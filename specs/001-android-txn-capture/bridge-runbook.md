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

Copy the example config, add the service and app bearer tokens, and restrict
the local file permissions:

```bash
cp .env.android-bridge.example .env.android-bridge
${EDITOR:-vi} .env.android-bridge
chmod 600 .env.android-bridge
make android-bridge-run
```

The Make target reads `BRIDGE_LISTEN`, `GOMONEY_URL`,
`GOMONEY_SERVICE_TOKEN`, `BRIDGE_TOKEN`, `BRIDGE_DEDUP_DB`, and
`BRIDGE_MAPPINGS` from `.env.android-bridge` before starting the bridge. The
file uses shell-style `NAME=value` entries as shown in the template, and the
local file is git-ignored. To use a different file, pass
`BRIDGE_ENV_FILE=/path/to/file` to make. Keep tokens in the file rather than
command-line flags, which may be visible in shell history or process listings.

Generate a token for the app: `openssl rand -hex 32`.

## 3. Mappings file

`mappings.json` maps `(bank, account hint)` → Go Money bank-side account ID
(source for expenses, destination for income):

```json
{ "mellat|****1234": 1, "saman|****9876": 2 }
```

Manage it live without restarting the bridge:

- `GET /v1/mappings` — current mappings
- `PUT /v1/mappings` — atomic replace (write temp + rename)

Unmapped account → the bridge answers `400 validation: unmapped account`
WITHOUT calling Go Money; add the mapping and retry manually.

The mapped bank account's currency must match the captured transaction currency
(currently IRR). A default expense/income account may use another currency; the
bridge converts that counterpart using the active Go Money currency rates and
target precision at delivery time. For an IRR bank account with a USD default
expense, make sure both IRR and USD have valid configured rates in Go Money.

## 4. Network access (LAN or Tailscale)

- The Android app uses the configured bridge URL and bearer token (stored
  encrypted on the phone). Plain HTTP is intended for a trusted private network;
  do **not** expose the bridge to the public internet.

### Tailscale

Both the PC running the bridge and the Android phone must be connected to the
same Tailscale network. For this PC (`100.110.180.85`), set this in
`.env.android-bridge`:

```dotenv
BRIDGE_LISTEN=100.110.180.85:8788
```

This binds the bridge only to the PC's Tailscale address instead of all network
interfaces. Restart the bridge with `make android-bridge-run`; allow inbound TCP
port `8788` for Tailscale in the PC firewall if needed. The bridge's default
`GOMONEY_URL=http://127.0.0.1:8080` can remain unchanged when Go Money runs on
the same PC.

In Android Settings, tap **Use PC via Tailscale (100.110.180.85:8788)**; the URL is
`http://100.110.180.85:8788`. Keep the existing bridge bearer token. USB is only
needed for ADB/install/debugging; sync traffic goes over Tailscale.

Tailscale encrypts traffic between tailnet devices. Keep the bearer token
configured and do not create a public router port-forward for the bridge.

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
