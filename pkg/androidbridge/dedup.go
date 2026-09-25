package androidbridge

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

// DedupEntry is one row of the bridge fingerprint registry (data-model.md §7).
type DedupEntry struct {
	Fingerprint   string // PK, exact sha256
	BucketKey     string // bank|account|type|amount|2min-bucket (indexed)
	Bank          string
	AccountHint   string
	Type          string
	Amount        int64
	Desc          string // normalized description (lowercase, whitespace-collapsed)
	TxUnix        int64  // transaction time (unix seconds) for the ±2 min window check
	GomoneyTxnId  string
	RecordedAt    string
	MemoBaseTitle string
}

// BucketKey computes the ±2-min epoch bucket key per data-model.md §Fingerprint.
// The bucket itself is coarse: dedup window matching always scans the
// adjacent buckets (b-1, b, b+1) and then verifies |Δt| <= 2 min against the
// stored transaction time, so a boundary crossing never produces a false
// negative (analyze note: floor(unix/120) fails at bucket boundaries).
func BucketKey(bank, accountHint, typ string, amount int64, txAt time.Time) string {
	return fmt.Sprintf("%s|%s|%s|%d|%d", bank, accountHint, typ, amount, txAt.Unix()/120)
}

// FingerprintRegistry is the SQLite-backed dedup store.
type FingerprintRegistry struct {
	db *sql.DB

	// deliverMu serializes the lookup→reserve→commit sequence so concurrent
	// duplicate deliveries collapse to exactly one Go Money transaction.
	deliverMu sync.Mutex
}

// OpenDedupStore opens (or creates) the dedup SQLite database at path.
func OpenDedupStore(path string) (*FingerprintRegistry, error) {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("create dedup db dir: %w", err)
		}
	}

	db, err := sql.Open("sqlite3", path+"?_busy_timeout=5000&_journal_mode=WAL")
	if err != nil {
		return nil, fmt.Errorf("open dedup db: %w", err)
	}

	schema := `
CREATE TABLE IF NOT EXISTS fingerprint_registry (
    fingerprint   TEXT PRIMARY KEY,
    bucket_key    TEXT NOT NULL,
    bank          TEXT NOT NULL,
    account_hint  TEXT NOT NULL,
    type          TEXT NOT NULL,
    amount        INTEGER NOT NULL,
    "desc"        TEXT NOT NULL,
    tx_unix       INTEGER NOT NULL,
    gomoney_txn_id TEXT NOT NULL DEFAULT '',
    recorded_at   TEXT NOT NULL,
    memo_base_title TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_fingerprint_registry_bucket ON fingerprint_registry(bucket_key);
`
	if _, err = db.Exec(schema); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("init dedup schema: %w", err)
	}
	if err = ensureColumn(db, "memo_base_title", `ALTER TABLE fingerprint_registry ADD COLUMN memo_base_title TEXT NOT NULL DEFAULT ''`); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("migrate dedup schema: %w", err)
	}
	if _, err = db.Exec(`CREATE INDEX IF NOT EXISTS idx_fingerprint_registry_gomoney_txn_id ON fingerprint_registry(gomoney_txn_id)`); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("index Go Money transaction id: %w", err)
	}

	return &FingerprintRegistry{db: db}, nil
}

