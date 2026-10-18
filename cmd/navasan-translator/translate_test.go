package main

import (
	_ "embed"
	"encoding/json"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/ft-t/go-money/pkg/currency"
	"github.com/shopspring/decimal"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

//go:embed testdata/latest.json
var latestFixture []byte

func testConfig() *Config {
	return &Config{
		PivotSymbol:     "usd_sell",
		PivotCurrency:   "USD",
		LocalCurrencies: defaultLocalCurrencies,
	}
}

func newTestTranslator(t *testing.T, cfg *Config) *translator {
	t.Helper()

	if cfg == nil {
		cfg = testConfig()
	}

	tr, err := newTranslator(cfg)
	require.NoError(t, err)

	return tr
}

func loadFixture(t *testing.T) navasanLatest {
	t.Helper()

	var latest navasanLatest
	require.NoError(t, json.Unmarshal(latestFixture, &latest))

	return latest
}

// rateOf returns a rates entry as a comparable decimal, failing the test when
// the currency is absent so a silent drop is never mistaken for a pass.
func rateOf(t *testing.T, rates *currency.RemoteRates, code string) decimal.Decimal {
	t.Helper()

	value, ok := rates.Rates[code]
	require.True(t, ok, "expected %s to be present in the feed", code)

	return value
}

func TestTranslate(t *testing.T) {
	now := time.Date(2026, 10, 18, 12, 0, 0, 0, time.UTC)
	pivot := decimal.NewFromInt(11270)

	t.Run("converts toman feed into pivot-relative rates", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(loadFixture(t), now)
		require.NoError(t, err)

		assert.Equal(t, "USD", rates.Base)
		assert.Equal(t, now, rates.UpdatedAt)

		assert.True(t, decimal.NewFromInt(1).Equal(rateOf(t, rates, "USD")), "pivot is always 1")

		want := map[string]decimal.Decimal{
			"EUR": decimal.NewFromInt(12500).Div(pivot),
			"JPY": decimal.NewFromInt(105).Div(pivot),
			"GBP": decimal.NewFromInt(14200).Div(pivot), // bare numeric value
			"AED": decimal.NewFromInt(3050).Div(pivot),
		}

		for code, expected := range want {
			assert.True(t, expected.Equal(rateOf(t, rates, code)), "%s=%s want=%s", code, rates.Rates[code], expected)
		}
	})

	t.Run("derives toman and rial without reading the irr symbol", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(loadFixture(t), now)
		require.NoError(t, err)

		// 1 Toman per unit of the feed, 10 Rial per Toman.
		assert.True(t, pivot.Equal(rateOf(t, rates, "IRT")), "IRT=%s", rates.Rates["IRT"])
		assert.True(t, pivot.Mul(decimal.NewFromInt(10)).Equal(rateOf(t, rates, "IRR")), "IRR=%s", rates.Rates["IRR"])

		// The fixture carries a bogus "irr" symbol; the derived value must win.
		assert.False(t, decimal.NewFromInt(999999).Div(pivot).Equal(rateOf(t, rates, "IRR")))
	})

	t.Run("passes through precious metals as ISO codes", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(loadFixture(t), now)
		require.NoError(t, err)

		assert.True(t, decimal.NewFromInt(60000000).Div(pivot).Equal(rateOf(t, rates, "XAU")),
			"XAU=%s", rates.Rates["XAU"])
		assert.True(t, decimal.NewFromInt(700000).Div(pivot).Equal(rateOf(t, rates, "XAG")),
			"XAG=%s", rates.Rates["XAG"])
	})

	t.Run("pivot symbol overrides the plain ISO symbol", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(loadFixture(t), now)
		require.NoError(t, err)

		// "usd" is 11200 but the configured pivot is "usd_sell" (11270).
		assert.True(t, pivot.Equal(rateOf(t, rates, "IRT")))
	})

	t.Run("excludes non-currencies and empty values", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(loadFixture(t), now)
		require.NoError(t, err)

		for _, excluded := range []string{"SEKKEH", "BTC", "MRO", "AFN", "NIM", "ROB"} {
			assert.NotContains(t, rates.Rates, excluded)
		}
	})

	t.Run("symbol overrides win", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "USD:usd, AED:aed_sell"

		rates, err := newTestTranslator(t, cfg).Translate(loadFixture(t), now)
		require.NoError(t, err)

		// USD pivot now comes from "usd" (11200) ...
		usdPivot := decimal.NewFromInt(11200)
		assert.True(t, usdPivot.Equal(rateOf(t, rates, "IRT")))

		// ... so IRT/IRR follow the new pivot ...
		assert.True(t, usdPivot.Mul(decimal.NewFromInt(10)).Equal(rateOf(t, rates, "IRR")))

		// ... and AED uses the "aed_sell" variant (3060).
		wantAED := decimal.NewFromInt(3060).Div(usdPivot)
		assert.True(t, wantAED.Equal(rateOf(t, rates, "AED")), "aed=%s want=%s", rates.Rates["AED"], wantAED)
	})

	t.Run("local currencies can be reconfigured", func(t *testing.T) {
		cfg := testConfig()
		cfg.LocalCurrencies = "IRT:1,IRR:10,IRR_OLD:0.5"

		rates, err := newTestTranslator(t, cfg).Translate(loadFixture(t), now)
		require.NoError(t, err)

		assert.True(t, pivot.Mul(decimal.RequireFromString("0.5")).Equal(rateOf(t, rates, "IRR_OLD")))
	})

	t.Run("local currencies can be disabled", func(t *testing.T) {
		cfg := testConfig()
		cfg.LocalCurrencies = ""

		rates, err := newTestTranslator(t, cfg).Translate(loadFixture(t), now)
		require.NoError(t, err)

		assert.NotContains(t, rates.Rates, "IRT")
		assert.NotContains(t, rates.Rates, "IRR")
		assert.Equal(t, "USD", rates.Base)
	})

	t.Run("missing pivot fails", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(navasanLatest{"eur": {Value: "1"}}, now)
		require.Error(t, err)
		assert.Nil(t, rates)
	})

	t.Run("non-positive pivot fails", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(navasanLatest{"usd_sell": {Value: "0"}}, now)
		require.Error(t, err)
		assert.Nil(t, rates)
	})

	t.Run("unparseable pivot fails", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(navasanLatest{"usd_sell": {Value: ""}}, now)
		require.Error(t, err)
		assert.Nil(t, rates)
	})

	t.Run("negative pivot fails", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(navasanLatest{"usd_sell": {Value: "-5"}}, now)
		require.Error(t, err)
		assert.Nil(t, rates)
	})

	t.Run("serializes into the schema the server decodes", func(t *testing.T) {
		rates, err := newTestTranslator(t, nil).Translate(loadFixture(t), now)
		require.NoError(t, err)

		payload, err := json.Marshal(rates)
		require.NoError(t, err)

		// Decode with the exact type pkg/currency.Sync uses, so this test fails
		// if the wire contract ever drifts from what the server expects.
		var decoded currency.RemoteRates
		require.NoError(t, json.Unmarshal(payload, &decoded))

		assert.Equal(t, "USD", decoded.Base)
		assert.True(t, pivot.Equal(decoded.Rates["IRT"]))
		assert.True(t, pivot.Mul(decimal.NewFromInt(10)).Equal(decoded.Rates["IRR"]))
		assert.Equal(t, now, decoded.UpdatedAt.UTC())
	})
}

