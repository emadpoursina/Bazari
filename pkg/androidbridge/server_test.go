package androidbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
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
	"google.golang.org/protobuf/types/known/timestamppb"
)

// fakeGoMoneyClient is a hand-written fake of the GoMoneyClient interface
// (injection point per FR-013).
type fakeGoMoneyClient struct {
	createErr     error
	pingErr       error
	accountsErr   error
	currenciesErr error
	accounts      []GoMoneyAccount
	currencies    []GoMoneyCurrency
	created       []*transactionsv1.CreateTransactionRequest
	updated       []*transactionsv1.UpdateTransactionRequest
	existing      map[int64]*gomoneypbv1.Transaction
	nextTxnId     int64
	failConnect   bool // return a connect Unavailable error
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
	if f.existing == nil {
		f.existing = make(map[int64]*gomoneypbv1.Transaction)
	}
	created := &gomoneypbv1.Transaction{
		Id:                       f.nextTxnId,
		Title:                    req.GetTitle(),
		TransactionDate:          req.GetTransactionDate(),
		TagIds:                   req.GetTagIds(),
		Notes:                    req.GetNotes(),
		Extra:                    req.GetExtra(),
		ReferenceNumber:          req.ReferenceNumber,
		InternalReferenceNumbers: req.GetInternalReferenceNumbers(),
		CategoryId:               req.CategoryId,
		GroupKey:                 req.GroupKey,
	}
	if expense := req.GetExpense(); expense != nil {
		created.Type = gomoneypbv1.TransactionType_TRANSACTION_TYPE_EXPENSE
		created.SourceAmount = expense.GetSourceAmount()
		created.SourceCurrency = expense.GetSourceCurrency()
		created.SourceAccountId = expense.GetSourceAccountId()
		created.FxSourceAmount = expense.FxSourceAmount
		created.FxSourceCurrency = expense.FxSourceCurrency
		created.DestinationAmount = expense.GetDestinationAmount()
		created.DestinationCurrency = expense.GetDestinationCurrency()
		created.DestinationAccountId = expense.GetDestinationAccountId()
	} else if income := req.GetIncome(); income != nil {
		created.Type = gomoneypbv1.TransactionType_TRANSACTION_TYPE_INCOME
		created.SourceAmount = income.GetSourceAmount()
		created.SourceCurrency = income.GetSourceCurrency()
		created.SourceAccountId = income.GetSourceAccountId()
		created.DestinationAmount = income.GetDestinationAmount()
		created.DestinationCurrency = income.GetDestinationCurrency()
		created.DestinationAccountId = income.GetDestinationAccountId()
	}
	f.existing[f.nextTxnId] = created
	return &transactionsv1.CreateTransactionResponse{
		Transaction: &gomoneypbv1.Transaction{Id: f.nextTxnId},
	}, nil
}

func (f *fakeGoMoneyClient) GetTransactionByID(ctx context.Context, id int64) (*gomoneypbv1.Transaction, error) {
	if txn, ok := f.existing[id]; ok {
		return txn, nil
	}
	return nil, errors.New("transaction not found")
}

func (f *fakeGoMoneyClient) UpdateTransaction(
	ctx context.Context,
	req *transactionsv1.UpdateTransactionRequest,
) (*transactionsv1.UpdateTransactionResponse, error) {
	f.updated = append(f.updated, req)
	if txn, ok := f.existing[req.GetId()]; ok {
		txn.Title = req.GetTransaction().GetTitle()
	}
	return &transactionsv1.UpdateTransactionResponse{
		Transaction: &gomoneypbv1.Transaction{Id: req.GetId(), Title: req.GetTransaction().GetTitle()},
	}, nil
}

func (f *fakeGoMoneyClient) Ping(ctx context.Context) error {
	return f.pingErr
}

