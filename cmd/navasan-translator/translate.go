package main

import (
	"sort"
	"strings"
	"time"

	"github.com/cockroachdb/errors"
	"github.com/ft-t/go-money/pkg/currency"
	"github.com/shopspring/decimal"
)

// navasanISOCodes is the intersection of Navasan's published symbol list
// (264 symbols, including the plain-lowercase ones) and current ISO 4217.
//
// Navasan exposes every currency under its plain lowercase code, so "EUR" is
// read from "eur" and "XAU" from "xau". Non-ISO entries Navasan also publishes
// (BTC, NIM, ROB, CNH, GGP, JEP, IMP, CLF, MXV) and retired codes it still
// lists (BYR, LTL, LVL, STD, ZMK, CUC, HRK, SLL) are excluded. Pin any of them
// with NAVASAN_SYMBOL_OVERRIDES if you want them anyway.
//
// IRR is deliberately absent even though Navasan has an "irr" symbol: the feed
// is Toman-denominated, so Rial is derived from Toman instead (see localUnit).
// Reading "irr" would risk storing a rate with unverified semantics.
var navasanISOCodes = []string{
	"AED", "AFN", "ALL", "AMD", "ANG", "AOA", "ARS", "AUD", "AWG", "AZN",
	"BAM", "BBD", "BDT", "BGN", "BHD", "BIF", "BMD", "BND", "BOB", "BRL",
	"BSD", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF", "CHF", "CLP", "CNY",
	"COP", "CRC", "CUP", "CVE", "CZK", "DJF", "DKK", "DOP", "DZD", "EGP",
	"ERN", "ETB", "EUR", "FJD", "FKP", "GBP", "GEL", "GHS", "GIP", "GMD",
	"GNF", "GTQ", "GYD", "HKD", "HNL", "HTG", "HUF", "IDR", "ILS", "INR",
	"IQD", "ISK", "JMD", "JOD", "JPY", "KES", "KGS", "KHR", "KMF",
	"KPW", "KRW", "KWD", "KYD", "KZT", "LAK", "LBP", "LKR", "LRD", "LSL",
	"LYD", "MAD", "MDL", "MGA", "MKD", "MMK", "MNT", "MOP", "MRU", "MUR",
	"MVR", "MWK", "MXN", "MYR", "MZN", "NAD", "NGN", "NIO", "NOK", "NPR",
	"NZD", "OMR", "PAB", "PEN", "PGK", "PHP", "PKR", "PLN", "PYG", "QAR",
	"RON", "RSD", "RUB", "RWF", "SAR", "SBD", "SCR", "SDG", "SEK", "SGD",
	"SHP", "SLE", "SOS", "SRD", "SSP", "STN", "SVC", "SYP", "SZL", "THB",
	"TJS", "TMT", "TND", "TOP", "TRY", "TTD", "TWD", "TZS", "UAH", "UGX",
	"USD", "UYU", "UZS", "VES", "VND", "VUV", "WST", "XAF", "XAG", "XAU",
	"XCD", "XCG", "XDR", "XOF", "XPF", "YER", "ZAR", "ZMW", "ZWL",
}

// localUnit is a currency derived from the unit the feed is priced in (Toman)
// instead of being read from a Navasan symbol. IRR is derived this way because
// Navasan quotes Toman, where 1 Toman == 10 Rial, so the relationship is exact.
type localUnit struct {
	currency   string
	multiplier decimal.Decimal
}

// translator converts Navasan's flat, Toman-denominated feed into the
// RemoteRates contract the go-money server consumes.
type translator struct {
	pivotCurrency string
	locals        []localUnit
	symbols       map[string]string // ISO code -> Navasan item symbol
	pinned        map[string]struct{}
}

// buildSymbols resolves which Navasan symbol feeds which ISO code.
func buildSymbols(cfg *Config) (map[string]string, error) {
	symbols := make(map[string]string)

	codes := navasanISOCodes
	if configured := splitCSV(cfg.Currencies); len(configured) > 0 {
		codes = configured
	}

	for _, code := range codes {
		code = strings.ToUpper(code)
		symbols[code] = strings.ToLower(code)
	}

	// The default symbol for the pivot currency. A NAVASAN_SYMBOL_OVERRIDES
	// entry for the same currency wins, so there is a single way to override it.
	symbols[cfg.PivotCurrency] = cfg.PivotSymbol

	for _, override := range splitCSV(cfg.Overrides) {
		iso, symbol, ok := strings.Cut(override, ":")
		if !ok {
			return nil, errors.Errorf("invalid NAVASAN_SYMBOL_OVERRIDES entry %q, expected ISO:symbol", override)
		}

		iso = strings.ToUpper(strings.TrimSpace(iso))
		symbol = strings.TrimSpace(symbol)

		if iso == "" || symbol == "" {
			return nil, errors.Errorf("invalid NAVASAN_SYMBOL_OVERRIDES entry %q, expected ISO:symbol", override)
		}

		symbols[iso] = symbol
	}

	return symbols, nil
}

