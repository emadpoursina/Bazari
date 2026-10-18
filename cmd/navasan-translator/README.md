# navasan-translator

A small sidecar that adapts the [Navasan](https://www.navasan.net/api/webserviceguide/)
exchange-rate API to the wire format the go-money server already polls.

It exists because Navasan's JSON does not match either format the project can
parse today:

| | go-money expects | Navasan returns |
| --- | --- | --- |
| Endpoint | `latest.json` (`EXCHANGE_RATES_URL`) | `GET http://api.navasan.tech/latest/?api_key=…` |
| Shape | `{"b":"USD","r":{"EUR":0.92},"u":"…"}` | flat map: `{"usd_sell":{"value":"11270",…}}` |
| Base | a currency code in the payload | implicit — everything is priced in Toman |
| Values | numbers | strings |
| Errors | JSON | plain text (`Invalid api_key`) |

The sidecar calls Navasan, pivots every rate on a trusted USD symbol, and serves
the result at `/latest.json`. The go-money server keeps using its existing
end-of-day job (`EXCHANGE_RATES_URL`, `cmd/server/internal/jobs/jobs.go:33`) and
needs **no code changes and no rebuild**.

## How the rates are computed

Navasan quotes everything in Toman. For each currency `X` taken from Navasan:

```
rate[X]     = toman(X) / toman(pivot)   # units of X per 1 pivot currency
rate[pivot] = 1
```

Currencies that are *derived* rather than read from a Navasan symbol
(`NAVASAN_LOCAL_CURRENCIES`) are scaled from the pivot instead:

```
rate[C] = toman(pivot) * multiplier
```

The payload is emitted with `"b"` set to the pivot currency (default `USD`), so
`r` is expressed as *units of X per 1 USD*. If go-money's own
`CURRENCY_CONFIG_BASE_CURRENCY` differs, `pkg/currency.RebaseRates` converts it
on the server side — as long as that currency appears in `r`.

## Rial, Toman, and precious metals

Three currencies that need care:

| Code | Where it comes from | Why |
| --- | --- | --- |
| `IRT` | Derived, multiplier `1` | Toman is the unit Navasan prices in |
| `IRR` | Derived, multiplier `10` | 1 Toman = 10 Rial. **Not** read from Navasan's `irr` symbol, which reports `0.15` — not a rate in any unit. Silently writing that to the ledger would be far worse than deriving it |
| `XAU` / `XAG` | Navasan symbols `xau` / `xag` | Gold and silver per ounce, quoted in Toman like everything else. Both are ISO 4217 codes and are in the default allowlist |

Verified against the live feed on 2026-10-18, with `usd_sell = 262,400`
Toman/USD:

| | Value | Cross-check |
| --- | --- | --- |
| `IRT` | `262400` | Toman per USD |
| `IRR` | `2624000` | exactly 10x `IRT` |
| `XAU` | `4165.56` USD/oz | Navasan's independent `usd_xau` field says `4165.58` |
| `XAG` | `56.98` USD/oz | gold/silver ratio of 73, in the normal range |

The `XAU` cross-check is the important one: `xau` is quoted in Toman
(1,093,042,940/oz) while `usd_xau` is quoted in USD, and dividing by `usd_sell`
reproduces the dollar figure. That confirms the Toman interpretation, per-ounce
scope, and the pivot arithmetic all at once.

To sanity-check your own key end to end:

```bash
curl -s "http://api.navasan.tech/latest/?api_key=$NAVASAN_API_KEY" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print({k: d.get(k,{}).get("value") for k in ("usd_sell","xau","xag")})'
```

A currency whose `value` is empty is silently dropped from the feed. To get a
loud warning instead, pin the symbol:

```bash
NAVASAN_SYMBOL_OVERRIDES="XAU:xau,XAG:xag"
```

Pinned currencies that produce no rate are logged at `WARN` as
`pinned currencies are missing from the navasan feed`.

## Crypto

Navasan also publishes crypto priced in Toman under bare codes — `btc`, `eth`,
`sol`, `usdt`, `xrp`, and 33 more. They are excluded from the default allowlist
because they are not ISO 4217 currencies, but they carry real values and work
fine as ledger currencies once pinned:

```bash
NAVASAN_SYMBOL_OVERRIDES="BTC:btc,ETH:eth,SOL:sol,USDT:usdt"
```

Note the `usd_*` variants (`usd_btc`, `usd_eth`, …) are priced in USD rather
than Toman; do not mix the two forms.

## Quick start

```bash
export NAVASAN_API_KEY="your-key"

go run ./cmd/navasan-translator
curl -s localhost:8088/latest.json | python3 -m json.tool
```

Then point the server at it:

```bash
export EXCHANGE_RATES_URL="http://navasan-translator:8088/latest.json"
```

Keep `CURRENCY_CONFIG_BASE_CURRENCY=USD` (the default) unless you have a reason
to change it.

## Configuration

| Variable | Default | Description |
| --- | --- | --- |
| `PORT` | `8088` | HTTP listen port |
| `NAVASAN_API_KEY` | — | **Required.** Key from navasan.net (email on their site to subscribe) |
| `NAVASAN_API_URL` | `http://api.navasan.tech/latest/` | Upstream endpoint. `api_key` is appended automatically |
| `NAVASAN_PIVOT_SYMBOL` | `usd_sell` | Default Navasan symbol for the pivot currency |
| `NAVASAN_PIVOT_CURRENCY` | `USD` | ISO code of the pivot, and the `"b"` value in the payload |
| `NAVASAN_LOCAL_CURRENCIES` | `IRT:1,IRR:10` | Derived currencies as `ISO:multiplier`, where the multiplier is units per one unit of the feed's pricing unit (Toman). `none` disables them |
| `NAVASAN_CURRENCIES` | *(built-in allowlist)* | CSV of ISO codes to expose. Replaces the built-in allowlist |
| `NAVASAN_SYMBOL_OVERRIDES` | *(none)* | CSV of `ISO:symbol` pins, applied last. Also adds currencies outside the allowlist |
| `NAVASAN_CACHE_TTL` | `5m` | How long a translated payload is reused |
| `NAVASAN_HTTP_TIMEOUT` | `30s` | Upstream request timeout |

### The default currency set

The allowlist is Navasan's own published symbol list intersected with current
ISO 4217, so every plain lowercase code it serves is picked up automatically:
`EUR` reads `eur`, `XAU` reads `xau`, and so on. All 158 currencies resolve
against the live feed. Navasan symbols that are *not* currencies are excluded —
`BTC` and the other crypto codes, `NIM` (نیم سکه), `ROB` (ربع سکه), the market
variants `harat_*`, `dolar_*`, `*_hav`, `mex_*`, `mob_*`, and the gold and coin
bubbles (`bub_*`). Retired codes Navasan still lists (`BYR`, `LTL`, `LVL`,
`STD`, `ZMK`, `CUC`, `HRK`, `SLL`) and non-ISO territory codes it also serves
(`GGP`, `JEP`, `IMP`) are excluded too.

Pin any of them with `NAVASAN_SYMBOL_OVERRIDES` if you actually want them:

```bash
NAVASAN_SYMBOL_OVERRIDES="CNH:cnh,AED:aed_sell,EUR:mex_eur_sell"
```

To expose only a few currencies, set `NAVASAN_CURRENCIES=USD,EUR,AED,IRT`.

## Endpoints

| Path | Behaviour |
| --- | --- |
| `GET /latest.json` | The translated feed (also served on `/`) |
| `GET /healthz` | `200 ok` — does not call Navasan |

## Operational notes

- **Cache and staleness.** The translated feed is cached for `NAVASAN_CACHE_TTL`.
  If Navasan fails after a successful fetch, the last good payload is served and
  a warning is logged, so a Navasan outage never aborts the go-money sync. With
  no cached payload the request returns `502`.
- **Scheduling.** go-money polls at 12:10 UTC (`jobs.go:33`). With the default
  5m TTL that request always triggers a fresh Navasan call.
- **`UpdatedAt`.** The `"u"` field mirrors the existing Lambda behaviour and is
  simply the time the payload was produced. go-money does not use it for writes.
- **Which rates get overwritten.** `pkg/currency/sync.go:137` only updates rows
  with `rate_mode = 'automatic'`. Rates you edited by hand stay `manual` and are
  never clobbered. New currencies arrive with `is_active = false` and must be
  activated in the UI.

## Security

Navasan's documented endpoint is **plain HTTP**, so the API key travels
unencrypted. Run this sidecar and the go-money server on a private network (a
Docker network, an internal VPC) and do not expose port 8088 publicly. Asking
Navasan whether an HTTPS endpoint is available is worthwhile.

## Docker

```bash
make build-docker-navasan
```

Example compose service, next to `app` on the same network:

```yaml
  navasan-translator:
    image: go-money-navasan-translator:latest
    hostname: navasan-translator
    restart: always
    environment:
      NAVASAN_API_KEY: ${NAVASAN_API_KEY}
      NAVASAN_PIVOT_SYMBOL: usd_sell
    networks:
      - go-money
```

and in the app's environment:

```bash
EXCHANGE_RATES_URL=http://navasan-translator:8088/latest.json
```