func (f *fakeGoMoneyClient) ListAccounts(ctx context.Context) ([]GoMoneyAccount, error) {
	if f.accountsErr != nil {
		return nil, f.accountsErr
	}
	if f.accounts != nil {
		return f.accounts, nil
	}
	return []GoMoneyAccount{
		{ID: 1, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET, Currency: "IRR"},
		{ID: 2, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE, Currency: "IRR", IsDefault: true},
		{ID: 3, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_INCOME, Currency: "IRR", IsDefault: true},
	}, nil
}

func (f *fakeGoMoneyClient) ListCurrencies(ctx context.Context, ids []string) ([]GoMoneyCurrency, error) {
	if f.currenciesErr != nil {
		return nil, f.currenciesErr
	}
	if f.currencies != nil {
		return f.currencies, nil
	}
	return []GoMoneyCurrency{
		{ID: "IRR", Rate: "230000.0", DecimalPlaces: 1},
		{ID: "USD", Rate: "1.00", DecimalPlaces: 2},
	}, nil
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
	return bridgeRequest(t, http.MethodPost, url, token, body)
}

func bridgeRequest(t *testing.T, method, url, token string, body []byte) *http.Response {
	t.Helper()
	req, err := http.NewRequest(method, url, bytes.NewReader(body))
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
	require.Equal(t, "-500000", created.GetExpense().SourceAmount)
	require.Equal(t, "IRR", created.GetExpense().SourceCurrency)
	require.Equal(t, int32(1), created.GetExpense().SourceAccountId)
	require.Equal(t, "500000", created.GetExpense().DestinationAmount)
	require.Equal(t, "IRR", created.GetExpense().DestinationCurrency)
	require.Equal(t, int32(2), created.GetExpense().DestinationAccountId)
}

func TestCreateTransactionIncludesMemoInGoMoneyTitle(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)
	txn := validTxn(fingerprintHex(22))
	txn.Memo = "groceries"

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusCreated, res.StatusCode)
	require.Len(t, client.created, 1)
	require.Equal(t, "Card purchase [mellat/****1234] — groceries", client.created[0].Title)
}

func TestUpdateMemoChangesTitleWithoutCreatingAnotherTransaction(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)
	txn := validTxn(fingerprintHex(23))
	created := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusCreated, created.StatusCode)

	update := &MemoUpdateRequest{
		Fingerprint:  txn.Fingerprint,
		GomoneyTxnId: "1",
		Bank:         txn.Bank,
		AccountHint:  txn.AccountHint,
		Type:         txn.Type,
		Amount:       txn.Amount,
		TxAt:         txn.TxAt,
		Description:  txn.Description,
		Memo:         "  weekly groceries  ",
	}
	res := bridgeRequest(t, http.MethodPut, ts.URL+"/v1/transactions/memo", "test-token", marshal(t, update))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.JSONEq(t, `{"status":"updated","gomoneyTxnId":"1"}`, readBody(t, res))
	require.Len(t, client.created, 1)
	require.Len(t, client.updated, 1)
	require.EqualValues(t, 1, client.updated[0].GetId())
	require.Equal(t, "Card purchase [mellat/****1234] — weekly groceries", client.updated[0].GetTransaction().GetTitle())

	update.Memo = "coffee"
	res = bridgeRequest(t, http.MethodPut, ts.URL+"/v1/transactions/memo", "test-token", marshal(t, update))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.Equal(t, "Card purchase [mellat/****1234] — coffee", client.updated[1].GetTransaction().GetTitle())

	update.Memo = ""
	res = bridgeRequest(t, http.MethodPut, ts.URL+"/v1/transactions/memo", "test-token", marshal(t, update))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.Equal(t, "Card purchase [mellat/****1234]", client.updated[2].GetTransaction().GetTitle())
}

