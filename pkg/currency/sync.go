package currency

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"github.com/cockroachdb/errors"
	"github.com/ft-t/go-money/pkg/configuration"
	"github.com/ft-t/go-money/pkg/database"
	"github.com/rs/zerolog"
	"github.com/shopspring/decimal"
	"net/http"
	"time"
)

type Syncer struct {
	cl            httpClient
	cfg           configuration.CurrencyConfig
	baseAmountSvc BaseAmountSvc
}

func NewSyncer(
	cl httpClient,
	baseAmountSvc BaseAmountSvc,
	config configuration.CurrencyConfig,
) *Syncer {
	return &Syncer{
		cl:            cl,
		baseAmountSvc: baseAmountSvc,
		cfg:           config,
	}
}

func (s *Syncer) RebaseRates(
	_ context.Context,
	currentRates *RemoteRates,
) (*RemoteRates, error) {
	newBase := s.cfg.BaseCurrency

	newBaseRate, ok := currentRates.Rates[newBase]
	if !ok {
		return nil, fmt.Errorf("missing rate for new base %v", newBase)
	}

	if newBaseRate.IsZero() {
		return nil, fmt.Errorf("rate for %s is zero", newBase)
	}
	if newBaseRate.IsNegative() {
		return nil, fmt.Errorf("rate for %s must be positive", newBase)
	}

	rebased := make(map[string]decimal.Decimal)

	inverted := decimal.NewFromInt(1).Div(newBaseRate)

	rebased[currentRates.Base] = inverted

	for currency, rate := range currentRates.Rates {
		if currency == newBase {
			continue
		}

		rebased[currency] = rate.Div(newBaseRate)
	}

	rebased[newBase] = decimal.NewFromInt(1)

	return &RemoteRates{
		Base:      newBase,
		Rates:     rebased,
		UpdatedAt: currentRates.UpdatedAt,
	}, nil
}

func (s *Syncer) Sync(
	ctx context.Context,
	remoteURL string,
) error {
	httpReq, httpReqErr := http.NewRequestWithContext(ctx, "GET", remoteURL, nil)
	if httpReqErr != nil {
		return httpReqErr
	}

	resp, err := s.cl.Do(httpReq)
	if err != nil {
		return err
	}

	if resp.Body != nil {
		defer func() {
			_ = resp.Body.Close()
		}()
	}

	var parsed *RemoteRates
	if err = json.NewDecoder(resp.Body).Decode(&parsed); err != nil {
		return err
	}

	tx := database.FromContext(ctx, database.GetDbWithContext(ctx, database.DbTypeMaster)).Begin()
	defer tx.Rollback()

	if parsed.Base != s.cfg.BaseCurrency {
		oldBase := parsed.Base

		parsed, err = s.RebaseRates(ctx, parsed)

		if err != nil {
			return errors.Wrapf(err, "failed to rebase rates to %s", s.cfg.BaseCurrency)
		}

		zerolog.Ctx(ctx).Info().Str("from", oldBase).Str("to", parsed.Base).Msg("rebased rates")
	}

	parsed.Rates[s.cfg.BaseCurrency] = decimal.NewFromInt(1)

	for currency, rate := range parsed.Rates {
		updatedAt := time.Now().UTC()
		if currency == s.cfg.BaseCurrency {
			// The base row is an invariant, not a feed-controlled rate mode.
			// Explicit NULL avoids the database's Manual default on insertion.
			err = tx.Exec(`INSERT INTO currencies (id, rate, is_active, decimal_places, updated_at, rate_mode)
				VALUES (?, 1, false, ?, ?, NULL)
				ON CONFLICT ON CONSTRAINT currencies_pk DO UPDATE
				SET rate = 1, rate_mode = NULL, updated_at = EXCLUDED.updated_at`,
				currency, configuration.DefaultDecimalPlaces, updatedAt).Error
		} else {
			if !rate.IsPositive() {
				continue
			}

			err = tx.Exec(`INSERT INTO currencies (id, rate, is_active, decimal_places, updated_at, rate_mode)
				VALUES (?, ?, false, ?, ?, ?)
				ON CONFLICT ON CONSTRAINT currencies_pk DO UPDATE
				SET rate = EXCLUDED.rate, updated_at = EXCLUDED.updated_at
				WHERE currencies.rate_mode = ?`,
				currency, rate, configuration.DefaultDecimalPlaces, updatedAt,
				database.CurrencyRateModeAutomatic, database.CurrencyRateModeAutomatic).Error
		}
		if err != nil {
			return err
		}
	}

	if s.cfg.UpdateTransactionAmountInBaseCurrency {
		if err = s.baseAmountSvc.RecalculateAmountInBaseCurrencyForAll(ctx, tx); err != nil {
			return errors.Wrap(err, "failed to recalculate")
		}
	}

	return tx.Commit().Error
}