func ensureColumn(db *sql.DB, name, alter string) error {
	rows, err := db.Query(`PRAGMA table_info(fingerprint_registry)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid, notnull, pk int
		var column, dataType string
		var defaultValue any
		if err = rows.Scan(&cid, &column, &dataType, &notnull, &defaultValue, &pk); err != nil {
			return err
		}
		if strings.EqualFold(column, name) {
			return rows.Err()
		}
	}
	if err = rows.Err(); err != nil {
		return err
	}
	if err = rows.Close(); err != nil {
		return err
	}
	_, err = db.Exec(alter)
	return err
}

// Close closes the underlying database.
func (r *FingerprintRegistry) Close() error {
	return r.db.Close()
}

// DeliverLock exposes the registry's delivery mutex so handlers can serialize
// the whole dedup-check → Go Money create → record flow.
func (r *FingerprintRegistry) DeliverLock() *sync.Mutex {
	return &r.deliverMu
}

func scanEntry(row scanner) (*DedupEntry, error) {
	var e DedupEntry
	err := row.Scan(
		&e.Fingerprint,
		&e.BucketKey,
		&e.Bank,
		&e.AccountHint,
		&e.Type,
		&e.Amount,
		&e.Desc,
		&e.TxUnix,
		&e.GomoneyTxnId,
		&e.RecordedAt,
		&e.MemoBaseTitle,
	)
	if err != nil {
		return nil, err
	}
	return &e, nil
}

// scanner abstracts *sql.Row / *sql.Rows for shared row scanning.
type scanner interface {
	Scan(dest ...any) error
}

// LookupExact returns the entry with the exact fingerprint, or nil.
func (r *FingerprintRegistry) LookupExact(ctx context.Context, fingerprint string) (*DedupEntry, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT fingerprint, bucket_key, bank, account_hint, type, amount, "desc", tx_unix, gomoney_txn_id, recorded_at, memo_base_title
		 FROM fingerprint_registry WHERE fingerprint = ?`, fingerprint)
	e, err := scanEntry(row)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return e, err
}

// LookupBucketWindow implements the ±2-minute cross-source match (Q3=B):
// adjacent-bucket scan + exact description equality + |Δt| <= 120 s.
// Returns the matched entry, or nil when nothing matches.
func (r *FingerprintRegistry) LookupBucketWindow(
	ctx context.Context,
	bank, accountHint, typ, desc string,
	amount int64,
	txUnix int64,
) (*DedupEntry, error) {
	b := txUnix / 120
	adjacentKey := func(bucket int64) string {
		return BucketKey(bank, accountHint, typ, amount, time.Unix(bucket*120, 0))
	}
	rows, err := r.db.QueryContext(ctx, `
SELECT fingerprint, bucket_key, bank, account_hint, type, amount, "desc", tx_unix, gomoney_txn_id, recorded_at, memo_base_title
FROM fingerprint_registry
WHERE bucket_key IN (?, ?, ?)
  AND bank = ? AND account_hint = ? AND type = ? AND amount = ? AND "desc" = ?`,
		adjacentKey(b-1), adjacentKey(b), adjacentKey(b+1), bank, accountHint, typ, amount, desc)
	if err != nil {
		return nil, fmt.Errorf("bucket window scan: %w", err)
	}
	defer rows.Close()

	var best *DedupEntry
	for rows.Next() {
		e, scanErr := scanEntry(rows)
		if scanErr != nil {
			return nil, scanErr
		}
		// The bucket pre-filter is coarse (±1 bucket ≈ up to ~4 min span);
		// enforce the exact ±2 min tolerance here.
		if abs64(int64(e.TxUnix)-txUnix) <= 120 {
			if best == nil || e.TxUnix < best.TxUnix {
				best = e
			}
		}
	}
	return best, rows.Err()
}