func TestUpdateMemoCanAnnotateLegacyBridgeEntry(t *testing.T) {
	client := &fakeGoMoneyClient{existing: map[int64]*gomoneypbv1.Transaction{
		41: {
			Id:                   41,
			Type:                 gomoneypbv1.TransactionType_TRANSACTION_TYPE_EXPENSE,
			Title:                "Legacy expense [mellat/****1234]",
			SourceAmount:         "-500000",
			SourceCurrency:       "IRR",
			SourceAccountId:      1,
			DestinationAmount:    "500000",
			DestinationCurrency:  "IRR",
			DestinationAccountId: 2,
			TransactionDate:      timestamppb.New(time.Date(2026, 9, 24, 12, 0, 0, 0, time.UTC)),
		},
	}}
	server, ts := newTestServer(t, client)
	fingerprint := fingerprintHex(24)
	txn := validTxn(fingerprint)
	txAt, err := txn.TxAtTime()
	require.NoError(t, err)
	require.NoError(t, server.dedup.Reserve(context.Background(), &DedupEntry{
		Fingerprint: fingerprint,
		BucketKey:   BucketKey(txn.Bank, txn.AccountHint, txn.Type, txn.Amount, txAt),
		Bank:        txn.Bank,
		AccountHint: txn.AccountHint,
		Type:        txn.Type,
		Amount:      txn.Amount,
		Desc:        normalizeDesc(txn.Description),
		TxUnix:      txAt.Unix(),
		RecordedAt:  time.Now().Format(time.RFC3339),
	}))
	require.NoError(t, server.dedup.Commit(context.Background(), fingerprint, "41"))

	update := &MemoUpdateRequest{
		Fingerprint:  fingerprint,
		GomoneyTxnId: "41",
		Bank:         txn.Bank,
		AccountHint:  txn.AccountHint,
		Type:         txn.Type,
		Amount:       txn.Amount,
		TxAt:         txn.TxAt,
		Description:  txn.Description,
		Memo:         "old purchase",
	}
	res := bridgeRequest(t, http.MethodPut, ts.URL+"/v1/transactions/memo", "test-token", marshal(t, update))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.Len(t, client.created, 0)
	require.Len(t, client.updated, 1)
	require.EqualValues(t, 1, client.updated[0].GetTransaction().GetExpense().GetSourceAccountId())
	require.Equal(t, "-500000", client.updated[0].GetTransaction().GetExpense().GetSourceAmount())
	require.Equal(t, "Legacy expense [mellat/****1234] — old purchase", client.updated[0].GetTransaction().GetTitle())
}

func TestUpdateMemoAfterCrossSourceDuplicateTargetsSingleGoMoneyTransaction(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)
	first := validTxn(fingerprintHex(26))
	created := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, first))
	require.Equal(t, http.StatusCreated, created.StatusCode)

	second := *first
	second.Fingerprint = fingerprintHex(27)
	second.TxAt = "2026-09-24T16:01:00+03:30"
	duplicate := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, second))
	require.Equal(t, http.StatusOK, duplicate.StatusCode)
	require.Contains(t, readBody(t, duplicate), `"gomoneyTxnId":"1"`)

	update := &MemoUpdateRequest{
		Fingerprint:  second.Fingerprint,
		GomoneyTxnId: "1",
		Bank:         second.Bank,
		AccountHint:  second.AccountHint,
		Type:         second.Type,
		Amount:       second.Amount,
		TxAt:         second.TxAt,
		Description:  second.Description,
		Memo:         "same purchase context",
	}
	res := bridgeRequest(t, http.MethodPut, ts.URL+"/v1/transactions/memo", "test-token", marshal(t, update))
	require.Equal(t, http.StatusOK, res.StatusCode)
	require.Len(t, client.created, 1)
	require.Len(t, client.updated, 1)
	require.EqualValues(t, 1, client.updated[0].GetId())
}