func TestMissingPinned(t *testing.T) {
	t.Run("reports pinned currencies with no usable value", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "XAU:xau, XAG:xag_missing, BTC:btc, EUR:eur"

		tr := newTestTranslator(t, cfg)

		rates, err := tr.Translate(loadFixture(t), time.Now())
		require.NoError(t, err)

		// XAU resolves, EUR resolves, but the pinned XAG symbol does not exist
		// in the feed.
		assert.Equal(t, []string{"XAG"}, tr.MissingPinned(rates))
	})

	t.Run("is empty when nothing is pinned", func(t *testing.T) {
		tr := newTestTranslator(t, nil)

		rates, err := tr.Translate(loadFixture(t), time.Now())
		require.NoError(t, err)

		assert.Empty(t, tr.MissingPinned(rates))
	})
}

func TestBuildSymbols(t *testing.T) {
	t.Run("defaults expose the ISO allowlist", func(t *testing.T) {
		symbols, err := buildSymbols(testConfig())
		require.NoError(t, err)

		assert.Equal(t, "usd_sell", symbols["USD"])
		assert.Equal(t, "eur", symbols["EUR"])
		assert.Equal(t, "jpy", symbols["JPY"])

		// Precious metals are ISO 4217 and Navasan publishes them.
		assert.Equal(t, "xau", symbols["XAU"])
		assert.Equal(t, "xag", symbols["XAG"])

		// Navasan-only symbols that are not currencies must stay out.
		for _, excluded := range []string{"BTC", "NIM", "ROB", "CNH", "GGP", "IMP", "MXV"} {
			assert.NotContains(t, symbols, excluded)
		}

		// IRR is derived from Toman, never read from Navasan's "irr" symbol.
		assert.NotContains(t, symbols, "IRR")
	})

	t.Run("explicit currency list replaces the allowlist", func(t *testing.T) {
		cfg := testConfig()
		cfg.Currencies = "USD, eur ,GBP"

		symbols, err := buildSymbols(cfg)
		require.NoError(t, err)

		assert.Equal(t, "usd_sell", symbols["USD"])
		assert.Equal(t, "eur", symbols["EUR"])
		assert.Equal(t, "gbp", symbols["GBP"])
		assert.NotContains(t, symbols, "JPY")
	})

	t.Run("overrides are applied", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "aed:aed_sell, usd:mex_usd_sell"

		symbols, err := buildSymbols(cfg)
		require.NoError(t, err)

		assert.Equal(t, "aed_sell", symbols["AED"])
		assert.Equal(t, "mex_usd_sell", symbols["USD"])
	})

	t.Run("overrides can add currencies outside the allowlist", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "XAG:xag, CNH:cnh"

		symbols, err := buildSymbols(cfg)
		require.NoError(t, err)

		assert.Equal(t, "xag", symbols["XAG"])
		assert.Equal(t, "cnh", symbols["CNH"])
	})

	t.Run("malformed override fails", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "aed"

		symbols, err := buildSymbols(cfg)
		require.Error(t, err)
		assert.Nil(t, symbols)
	})

	t.Run("override with empty symbol fails", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "aed:"

		symbols, err := buildSymbols(cfg)
		require.Error(t, err)
		assert.Nil(t, symbols)
	})
}