// Reserve inserts a placeholder row for a fingerprint before the Go Money call
// so concurrent duplicates cannot both proceed. The row is rolled back by
// Delete if Go Money fails.
func (r *FingerprintRegistry) Reserve(ctx context.Context, e *DedupEntry) error {
	_, err := r.db.ExecContext(ctx, `
INSERT INTO fingerprint_registry (fingerprint, bucket_key, bank, account_hint, type, amount, "desc", tx_unix, gomoney_txn_id, recorded_at, memo_base_title)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		e.Fingerprint,
		e.BucketKey,
		e.Bank,
		e.AccountHint,
		e.Type,
		e.Amount,
		e.Desc,
		e.TxUnix,
		e.GomoneyTxnId,
		e.RecordedAt,
		e.MemoBaseTitle,
	)
	return err
}

// LookupGoMoneyTxnID returns the registry row associated with a recorded Go Money id.
func (r *FingerprintRegistry) LookupGoMoneyTxnID(ctx context.Context, gomoneyTxnID string) (*DedupEntry, error) {
	if strings.TrimSpace(gomoneyTxnID) == "" {
		return nil, nil
	}
	row := r.db.QueryRowContext(ctx,
		`SELECT fingerprint, bucket_key, bank, account_hint, type, amount, "desc", tx_unix, gomoney_txn_id, recorded_at, memo_base_title
		 FROM fingerprint_registry WHERE gomoney_txn_id = ? LIMIT 1`, gomoneyTxnID)
	e, err := scanEntry(row)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return e, err
}

// SaveMemoBaseTitle persists the unannotated Go Money title for safe memo replacement/removal.
func (r *FingerprintRegistry) SaveMemoBaseTitle(ctx context.Context, fingerprint, title string) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE fingerprint_registry SET memo_base_title = ? WHERE fingerprint = ?`,
		title,
		fingerprint,
	)
	return err
}

// Commit updates the reserved row with the Go Money transaction id.
func (r *FingerprintRegistry) Commit(ctx context.Context, fingerprint, gomoneyTxnId string) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE fingerprint_registry SET gomoney_txn_id = ? WHERE fingerprint = ?`,
		gomoneyTxnId, fingerprint)
	return err
}

// Delete rolls back a reserved row (Go Money failure path).
func (r *FingerprintRegistry) Delete(ctx context.Context, fingerprint string) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM fingerprint_registry WHERE fingerprint = ?`, fingerprint)
	return err
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

// MappingStore persists accountHint → Go Money account id resolution
// (contracts/gomoney-integration.md). File format: {"mellat|****1234": 1}.
type MappingStore struct {
	path string

	mu     sync.RWMutex
	maps   map[string]int32
	tokens map[string]struct{} // (bank, hint) keys, for validation message parity
}

// NewMappingStore loads the mappings from the given JSON file.
func NewMappingStore(path string) (*MappingStore, error) {
	m := &MappingStore{path: path}
	if err := m.reload(); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *MappingStore) reload() error {
	data, err := os.ReadFile(m.path)
	if err != nil {
		if os.IsNotExist(err) {
			m.maps = map[string]int32{}
			m.tokens = map[string]struct{}{}
			return nil
		}
		return fmt.Errorf("read mappings file: %w", err)
	}

	maps := map[string]int32{}
	if len(data) > 0 {
		if err = json.Unmarshal(data, &maps); err != nil {
			return fmt.Errorf("parse mappings file: %w", err)
		}
	}

	m.mu.Lock()
	m.maps = maps
	m.mu.Unlock()
	return nil
}

// Resolve maps (bank, accountHint) to a Go Money source account id.
func (m *MappingStore) Resolve(bank, accountHint string) (int32, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	id, ok := m.maps[bank+"|"+accountHint]
	return id, ok
}

// All returns a copy of the current mappings.
func (m *MappingStore) All() map[string]int32 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make(map[string]int32, len(m.maps))
	for k, v := range m.maps {
		out[k] = v
	}
	return out
}

// Save atomically replaces all mappings (write temp file + rename).
func (m *MappingStore) Save(mappings map[string]int32) error {
	data, err := json.MarshalIndent(mappings, "", "  ")
	if err != nil {
		return fmt.Errorf("encode mappings: %w", err)
	}

	if dir := filepath.Dir(m.path); dir != "" && dir != "." {
		if err = os.MkdirAll(dir, 0o755); err != nil {
			return fmt.Errorf("create mappings dir: %w", err)
		}
	}

	tmp := m.path + ".tmp"
	if err = os.WriteFile(tmp, data, 0o644); err != nil {
		return fmt.Errorf("write mappings: %w", err)
	}
	if err = os.Rename(tmp, m.path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename mappings: %w", err)
	}

	return m.reload()
}
