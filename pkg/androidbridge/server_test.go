package androidbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
	"connectrpc.com/connect"
	"github.com/stretchr/testify/require"
)

// fakeGoMoneyClient is a hand-written fake of the GoMoneyClient interface
// (injection point per FR-013).
type fakeGoMoneyClient struct {
	createErr   error
	pingErr     error
	created     []*transactionsv1.CreateTransactionRequest
	nextTxnId   int64
	failConnect bool // return a connect Unavailable error
}

func (f *fakeGoMoneyClient) CreateTransaction(
	ctx context.Context,
	req *transactionsv1.CreateTransactionRequest,
) (*transactionsv1.CreateTransactionResponse, error) {
	if f.createErr != nil {
		return nil, f.createErr
	}
	f.created = append(f.created, req)
	f.nextTxnId++
	return &transactionsv1.CreateTransactionResponse{
		Transaction: &gomoneypbv1.Transaction{Id: f.nextTxnId},
	}, nil
}

func (f *fakeGoMoneyClient) Ping(ctx context.Context) error {
	return f.pingErr
}

// newTestServer spins up an httptest server with the full bridge surface.
func newTestServer(t *testing.T, client GoMoneyClient) (*Server, *httptest.Server) {
	t.Helper()
	reg := newTestRegistry(t)

	mappingsFile := t.TempDir() + "/mappings.json"
	mappings, err := NewMappingStore(mappingsFile)
	require.NoError(t, err)
	require.NoError(t, mappings.Save(map[string]int32{"mellat|****1234": 1}))

	srv := NewServer("test-token", client, reg, mappings)
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return srv, ts
}

func bridgePost(t *testing.T, url, token string, body []byte) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	require.NoError(t, err)
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	t.Cleanup(func() { _ = res.Body.Close() })
	return res
}

func validTxn(fingerprint string) *NormalizedTransaction {
	return &NormalizedTransaction{
		Id:            "11111111-1111-1111-1111-111111111111",
		SourceEventId: "22222222-2222-2222-2222-222222222222",
		Source:        SourceNotification,
		Bank:          "mellat",
		AccountHint:   "****1234",
		Type:          TypeExpense,
		Amount:        500000,
		Currency:      "IRR",
		TxAt:          "2026-09-24T16:00:00+03:30",
		Description:   "Card purchase",
		Fingerprint:   fingerprint,
	}
}

func fingerprintHex(i byte) string {
	return fmt.Sprintf("%064x", i)
}

func TestPing(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/ping", "test-token", []byte("{}"))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.Contains(t, readBody(t, res), `"ok":true`)

	// Go Money unreachable → ok:false, still 200.
	client.pingErr = fmt.Errorf("down")
	res2 := bridgePost(t, ts.URL+"/v1/ping", "test-token", []byte("{}"))
	require.Equal(t, http.StatusOK, res2.StatusCode)
}

func TestAuthUnauthorized(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/ping", "wrong-token", []byte("{}"))
	require.Equal(t, http.StatusUnauthorized, res.StatusCode)

	// Same token but not via Bearer scheme.
	req, err := http.NewRequest(http.MethodPost, ts.URL+"/v1/ping", nil)
	require.NoError(t, err)
	req.Header.Set("Authorization", "Basic test-token")
	res2, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	defer func() { _ = res2.Body.Close() }()
	require.Equal(t, http.StatusUnauthorized, res2.StatusCode)
}

func TestCreateCreated(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	body := marshal(t, validTxn(fingerprintHex(1)))
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", body)
	require.Equal(t, http.StatusCreated, res.StatusCode)
	require.JSONEq(t, `{"status":"created","gomoneyTxnId":"1"}`, readBody(t, res))
	require.Len(t, client.created, 1)

	// Mapped to Go Money correctly.
	created := client.created[0]
	require.Equal(t, "Card purchase [mellat/****1234]", created.Title)
	require.NotNil(t, created.GetExpense())
	require.Equal(t, "500000", created.GetExpense().SourceAmount)
	require.Equal(t, "IRR", created.GetExpense().SourceCurrency)
}

func TestCreateDuplicateExactFingerprint(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	txn := validTxn(fingerprintHex(2))
	require.Equal(t, http.StatusCreated, bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn)).StatusCode)
	require.Equal(t, http.StatusOK, bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn)).StatusCode)
	require.Len(t, client.created, 1)
}

func TestCreateDuplicateBucketWindow(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	// Same real transaction delivered twice with different derived timestamps
	// (notification vs SMS ±2 min) and different fingerprints.
	txnA := validTxn(fingerprintHex(3))
	txnA.TxAt = "2026-09-24T16:00:00+03:30"
	txnA.Description = "Card purchase"

	txnB := validTxn(fingerprintHex(4))
	txnB.Source = SourceSMS
	txnB.TxAt = "2026-09-24T16:01:30+03:30"

	require.Equal(t, http.StatusCreated, bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txnA)).StatusCode)
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txnB))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.JSONEq(t, `{"status":"duplicate","gomoneyTxnId":"1"}`, readBody(t, res))
	require.Len(t, client.created, 1)
}