func TestNavasanISOCodes(t *testing.T) {
	t.Run("is a sorted set of unique uppercase ISO codes", func(t *testing.T) {
		require.NotEmpty(t, navasanISOCodes)

		seen := make(map[string]struct{}, len(navasanISOCodes))

		for i, code := range navasanISOCodes {
			require.Len(t, code, 3, "entry %d (%q) must be a 3 letter code", i, code)
			assert.Equal(t, code, strings.ToUpper(code), "entry %d (%q) must be uppercase", i, code)
			assert.NotContains(t, seen, code, "entry %d (%q) is a duplicate", i, code)

			seen[code] = struct{}{}
		}

		assert.True(t, sort.StringsAreSorted(navasanISOCodes), "allowlist must stay sorted")
	})

	t.Run("contains the currencies the live feed serves", func(t *testing.T) {
		for _, code := range []string{"USD", "EUR", "GBP", "CHF", "JPY", "AED", "TRY", "CNY", "XAU", "XAG"} {
			assert.Contains(t, navasanISOCodes, code)
		}

		// Derived from Toman rather than read from Navasan's "irr" symbol.
		assert.NotContains(t, navasanISOCodes, "IRR")
	})
}

func TestParseLocalUnits(t *testing.T) {
	t.Run("parses multiple units", func(t *testing.T) {
		units, err := parseLocalUnits("IRT:1, IRR:10")
		require.NoError(t, err)
		require.Len(t, units, 2)

		assert.Equal(t, "IRT", units[0].currency)
		assert.True(t, decimal.NewFromInt(1).Equal(units[0].multiplier))
		assert.Equal(t, "IRR", units[1].currency)
		assert.True(t, decimal.NewFromInt(10).Equal(units[1].multiplier))
	})

	t.Run("uppercases the currency", func(t *testing.T) {
		units, err := parseLocalUnits(" irt : 1 ")
		require.NoError(t, err)
		require.Len(t, units, 1)

		assert.Equal(t, "IRT", units[0].currency)
	})

	t.Run("empty input yields no units", func(t *testing.T) {
		units, err := parseLocalUnits("")
		require.NoError(t, err)
		assert.Empty(t, units)
	})

	t.Run("missing multiplier fails", func(t *testing.T) {
		units, err := parseLocalUnits("IRT")
		require.Error(t, err)
		assert.Nil(t, units)
	})

	t.Run("non numeric multiplier fails", func(t *testing.T) {
		units, err := parseLocalUnits("IRT:abc")
		require.Error(t, err)
		assert.Nil(t, units)
	})

	t.Run("non positive multiplier fails", func(t *testing.T) {
		units, err := parseLocalUnits("IRT:0")
		require.Error(t, err)
		assert.Nil(t, units)
	})
}

func TestNewTranslatorRejectsBadConfig(t *testing.T) {
	t.Run("invalid local currencies", func(t *testing.T) {
		cfg := testConfig()
		cfg.LocalCurrencies = "IRT:abc"

		tr, err := newTranslator(cfg)
		require.Error(t, err)
		assert.Nil(t, tr)
	})

	t.Run("invalid overrides", func(t *testing.T) {
		cfg := testConfig()
		cfg.Overrides = "broken"

		tr, err := newTranslator(cfg)
		require.Error(t, err)
		assert.Nil(t, tr)
	})
}

func TestParseRate(t *testing.T) {
	t.Run("accepts quoted and bare numbers", func(t *testing.T) {
		quoted, err := parseRate("11270")
		require.NoError(t, err)
		assert.True(t, decimal.NewFromInt(11270).Equal(quoted))

		bare, err := parseRate("11270.55")
		require.NoError(t, err)
		assert.True(t, decimal.RequireFromString("11270.55").Equal(bare))
	})

	t.Run("strips thousands separators", func(t *testing.T) {
		value, err := parseRate("11,270")
		require.NoError(t, err)
		assert.True(t, decimal.NewFromInt(11270).Equal(value))
	})

	t.Run("rejects empty and null", func(t *testing.T) {
		for _, raw := range []flexNumber{"", "null", "   "} {
			value, err := parseRate(raw)
			require.Error(t, err, "raw=%q", raw)
			assert.True(t, value.IsZero())
		}
	})
}
