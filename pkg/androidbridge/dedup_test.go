package androidbridge

import (
	"context"
	"database/sql"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// newTestRegistry opens a temp-file backed registry (per T010: temp file db).
func newTestRegistry(t *testing.T) *FingerprintRegistry {
	t.Helper()
	reg, err := OpenDedupStore(filepath.Join(t.TempDir(), "dedup.db"))
	require.NoError(t, err)
	t.Cleanup(func() { _ = reg.Close() })
	return reg
}

func sampleEntry(fingerprint string, txAt time.Time) *DedupEntry {
	return &DedupEntry{
		Fingerprint: fingerprint,
		BucketKey:   BucketKey("mellat", "****1234", TypeExpense, 500000, txAt),
		Bank:        "mellat",
		AccountHint: "****1234",
		Type:        TypeExpense,
		Amount:      500000,
		Desc:        "card purchase",
		TxUnix:      txAt.Unix(),
		RecordedAt:  time.Now().Format(time.RFC3339),
	}
}

func TestDedupExactHit(t *testing.T) {
	reg := newTestRegistry(t)
	ctx := context.Background()
	txAt := time.Date(2026, 9, 24, 12, 30, 0, 0, time.UTC)

	require.NoError(t, reg.Reserve(ctx, sampleEntry("fp-exact", txAt)))

	hit, err := reg.LookupExact(ctx, "fp-exact")
	require.NoError(t, err)
	require.NotNil(t, hit)
	require.Equal(t, "mellat", hit.Bank)

	miss, err := reg.LookupExact(ctx, "fp-other")
	require.NoError(t, err)
	require.Nil(t, miss)
}

func TestDedupBucketHitWithinTwoMinutes(t *testing.T) {
	reg := newTestRegistry(t)
	ctx := context.Background()

	// Notification at 12:30:00, SMS of the same transaction at 12:31:30
	// (90 s apart → duplicate per ±2 min tolerance).
	first := sampleEntry("fp-a", time.Date(2026, 9, 24, 12, 30, 0, 0, time.UTC))
	second := sampleEntry("fp-b", time.Date(2026, 9, 24, 12, 31, 30, 0, time.UTC))

	require.NoError(t, reg.Reserve(ctx, first))

	hit, err := reg.LookupBucketWindow(ctx, second.Bank, second.AccountHint, second.Type, second.Desc, second.Amount, second.TxUnix)
	require.NoError(t, err)
	require.NotNil(t, hit)
	require.Equal(t, "fp-a", hit.Fingerprint)
}

func TestDedupBucketBoundaryAdjacentBuckets(t *testing.T) {
	reg := newTestRegistry(t)
	ctx := context.Background()

	// Boundary case (analyze HIGH): first txn at 12:31:59 (bucket 371),
	// second at 12:32:01 (bucket 372, different bucket) but only 2 s apart —
	// must still match via the adjacent-bucket scan.
	first := sampleEntry("fp-boundary", time.Date(2026, 9, 24, 12, 31, 59, 0, time.UTC))
	second := sampleEntry("fp-boundary2", time.Date(2026, 9, 24, 12, 32, 1, 0, time.UTC))
	require.NotEqual(t, first.BucketKey, second.BucketKey)

	require.NoError(t, reg.Reserve(ctx, first))
	hit, err := reg.LookupBucketWindow(ctx, second.Bank, second.AccountHint, second.Type, second.Desc, second.Amount, second.TxUnix)
	require.NoError(t, err)
	require.NotNil(t, hit)
}

func TestDedupMissBeyondTwoMinutes(t *testing.T) {
	reg := newTestRegistry(t)
	ctx := context.Background()

	first := sampleEntry("fp-old", time.Date(2026, 9, 24, 12, 30, 0, 0, time.UTC))
	second := sampleEntry("fp-new", time.Date(2026, 9, 24, 12, 32, 1, 0, time.UTC))

	require.NoError(t, reg.Reserve(ctx, first))
	hit, err := reg.LookupBucketWindow(ctx, second.Bank, second.AccountHint, second.Type, second.Desc, second.Amount, second.TxUnix)
	require.NoError(t, err)
	require.Nil(t, hit)
}

func TestDedupDistinctTransactionsSameAmountNotCollapsed(t *testing.T) {
	reg := newTestRegistry(t)
	ctx := context.Background()

	// Same bank/account/type/amount seconds apart, different descriptions
	// (spec edge case: must NOT collapse).
	first := sampleEntry("fp-d1", time.Date(2026, 9, 24, 12, 30, 0, 0, time.UTC))
	second := sampleEntry("fp-d2", time.Date(2026, 9, 24, 12, 30, 5, 0, time.UTC))
	second.Desc = "coffee shop"
	second.Fingerprint = "fp-d2"

	require.NoError(t, reg.Reserve(ctx, first))
	hit, err := reg.LookupBucketWindow(ctx, second.Bank, second.AccountHint, second.Type, second.Desc, second.Amount, second.TxUnix)
	require.NoError(t, err)
	require.Nil(t, hit)
}

func TestDedupCommitAndRollback(t *testing.T) {
	reg := newTestRegistry(t)
	ctx := context.Background()
	txAt := time.Date(2026, 9, 24, 12, 30, 0, 0, time.UTC)

	require.NoError(t, reg.Reserve(ctx, sampleEntry("fp-c", txAt)))
	require.NoError(t, reg.Commit(ctx, "fp-c", "123"))
	hit, err := reg.LookupExact(ctx, "fp-c")
	require.NoError(t, err)
	require.Equal(t, "123", hit.GomoneyTxnId)

	require.NoError(t, reg.Reserve(ctx, sampleEntry("fp-r", txAt)))
	require.NoError(t, reg.Delete(ctx, "fp-r"))
	rolled, err := reg.LookupExact(ctx, "fp-r")
	require.NoError(t, err)
	require.Nil(t, rolled)
}

func TestDedupPersistenceAcrossReopen(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "dedup.db")
	ctx := context.Background()
	txAt := time.Date(2026, 9, 24, 12, 30, 0, 0, time.UTC)

	reg, err := OpenDedupStore(path)
	require.NoError(t, err)
	require.NoError(t, reg.Reserve(ctx, sampleEntry("fp-persist", txAt)))
	require.NoError(t, reg.Commit(ctx, "fp-persist", "42"))
	require.NoError(t, reg.Close())

	reopened, err := OpenDedupStore(path)
	require.NoError(t, err)
	defer func() { _ = reopened.Close() }()

	hit, err := reopened.LookupExact(ctx, "fp-persist")
	require.NoError(t, err)
	require.NotNil(t, hit)
	require.Equal(t, "42", hit.GomoneyTxnId)
}

