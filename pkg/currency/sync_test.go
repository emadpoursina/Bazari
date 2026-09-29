package currency_test

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"github.com/ft-t/go-money/pkg/configuration"
	"github.com/ft-t/go-money/pkg/currency"
	"github.com/ft-t/go-money/pkg/database"
	"github.com/ft-t/go-money/pkg/testingutils"
	"github.com/golang/mock/gomock"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
	"io"
	"net/http"
	"testing"
	"time"
)

//go:embed testdata/rates.json
var mockResponse []byte

//go:embed testdata/rates_invalid_rebase.json
var invalidRebaseResponse []byte

func TestSync(t *testing.T) {
	t.Run("success", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return &http.Response{
					StatusCode: http.StatusOK,
					Body:       io.NopCloser(bytes.NewReader(mockResponse)),
				}, nil
			})

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{
			UpdateTransactionAmountInBaseCurrency: false,
			BaseCurrency:                          "USD",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.NoError(t, err)

		var currencies []*database.Currency
		assert.NoError(t, gormDB.Order("id asc").Find(&currencies).Error)

		assert.Len(t, currencies, 3)
		assert.Equal(t, "EUR", currencies[0].ID)
		assert.EqualValues(t, "0.85", currencies[0].Rate.String())
		assert.EqualValues(t, 2, currencies[0].DecimalPlaces)
		assert.Equal(t, database.CurrencyRateModeAutomatic, *currencies[0].RateMode)

		assert.Equal(t, "PLN", currencies[1].ID)
		assert.EqualValues(t, "3.8", currencies[1].Rate.String())
		assert.EqualValues(t, 2, currencies[1].DecimalPlaces)
		assert.Equal(t, database.CurrencyRateModeAutomatic, *currencies[1].RateMode)

		assert.Equal(t, "USD", currencies[2].ID)
		assert.EqualValues(t, "1", currencies[2].Rate.String())
		assert.EqualValues(t, 2, currencies[2].DecimalPlaces)
		assert.Nil(t, currencies[2].RateMode)
	})

	t.Run("success with rebase", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return &http.Response{
					StatusCode: http.StatusOK,
					Body:       io.NopCloser(bytes.NewReader(mockResponse)),
				}, nil
			})

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{
			UpdateTransactionAmountInBaseCurrency: false,
			BaseCurrency:                          "EUR",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.NoError(t, err)

		var currencies []*database.Currency
		assert.NoError(t, gormDB.Order("id asc").Find(&currencies).Error)

		assert.Len(t, currencies, 3)
		assert.Equal(t, "EUR", currencies[0].ID)
		assert.EqualValues(t, "1", currencies[0].Rate.String())
		assert.EqualValues(t, 2, currencies[0].DecimalPlaces)

		assert.Equal(t, "PLN", currencies[1].ID)
		assert.EqualValues(t, "4.47", currencies[1].Rate.StringFixed(2))
		assert.EqualValues(t, 2, currencies[1].DecimalPlaces)

		assert.Equal(t, "USD", currencies[2].ID)
		assert.EqualValues(t, "1.18", currencies[2].Rate.StringFixed(2))
		assert.EqualValues(t, 2, currencies[2].DecimalPlaces)
	})

	t.Run("success with recalculate", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockBaseSvc := NewMockBaseAmountSvc(gomock.NewController(t))

		mockBaseSvc.EXPECT().RecalculateAmountInBaseCurrencyForAll(gomock.Any(), gomock.Any()).
			Return(nil)

		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return &http.Response{
					StatusCode: http.StatusOK,
					Body:       io.NopCloser(bytes.NewReader(mockResponse)),
				}, nil
			})

		syn := currency.NewSyncer(mockClient, mockBaseSvc, configuration.CurrencyConfig{
			UpdateTransactionAmountInBaseCurrency: true,
			BaseCurrency:                          "USD",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.NoError(t, err)

		var currencies []*database.Currency
		assert.NoError(t, gormDB.Order("id asc").Find(&currencies).Error)

		assert.Len(t, currencies, 3)
		assert.Equal(t, "EUR", currencies[0].ID)
		assert.EqualValues(t, "0.85", currencies[0].Rate.String())
		assert.EqualValues(t, 2, currencies[0].DecimalPlaces)

		assert.Equal(t, "PLN", currencies[1].ID)
		assert.EqualValues(t, "3.8", currencies[1].Rate.String())
		assert.EqualValues(t, 2, currencies[1].DecimalPlaces)

		assert.Equal(t, "USD", currencies[2].ID)
		assert.EqualValues(t, "1", currencies[2].Rate.String())
		assert.EqualValues(t, 2, currencies[2].DecimalPlaces)
	})

	t.Run("success update", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		usd := &database.Currency{
			ID:            "USD",
			DecimalPlaces: 99,
			Rate:          decimal.RequireFromString("-1.0"),
			RateMode:      currencyRateModePointer(database.CurrencyRateModeManual),
		}
		assert.NoError(t, gormDB.Create(usd).Error)

		eur := &database.Currency{
			ID:            "EUR",
			DecimalPlaces: 88,
			Rate:          decimal.RequireFromString("-240"),
			RateMode:      currencyRateModePointer(database.CurrencyRateModeManual),
		}
		assert.NoError(t, gormDB.Create(eur).Error)

		pln := &database.Currency{
			ID:            "PLN",
			DecimalPlaces: 77,
			Rate:          decimal.RequireFromString("-3.8"),
			RateMode:      currencyRateModePointer(database.CurrencyRateModeAutomatic),
		}
		assert.NoError(t, gormDB.Create(pln).Error)

		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return &http.Response{
					StatusCode: http.StatusOK,
					Body:       io.NopCloser(bytes.NewReader(mockResponse)),
				}, nil
			})

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{
			BaseCurrency: "USD",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.NoError(t, err)

		var currencies []*database.Currency
		assert.NoError(t, gormDB.Order("id asc").Find(&currencies).Error)

		assert.Len(t, currencies, 3)

		assert.Equal(t, "EUR", currencies[0].ID)
		assert.EqualValues(t, "-240", currencies[0].Rate.String())
		assert.EqualValues(t, 88, currencies[0].DecimalPlaces)
		assert.Equal(t, database.CurrencyRateModeManual, *currencies[0].RateMode)

		assert.Equal(t, "PLN", currencies[1].ID)
		assert.EqualValues(t, "3.8", currencies[1].Rate.String())
		assert.EqualValues(t, 77, currencies[1].DecimalPlaces)
		assert.Equal(t, database.CurrencyRateModeAutomatic, *currencies[1].RateMode)

		assert.Equal(t, "USD", currencies[2].ID)
		assert.EqualValues(t, "1", currencies[2].Rate.String())
		assert.EqualValues(t, 99, currencies[2].DecimalPlaces)
		assert.Nil(t, currencies[2].RateMode)
	})

	t.Run("new currencies are automatic and invalid or omitted rates are ignored", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))

		for _, existing := range []*database.Currency{
			{ID: "EUR", Rate: decimal.NewFromInt(2), RateMode: currencyRateModePointer(database.CurrencyRateModeAutomatic)},
			{ID: "GBP", Rate: decimal.NewFromInt(3), RateMode: currencyRateModePointer(database.CurrencyRateModeAutomatic)},
			{ID: "NZD", Rate: decimal.NewFromInt(4), RateMode: currencyRateModePointer(database.CurrencyRateModeManual)},
			{ID: "USD", Rate: decimal.NewFromInt(99), RateMode: currencyRateModePointer(database.CurrencyRateModeManual)},
		} {
			assert.NoError(t, gormDB.Create(existing).Error)
		}

		body := []byte(`{"b":"USD","r":{"USD":1,"EUR":0,"GBP":-2,"JPY":3}}`)
		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).Return(&http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(bytes.NewReader(body)),
		}, nil)

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{BaseCurrency: "USD"})
		assert.NoError(t, syn.Sync(context.TODO(), "https://localhost/rates.json"))

		var currencies []*database.Currency
		assert.NoError(t, gormDB.Order("id asc").Find(&currencies).Error)
		byID := make(map[string]*database.Currency, len(currencies))
		for _, cur := range currencies {
			byID[cur.ID] = cur
		}
		assert.EqualValues(t, "2", byID["EUR"].Rate.String(), "zero feed rates must not replace stored rates")
		assert.EqualValues(t, "3", byID["GBP"].Rate.String(), "negative feed rates must not replace stored rates")
		assert.EqualValues(t, "4", byID["NZD"].Rate.String(), "omitted currencies must retain their rates")
		assert.EqualValues(t, "3", byID["JPY"].Rate.String())
		assert.Equal(t, database.CurrencyRateModeAutomatic, *byID["JPY"].RateMode)
		assert.EqualValues(t, "1", byID["USD"].Rate.String())
		assert.Nil(t, byID["USD"].RateMode)
	})

	t.Run("manual save wins while feed upsert waits on the currency row", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		assert.NoError(t, gormDB.Create(&database.Currency{
			ID:            "EUR",
			Rate:          decimal.RequireFromString("0.85"),
			DecimalPlaces: 2,
			RateMode:      currencyRateModePointer(database.CurrencyRateModeAutomatic),
		}).Error)

		lockTx := gormDB.Begin()
		assert.NoError(t, lockTx.Error)
		var lockedID string
		assert.NoError(t, lockTx.Raw(`SELECT id FROM currencies WHERE id = ? FOR UPDATE`, "EUR").Scan(&lockedID).Error)

		responseReady := make(chan struct{})
		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).DoAndReturn(func(*http.Request) (*http.Response, error) {
			close(responseReady)
			return &http.Response{
				StatusCode: http.StatusOK,
				Body:       io.NopCloser(bytes.NewReader([]byte(`{"b":"USD","r":{"USD":1,"EUR":1.2}}`))),
			}, nil
		})
		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{BaseCurrency: "USD"})
		syncDone := make(chan error, 1)
		go func() { syncDone <- syn.Sync(context.TODO(), "https://localhost/rates.json") }()
		<-responseReady

		assert.NoError(t, lockTx.Exec(`UPDATE currencies SET rate = ?, rate_mode = ? WHERE id = ?`, "1.75", database.CurrencyRateModeManual, "EUR").Error)
		assert.NoError(t, lockTx.Commit().Error)
		select {
		case err := <-syncDone:
			assert.NoError(t, err)
		case <-time.After(5 * time.Second):
			t.Fatal("sync did not finish after the manual update released the row lock")
		}

		var updated database.Currency
		assert.NoError(t, gormDB.Where("id = ?", "EUR").First(&updated).Error)
		assert.EqualValues(t, "1.75", updated.Rate.String())
		assert.Equal(t, database.CurrencyRateModeManual, *updated.RateMode)
	})

	t.Run("success with rebase", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return &http.Response{
					StatusCode: http.StatusOK,
					Body:       io.NopCloser(bytes.NewReader(invalidRebaseResponse)),
				}, nil
			})

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{
			UpdateTransactionAmountInBaseCurrency: false,
			BaseCurrency:                          "EUR",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.ErrorContains(t, err, "missing rate for new base")
	})

	t.Run("fail request", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return nil, assert.AnError
			})

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{
			BaseCurrency: "USD",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.Error(t, err)
	})

	t.Run("fail parse json", func(t *testing.T) {
		assert.NoError(t, testingutils.FlushAllTables(cfg.Db))
		remoteURL := "https://localhost/rates.json"

		mockClient := NewMockhttpClient(gomock.NewController(t))
		mockClient.EXPECT().Do(gomock.Any()).
			DoAndReturn(func(request *http.Request) (*http.Response, error) {
				assert.EqualValues(t, remoteURL, request.URL.String())

				return &http.Response{
					StatusCode: http.StatusOK,
					Body:       io.NopCloser(bytes.NewReader([]byte("invalid json"))),
				}, nil
			})

		syn := currency.NewSyncer(mockClient, nil, configuration.CurrencyConfig{
			BaseCurrency: "USD",
		})

		err := syn.Sync(context.TODO(), remoteURL)
		assert.Error(t, err)
	})
}

