# Data Model: Per-Currency Exchange Rate Modes

## Currency

The existing `currencies` row remains the source of truth for the current exchange rate and its update policy.

| Field | Type / domain | Rules |
|---|---|---|
| `id` | Currency code (`text`) | Primary key. The configured base currency is identified by `CurrencyConfig.BaseCurrency`. |
| `rate` | Decimal | Must be positive for a non-base currency. The base currency is always exactly `1`. |
| `rate_mode` | Nullable enum-like text: `manual`, `automatic`, or `NULL` | `manual`/`automatic` only for non-base currencies; `NULL` for the base currency. New rows created outside feed sync default to Manual. |
| `is_active` | Boolean | Existing behavior; independent of rate mode. |
| `decimal_places` | Integer | Existing display precision; independent of rate mode. |
| `updated_at` | Timestamp | Existing update timestamp. |
| `deleted_at` | Nullable timestamp | Existing soft-delete state. |

### Rollout and creation defaults

- The migration preserves every existing non-base rate and assigns Manual.
- The migration ensures the configured base row has rate `1` and no mode.
- A currency inserted by a valid feed rate is Automatic.
- A currency created manually through the currency administration service is Manual by default.
- An existing Automatic currency remains Automatic when a feed omits it or supplies an invalid rate.

### State transitions

| Current state | Event | Result |
|---|---|---|
| Manual | User saves a different rate | Save the rate and remain/set Manual atomically. |
| Automatic | User saves a different rate | Save the rate and switch to Manual atomically. |
| Manual | User selects Automatic | Keep the current rate; a later valid feed update may replace it. |
| Automatic | User selects Manual | Keep the current rate; later feed updates skip it. |
| Automatic | Valid feed rate is written | Replace rate; retain Automatic. The mode predicate is checked by the database in the conflict update. |
| Manual | Feed contains a valid rate | Keep rate and mode unchanged. |
| Either non-base mode | Feed fails, omits code, or has invalid rate | Keep rate and mode unchanged. |
| Base (`NULL`) | User update or feed update | Keep mode `NULL` and force rate to `1`. |

### Validation and invariants

- Rate mode accepts only Manual or Automatic for non-base currencies; an unsupported mode is rejected.
- An unspecified API mode means “do not change the current mode.” It is not a persisted third mode.
- A changed non-base rate in an update request takes precedence over a simultaneously requested Automatic mode and results in Manual.
- Feed rate parsing and positivity validation occur before persistence. An invalid rate never replaces the last valid rate.
- No mode value is assigned to the configured base currency.

## Exchange-rate feed update

An update consists of the feed's base currency, a mapping of currency codes to decimal rates, and optional feed timestamp metadata. The existing syncer rebases rates to the configured base when needed, forces the configured base entry to `1`, and applies each valid rate transactionally. Existing currency rows are updated only while their stored mode is Automatic; previously unknown feed currencies are inserted as Automatic. Missing currencies are not deleted or reset.