func TestUpdateMemoRejectsInvalidMemo(t *testing.T) {
	_, ts := newTestServer(t, &fakeGoMoneyClient{})
	update := &MemoUpdateRequest{Fingerprint: fingerprintHex(25), Memo: strings.Repeat("a", 201)}
	res := bridgeRequest(t, http.MethodPut, ts.URL+"/v1/transactions/memo", "test-token", marshal(t, update))
	require.Equal(t, http.StatusBadRequest, res.StatusCode)
}

func TestCreateIncomeUsesDefaultIncomeAndMappedBankAccount(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	txn := validTxn(fingerprintHex(11))
	txn.Type = TypeIncome
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))

	require.Equal(t, http.StatusCreated, res.StatusCode)
	require.Len(t, client.created, 1)
	income := client.created[0].GetIncome()
	require.NotNil(t, income)
	require.Equal(t, "-500000", income.SourceAmount)
	require.Equal(t, int32(3), income.SourceAccountId)
	require.Equal(t, "500000", income.DestinationAmount)
	require.Equal(t, int32(1), income.DestinationAccountId)
}

func TestCreateExpenseConvertsCounterpartToDefaultCurrency(t *testing.T) {
	client := &fakeGoMoneyClient{accounts: []GoMoneyAccount{
		{ID: 1, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET, Currency: "IRR"},
		{ID: 2, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE, Currency: "USD", IsDefault: true},
		{ID: 3, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_INCOME, Currency: "USD", IsDefault: true},
	}}
	_, ts := newTestServer(t, client)

	txn := validTxn(fingerprintHex(12))
	txn.Amount = 10_000_000
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))

	require.Equal(t, http.StatusCreated, res.StatusCode)
	require.Len(t, client.created, 1)
	expense := client.created[0].GetExpense()
	require.NotNil(t, expense)
	require.Equal(t, "-10000000", expense.SourceAmount)
	require.Equal(t, "IRR", expense.SourceCurrency)
	require.Equal(t, int32(1), expense.SourceAccountId)
	require.Equal(t, "43.48", expense.DestinationAmount)
	require.Equal(t, "USD", expense.DestinationCurrency)
	require.Equal(t, int32(2), expense.DestinationAccountId)
}

func TestCreateIncomeConvertsDefaultSourceToDefaultCurrency(t *testing.T) {
	client := &fakeGoMoneyClient{accounts: []GoMoneyAccount{
		{ID: 1, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET, Currency: "IRR"},
		{ID: 2, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE, Currency: "USD", IsDefault: true},
		{ID: 3, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_INCOME, Currency: "USD", IsDefault: true},
	}}
	_, ts := newTestServer(t, client)

	txn := validTxn(fingerprintHex(13))
	txn.Type = TypeIncome
	txn.Amount = 10_000_000
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))

	require.Equal(t, http.StatusCreated, res.StatusCode)
	require.Len(t, client.created, 1)
	income := client.created[0].GetIncome()
	require.NotNil(t, income)
	require.Equal(t, "-43.48", income.SourceAmount)
	require.Equal(t, "USD", income.SourceCurrency)
	require.Equal(t, int32(3), income.SourceAccountId)
	require.Equal(t, "10000000", income.DestinationAmount)
	require.Equal(t, "IRR", income.DestinationCurrency)
	require.Equal(t, int32(1), income.DestinationAccountId)
}

func TestCreateCrossCurrencyRejectsMissingRate(t *testing.T) {
	client := &fakeGoMoneyClient{
		accounts: []GoMoneyAccount{
			{ID: 1, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET, Currency: "IRR"},
			{ID: 2, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE, Currency: "USD", IsDefault: true},
		},
		currencies: []GoMoneyCurrency{{ID: "IRR", Rate: "230000.0", DecimalPlaces: 1}},
	}
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, validTxn(fingerprintHex(14))))

	require.Equal(t, http.StatusBadRequest, res.StatusCode)
	require.Contains(t, readBody(t, res), "Go Money currency rate is missing for USD")
	require.Empty(t, client.created)
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