//go:embed testdata/rebase.json
var rebaseData []byte

func TestRebase(t *testing.T) {
	t.Run("success", func(t *testing.T) {
		var parsed currency.RemoteRates
		assert.NoError(t, json.Unmarshal(rebaseData, &parsed))

		srv := currency.NewSyncer(nil, nil, configuration.CurrencyConfig{
			BaseCurrency: "EUR",
		})

		result, err := srv.RebaseRates(context.TODO(), &parsed)
		assert.NoError(t, err)

		assert.Len(t, result.Rates, 163)

		assert.EqualValues(t, "EUR", result.Base)
		assert.EqualValues(t, "1.00", result.Rates["EUR"].StringFixed(2))
		assert.EqualValues(t, "1.17", result.Rates["USD"].StringFixed(2))
		assert.EqualValues(t, "4.26", result.Rates["PLN"].StringFixed(2))
	})

	t.Run("new base is missing", func(t *testing.T) {
		var parsed currency.RemoteRates
		assert.NoError(t, json.Unmarshal(rebaseData, &parsed))

		srv := currency.NewSyncer(nil, nil, configuration.CurrencyConfig{
			BaseCurrency: "XX",
		})

		result, err := srv.RebaseRates(context.TODO(), &parsed)
		assert.Nil(t, result)
		assert.ErrorContains(t, err, "missing rate for new base XX")
	})

	t.Run("zero rate", func(t *testing.T) {
		var parsed currency.RemoteRates
		assert.NoError(t, json.Unmarshal(rebaseData, &parsed))

		srv := currency.NewSyncer(nil, nil, configuration.CurrencyConfig{
			BaseCurrency: "EUR",
		})

		parsed.Rates["EUR"] = decimal.Zero

		result, err := srv.RebaseRates(context.TODO(), &parsed)
		assert.Nil(t, result)
		assert.ErrorContains(t, err, "rate for EUR is zero")
	})
}
