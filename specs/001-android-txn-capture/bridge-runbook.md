# Bridge Setup & Run Book (US2)

`cmd/android-bridge` is a small HTTP service that the Android capture app
talks to on the trusted LAN. It validates payloads, resolves account hints,
enforces final deduplication (±2 min window), and records transactions in
Go Money.

## 1. Create a Go Money service token

Never store bank/user credentials (FR-006). Create a **service token** once
via Go Money:

```bash
curl -X POST https://<gomoney-host>/gomoneypb.configuration.v1.ConfigurationService/CreateServiceToken \
  -H 'Authorization: Bearer <your-session-token>' -d '{}'
```

## 2. Run the bridge

```bash
go run ./cmd/android-bridge \
  --listen :8787 \
  --gomoney-url http://127.0.0.1:8080 \
  --gomoney-token <GOMONEY_SERVICE_TOKEN> \
  --bearer-token <RANDOM_STATIC_TOKEN_FOR_THE_APP> \
  --dedup-db ~/.gomoney-android-bridge/dedup.db \
  --mappings-file ~/.gomoney-android-bridge/mappings.json
```

Equivalent env vars: `BRIDGE_LISTEN`, `GOMONEY_URL`, `GOMONEY_SERVICE_TOKEN`,
`BRIDGE_TOKEN`, `BRIDGE_DEDUP_DB`, `BRIDGE_MAPPINGS`.

Generate a token for the app: `openssl rand -hex 32`.

## 3. Mappings file

`mappings.json` maps `(bank, masked card hint)` → Go Money `source_account_id`:

```json
{ "mellat|****1234": 1, "saman|****9876": 2 }
```

Manage it live without restarting the bridge:

- `GET /v1/mappings` — current mappings
- `PUT /v1/mappings` — atomic replace (write temp + rename)

Unmapped account → the bridge answers `400 validation: unmapped account`
WITHOUT calling Go Money; add the mapping and retry manually.

## 4. LAN notes

- Plain HTTP by design (clarified Q1=B — trusted home LAN); do **not** expose
  the bridge to the internet. If your firewall blocks inbound :8787, allow it
  on the local interface only.
- The Android app pins the base URL (e.g. `http://192.168.1.10:8787`) + the
  bearer token (stored encrypted on the phone).

## 5. Endpoints

| Endpoint | Purpose |
|---|---|
| `POST /v1/ping` | connection test; reports Go Money reachability |
| `POST /v1/transactions` | deliver one normalized transaction (201 created / 200 duplicate / 400 validation / 502 unreachable / 500 gomoney_error) |
| `POST /v1/transactions/bulk` | drain optimization, max 50 items, per-item results |
| `GET/PUT /v1/mappings` | account-hint mapping management |

All endpoints require `Authorization: Bearer <token>` (constant-time compare).

## 6. Logging rules (FR-028)

Logs contain ONLY: timestamp, endpoint, fingerprint prefix (8 hex), outcome,
latency. Never full payloads, descriptions or account hints.
