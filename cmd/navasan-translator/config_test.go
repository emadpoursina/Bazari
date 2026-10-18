package main

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func clearNavasanEnv(t *testing.T) {
	t.Helper()

	for _, key := range []string{
		"PORT",
		"NAVASAN_API_URL",
		"NAVASAN_API_KEY",
		"NAVASAN_PIVOT_SYMBOL",
		"NAVASAN_PIVOT_CURRENCY",
		"NAVASAN_LOCAL_CURRENCIES",
		"NAVASAN_CURRENCIES",
		"NAVASAN_SYMBOL_OVERRIDES",
		"NAVASAN_CACHE_TTL",
		"NAVASAN_HTTP_TIMEOUT",
	} {
		original, had := os.LookupEnv(key)
		require.NoError(t, os.Unsetenv(key))

		t.Cleanup(func() {
			if had {
				_ = os.Setenv(key, original)
			} else {
				_ = os.Unsetenv(key)
			}
		})
	}
}

func TestLoadConfig(t *testing.T) {
	t.Run("defaults", func(t *testing.T) {
		clearNavasanEnv(t)
		t.Setenv("NAVASAN_API_KEY", "secret")

		cfg, err := LoadConfig(context.Background())
		require.NoError(t, err)

		assert.Equal(t, 8088, cfg.Port)
		assert.Equal(t, "http://api.navasan.tech/latest/", cfg.NavasanAPIURL)
		assert.Equal(t, "secret", cfg.NavasanAPIKey)
		assert.Equal(t, "usd_sell", cfg.PivotSymbol)
		assert.Equal(t, "USD", cfg.PivotCurrency)
		assert.Equal(t, "IRT:1,IRR:10", cfg.LocalCurrencies)
		assert.Equal(t, 5*time.Minute, cfg.CacheTTL)
		assert.Equal(t, 30*time.Second, cfg.HTTPTimeout)
	})

	t.Run("normalizes currency case", func(t *testing.T) {
		clearNavasanEnv(t)
		t.Setenv("NAVASAN_API_KEY", "secret")
		t.Setenv("NAVASAN_PIVOT_CURRENCY", " usd ")

		cfg, err := LoadConfig(context.Background())
		require.NoError(t, err)

		assert.Equal(t, "USD", cfg.PivotCurrency)
	})

	t.Run("local currencies can be set and disabled", func(t *testing.T) {
		clearNavasanEnv(t)
		t.Setenv("NAVASAN_API_KEY", "secret")
		t.Setenv("NAVASAN_LOCAL_CURRENCIES", "IRT:1,IRR:10,AED:3.67")

		cfg, err := LoadConfig(context.Background())
		require.NoError(t, err)
		assert.Equal(t, "IRT:1,IRR:10,AED:3.67", cfg.LocalCurrencies)

		t.Setenv("NAVASAN_LOCAL_CURRENCIES", "none")

		cfg, err = LoadConfig(context.Background())
		require.NoError(t, err)
		assert.Equal(t, "", cfg.LocalCurrencies)
	})

	t.Run("missing api key fails", func(t *testing.T) {
		clearNavasanEnv(t)

		cfg, err := LoadConfig(context.Background())
		require.Error(t, err)
		assert.Nil(t, cfg)
	})

	t.Run("empty pivot symbol fails", func(t *testing.T) {
		clearNavasanEnv(t)
		t.Setenv("NAVASAN_API_KEY", "secret")
		t.Setenv("NAVASAN_PIVOT_SYMBOL", " ")

		cfg, err := LoadConfig(context.Background())
		require.Error(t, err)
		assert.Nil(t, cfg)
	})

	t.Run("non positive cache ttl fails", func(t *testing.T) {
		clearNavasanEnv(t)
		t.Setenv("NAVASAN_API_KEY", "secret")
		t.Setenv("NAVASAN_CACHE_TTL", "0s")

		cfg, err := LoadConfig(context.Background())
		require.Error(t, err)
		assert.Nil(t, cfg)
	})

	t.Run("out of range port fails", func(t *testing.T) {
		clearNavasanEnv(t)
		t.Setenv("NAVASAN_API_KEY", "secret")
		t.Setenv("PORT", "70000")

		cfg, err := LoadConfig(context.Background())
		require.Error(t, err)
		assert.Nil(t, cfg)
	})
}
