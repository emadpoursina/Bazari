package database

import (
	"testing"

	"github.com/ft-t/go-money/pkg/configuration"
	"github.com/go-gormigrate/gormigrate/v2"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/require"
)

func TestCurrencyRateModeMigrationPreservesRatesAndClassifiesRows(t *testing.T) {
	db := GetDb(DbTypeMaster)
	tx := db.Begin()
	require.NoError(t, tx.Error)
	t.Cleanup(func() { _ = tx.Rollback().Error })

	require.NoError(t, tx.Exec(`CREATE TEMPORARY TABLE currencies (
		id text NOT NULL,
		rate numeric NOT NULL,
		CONSTRAINT currencies_pk PRIMARY KEY (id)
	)`).Error)

	cfg := configuration.GetConfiguration()
	baseCurrency := cfg.CurrencyConfig.BaseCurrency
	legacyCurrency := "RATE_TEST"
	require.NoError(t, tx.Exec(`INSERT INTO currencies (id, rate) VALUES (?, ?), (?, ?)`, baseCurrency, "2.5", legacyCurrency, "0.84").Error)

	var migration *gormigrate.Migration
	for _, candidate := range getMigrations(cfg) {
		if candidate.ID == "2026-09-29-AddCurrencyRateMode" {
			migration = candidate
			break
		}
	}
	require.NotNil(t, migration, "currency rate mode migration should be registered")
	require.NoError(t, migration.Migrate(tx))

	var rows []struct {
		ID       string
		Rate     decimal.Decimal
		RateMode *string
	}
	require.NoError(t, tx.Table("currencies").Select("id, rate, rate_mode").Order("id").Scan(&rows).Error)
	require.Len(t, rows, 2)

	for _, row := range rows {
		if row.ID == baseCurrency {
			require.True(t, row.Rate.Equal(decimal.NewFromInt(1)))
			require.Nil(t, row.RateMode)
			continue
		}

		require.Equal(t, legacyCurrency, row.ID)
		require.True(t, row.Rate.Equal(decimal.RequireFromString("0.84")), "migration must preserve existing non-base rates")
		require.NotNil(t, row.RateMode)
		require.Equal(t, "manual", *row.RateMode)
	}

	require.NoError(t, tx.Exec(`INSERT INTO currencies (id, rate) VALUES (?, ?)`, "JPY", "150").Error)
	var defaultMode string
	require.NoError(t, tx.Raw(`SELECT rate_mode FROM currencies WHERE id = ?`, "JPY").Scan(&defaultMode).Error)
	require.Equal(t, "manual", defaultMode)
}
