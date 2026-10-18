package main

import (
	"context"
	"strings"
	"time"

	"github.com/cockroachdb/errors"
	"github.com/sethvargo/go-envconfig"
)

// Config is the sidecar configuration, loaded from environment variables.
type Config struct {
	Port            int           `env:"PORT, default=8088"`
	NavasanAPIURL   string        `env:"NAVASAN_API_URL, default=http://api.navasan.tech/latest/"`
	NavasanAPIKey   string        `env:"NAVASAN_API_KEY, required"`
	PivotSymbol     string        `env:"NAVASAN_PIVOT_SYMBOL, default=usd_sell"`
	PivotCurrency   string        `env:"NAVASAN_PIVOT_CURRENCY, default=USD"`
	LocalCurrencies string        `env:"NAVASAN_LOCAL_CURRENCIES"`
	Currencies      string        `env:"NAVASAN_CURRENCIES"`
	Overrides       string        `env:"NAVASAN_SYMBOL_OVERRIDES"`
	CacheTTL        time.Duration `env:"NAVASAN_CACHE_TTL, default=5m"`
	HTTPTimeout     time.Duration `env:"NAVASAN_HTTP_TIMEOUT, default=30s"`
}

// defaultLocalCurrencies is applied in LoadConfig rather than as an envconfig
// tag default, because the value itself contains a comma.
const defaultLocalCurrencies = "IRT:1,IRR:10"

// noLocalCurrencies disables derived currencies entirely.
const noLocalCurrencies = "none"

// LoadConfig reads and normalizes the environment configuration.
func LoadConfig(ctx context.Context) (*Config, error) {
	var cfg Config

	if err := envconfig.ProcessWith(ctx, &envconfig.Config{Target: &cfg}); err != nil {
		return nil, errors.Wrap(err, "failed to load configuration")
	}

	cfg.PivotSymbol = strings.TrimSpace(cfg.PivotSymbol)
	cfg.PivotCurrency = strings.ToUpper(strings.TrimSpace(cfg.PivotCurrency))
	cfg.LocalCurrencies = strings.TrimSpace(cfg.LocalCurrencies)

	if cfg.LocalCurrencies == "" {
		cfg.LocalCurrencies = defaultLocalCurrencies
	}

	if strings.EqualFold(cfg.LocalCurrencies, noLocalCurrencies) {
		cfg.LocalCurrencies = ""
	}

	if cfg.PivotSymbol == "" {
		return nil, errors.New("NAVASAN_PIVOT_SYMBOL must not be empty")
	}

	if cfg.PivotCurrency == "" {
		return nil, errors.New("NAVASAN_PIVOT_CURRENCY must not be empty")
	}

	if cfg.CacheTTL <= 0 {
		return nil, errors.New("NAVASAN_CACHE_TTL must be positive")
	}

	if cfg.HTTPTimeout <= 0 {
		return nil, errors.New("NAVASAN_HTTP_TIMEOUT must be positive")
	}

	if cfg.Port <= 0 || cfg.Port > 65535 {
		return nil, errors.New("PORT must be within 1..65535")
	}

	return &cfg, nil
}

func splitCSV(raw string) []string {
	var out []string

	for _, part := range strings.Split(raw, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}

	return out
}