// parseLocalUnits parses NAVASAN_LOCAL_CURRENCIES, e.g. "IRT:1,IRR:10".
func parseLocalUnits(raw string) ([]localUnit, error) {
	var units []localUnit

	for _, entry := range splitCSV(raw) {
		iso, multiplier, ok := strings.Cut(entry, ":")
		if !ok {
			return nil, errors.Errorf("invalid NAVASAN_LOCAL_CURRENCIES entry %q, expected ISO:multiplier", entry)
		}

		iso = strings.ToUpper(strings.TrimSpace(iso))
		multiplier = strings.TrimSpace(multiplier)

		if iso == "" || multiplier == "" {
			return nil, errors.Errorf("invalid NAVASAN_LOCAL_CURRENCIES entry %q, expected ISO:multiplier", entry)
		}

		value, err := decimal.NewFromString(multiplier)
		if err != nil {
			return nil, errors.Wrapf(err, "invalid multiplier in NAVASAN_LOCAL_CURRENCIES entry %q", entry)
		}

		if !value.IsPositive() {
			return nil, errors.Errorf("multiplier must be positive in NAVASAN_LOCAL_CURRENCIES entry %q", entry)
		}

		units = append(units, localUnit{currency: iso, multiplier: value})
	}

	return units, nil
}

// pinnedCodes lists the ISO codes named explicitly in NAVASAN_SYMBOL_OVERRIDES.
func pinnedCodes(overrides string) map[string]struct{} {
	pinned := make(map[string]struct{})

	for _, override := range splitCSV(overrides) {
		if iso, _, ok := strings.Cut(override, ":"); ok {
			pinned[strings.ToUpper(strings.TrimSpace(iso))] = struct{}{}
		}
	}

	return pinned
}

func newTranslator(cfg *Config) (*translator, error) {
	symbols, err := buildSymbols(cfg)
	if err != nil {
		return nil, err
	}

	locals, err := parseLocalUnits(cfg.LocalCurrencies)
	if err != nil {
		return nil, err
	}

	return &translator{
		pivotCurrency: cfg.PivotCurrency,
		locals:        locals,
		symbols:       symbols,
		pinned:        pinnedCodes(cfg.Overrides),
	}, nil
}

func (t *translator) isLocal(iso string) bool {
	for _, local := range t.locals {
		if local.currency == iso {
			return true
		}
	}

	return false
}

// Translate expresses every rate as "units of X per 1 pivot currency". The
// go-money server rebases to its own CURRENCY_CONFIG_BASE_CURRENCY when that
// differs from the pivot.
func (t *translator) Translate(latest navasanLatest, now time.Time) (*currency.RemoteRates, error) {
	pivotSymbol := t.symbols[t.pivotCurrency]
	if pivotSymbol == "" {
		return nil, errors.Errorf("no navasan symbol configured for pivot currency %q", t.pivotCurrency)
	}

	pivot, ok := latest[pivotSymbol]
	if !ok {
		return nil, errors.Errorf("pivot symbol %q is missing from the navasan feed", pivotSymbol)
	}

	pivotValue, err := parseRate(pivot.Value)
	if err != nil {
		return nil, errors.Wrapf(err, "failed to parse pivot symbol %q", pivotSymbol)
	}

	if !pivotValue.IsPositive() {
		return nil, errors.Errorf("pivot symbol %q has non-positive value %s", pivotSymbol, pivotValue)
	}

	rates := map[string]decimal.Decimal{
		t.pivotCurrency: decimal.NewFromInt(1),
	}

	// Currencies derived from the feed's pricing unit (Toman) always win over
	// any Navasan symbol of the same name; see the localUnit comment.
	for _, local := range t.locals {
		if local.currency == t.pivotCurrency {
			continue
		}

		rates[local.currency] = pivotValue.Mul(local.multiplier)
	}

	for iso, symbol := range t.symbols {
		if iso == t.pivotCurrency || t.isLocal(iso) {
			continue
		}

		item, ok := latest[symbol]
		if !ok {
			continue
		}

		value, err := parseRate(item.Value)
		if err != nil || !value.IsPositive() {
			continue
		}

		rates[iso] = value.Div(pivotValue)
	}

	return &currency.RemoteRates{
		Base:      t.pivotCurrency,
		Rates:     rates,
		UpdatedAt: now.UTC(),
	}, nil
}

// MissingPinned returns the explicitly pinned ISO codes that produced no rate,
// which almost always means the configured Navasan symbol is absent or empty.
func (t *translator) MissingPinned(rates *currency.RemoteRates) []string {
	var missing []string

	for iso := range t.pinned {
		if _, ok := rates.Rates[iso]; !ok {
			missing = append(missing, iso)
		}
	}

	sort.Strings(missing)

	return missing
}

func parseRate(raw flexNumber) (decimal.Decimal, error) {
	value := strings.TrimSpace(strings.ReplaceAll(string(raw), ",", ""))
	if value == "" || value == "null" {
		return decimal.Zero, errors.New("empty rate value")
	}

	return decimal.NewFromString(value)
}