func TestCreateValidation(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	cases := []func(*NormalizedTransaction){
		func(x *NormalizedTransaction) { x.Amount = 0 },
		func(x *NormalizedTransaction) { x.Currency = "USD" },
		func(x *NormalizedTransaction) { x.Type = "transfer" },
		func(x *NormalizedTransaction) { x.Source = "email" },
		func(x *NormalizedTransaction) { x.Fingerprint = "short" },
		func(x *NormalizedTransaction) { x.TxAt = "not-a-date" },
	}

	for i, mutate := range cases {
		t.Run(fmt.Sprintf("case_%d", i), func(t *testing.T) {
			txn := validTxn(fingerprintHex(byte(10 + i)))
			mutate(txn)
			res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
			require.Equal(t, http.StatusBadRequest, res.StatusCode)
			require.Contains(t, readBody(t, res), `"validation"`)
		})
	}
	require.Empty(t, client.created)
}

func TestCreateUnmappedAccount(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	txn := validTxn(fingerprintHex(20))
	txn.Bank = "saman"
	txn.AccountHint = "****9999"

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusBadRequest, res.StatusCode)
	require.Contains(t, readBody(t, res), "unmapped account: saman/****9999")
	require.Empty(t, client.created)
}

func TestCreateGomoneyUnreachable(t *testing.T) {
	client := &fakeGoMoneyClient{createErr: connect.NewError(connect.CodeUnavailable, fmt.Errorf("down"))}
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, validTxn(fingerprintHex(30))))
	require.Equal(t, http.StatusBadGateway, res.StatusCode)
	require.Contains(t, readBody(t, res), "gomoney_unreachable")

	// Fingerprint row rolled back → a retry can succeed.
	client.createErr = nil
	res2 := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, validTxn(fingerprintHex(30))))
	require.Equal(t, http.StatusCreated, res2.StatusCode)
}

func TestCreateGomoneyError(t *testing.T) {
	client := &fakeGoMoneyClient{createErr: connect.NewError(connect.CodeInternal, fmt.Errorf("boom"))}
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, validTxn(fingerprintHex(31))))
	require.Equal(t, http.StatusInternalServerError, res.StatusCode)
	require.Contains(t, readBody(t, res), "gomoney_error")
}

func TestBulkPerItemResults(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	a := validTxn(fingerprintHex(40))
	b := validTxn(fingerprintHex(41))
	b.Description = "Coffee shop" // distinct real transaction, same bucket
	c := validTxn(fingerprintHex(42))
	c.Currency = "USD" // per-item validation failure

	res := bridgePost(t, ts.URL+"/v1/transactions/bulk", "test-token",
		marshal(t, &BulkTransactionRequest{Transactions: []*NormalizedTransaction{a, b, b, c}}))
	require.Equal(t, http.StatusOK, res.StatusCode)

	body := readBody(t, res)
	require.Contains(t, body, `"status":"created"`)
	require.Contains(t, body, `"status":"duplicate"`)
	require.Contains(t, body, `"error":"validation"`)
	require.Len(t, client.created, 2)
}

func TestBulkOverLimit(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	items := make([]*NormalizedTransaction, 51)
	for i := range items {
		items[i] = validTxn(fingerprintHex(byte(50 + i)))
	}
	res := bridgePost(t, ts.URL+"/v1/transactions/bulk", "test-token",
		marshal(t, &BulkTransactionRequest{Transactions: items}))
	require.Equal(t, http.StatusBadRequest, res.StatusCode)
}

func TestMappingsEndpoints(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	get, err := http.NewRequest(http.MethodGet, ts.URL+"/v1/mappings", nil)
	require.NoError(t, err)
	get.Header.Set("Authorization", "Bearer test-token")
	res, err := http.DefaultClient.Do(get)
	require.NoError(t, err)
	defer func() { _ = res.Body.Close() }()
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.JSONEq(t, `{"mellat|****1234":1}`, readBody(t, res))

	put, err := http.NewRequest(http.MethodPut, ts.URL+"/v1/mappings",
		bytes.NewReader([]byte(`{"mellat|****1234": 1, "saman|****9876": 2}`)))
	require.NoError(t, err)
	put.Header.Set("Authorization", "Bearer test-token")
	res2, err := http.DefaultClient.Do(put)
	require.NoError(t, err)
	defer func() { _ = res2.Body.Close() }()
	require.Equal(t, http.StatusOK, res2.StatusCode)

	// New mapping takes effect for delivery.
	txn := validTxn(fingerprintHex(60))
	txn.Bank = "saman"
	txn.AccountHint = "****9876"
	res3 := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusCreated, res3.StatusCode)
}

// The timestamp used across tests must stay stable within ±2 min windows.
func TestFixtureClockSanity(t *testing.T) {
	parsed, err := time.Parse(time.RFC3339, "2026-09-24T16:00:00+03:30")
	require.NoError(t, err)
	require.Equal(t, "2026-09-24T16:00:00+03:30", parsed.Format(time.RFC3339))
}

func TestLoggingRedactsSensitiveFields(t *testing.T) {
	// Fingerprint prefix helper: 8 hex max, never the whole payload.
	require.Equal(t, "0f0f0f0f", FingerprintPrefix("0f0f0f0f"+strings.Repeat("a", 56)))
	require.Equal(t, "ab", FingerprintPrefix("ab"))
}

func marshal(t *testing.T, v any) []byte {
	t.Helper()
	data, err := json.Marshal(v)
	require.NoError(t, err)
	return data
}

func readBody(t *testing.T, res *http.Response) string {
	t.Helper()
	data, err := io.ReadAll(res.Body)
	require.NoError(t, err)
	return string(data)
}