func TestOpenDedupStoreMigratesExistingRegistry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy-dedup.db")
	db, err := sql.Open("sqlite3", path)
	require.NoError(t, err)
	_, err = db.Exec(`CREATE TABLE fingerprint_registry (
		fingerprint TEXT PRIMARY KEY,
		bucket_key TEXT NOT NULL,
		bank TEXT NOT NULL,
		account_hint TEXT NOT NULL,
		type TEXT NOT NULL,
		amount INTEGER NOT NULL,
		"desc" TEXT NOT NULL,
		tx_unix INTEGER NOT NULL,
		gomoney_txn_id TEXT NOT NULL DEFAULT '',
		recorded_at TEXT NOT NULL
	)`)
	require.NoError(t, err)
	_, err = db.Exec(`INSERT INTO fingerprint_registry
		(fingerprint,bucket_key,bank,account_hint,type,amount,"desc",tx_unix,gomoney_txn_id,recorded_at)
		VALUES(?,?,?,?,?,?,?,?,?,?)`,
		"legacy-fingerprint", "bucket", "mellat", "****1234", TypeExpense, 500000, "coffee", 1, "7", time.Now().Format(time.RFC3339))
	require.NoError(t, err)
	require.NoError(t, db.Close())

	registry, err := OpenDedupStore(path)
	require.NoError(t, err)
	defer func() { _ = registry.Close() }()

	entry, err := registry.LookupExact(context.Background(), "legacy-fingerprint")
	require.NoError(t, err)
	require.NotNil(t, entry)
	require.Empty(t, entry.MemoBaseTitle)

	entry.MemoBaseTitle = "base title"
	require.NoError(t, registry.SaveMemoBaseTitle(context.Background(), entry.Fingerprint, entry.MemoBaseTitle))
	byID, err := registry.LookupGoMoneyTxnID(context.Background(), "7")
	require.NoError(t, err)
	require.NotNil(t, byID)
	require.Equal(t, entry.MemoBaseTitle, byID.MemoBaseTitle)
}

func TestBucketKeyFormat(t *testing.T) {
	txAt := time.Unix(1789000000, 0).UTC()
	key := BucketKey("mellat", "****1234", TypeExpense, 500000, txAt)
	require.Equal(t, fmt.Sprintf("mellat|****1234|expense|500000|%d", 1789000000/120), key)
}
