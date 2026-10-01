package androidbridge

import (
	"fmt"
	"net/http"
	"strings"
	"testing"

	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
	"github.com/stretchr/testify/require"
)

// 005-account-currency-sources T017 (contracts/bridge-api.md): POST
// /v1/transactions accepts non-rial currencies when they match the mapped (or
// explicit accountId) Go Money account, and still 400s on blank currency or a
// mapped-account currency mismatch.

func usdTxn(fingerprint string) *NormalizedTransaction {
	txn := validTxn(fingerprint)
	txn.Currency = "USD"
	return txn
}

// The static mapping in newTestServer points "mellat|****1234" → account 1.
func usdAccounts() []GoMoneyAccount {
	return []GoMoneyAccount{
		{ID: 1, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET, Currency: "USD"},
		{ID: 2, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE, Currency: "USD", IsDefault: true},
		{ID: 3, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_INCOME, Currency: "USD", IsDefault: true},
	}
}

// USD is accepted when the mapped Go Money account is USD.
func TestCreateAcceptsUsdWhenMappedAccountIsUsd(t *testing.T) {
	client := &fakeGoMoneyClient{accounts: usdAccounts()}
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, usdTxn(fingerprintHex(90))))
	require.Equal(t, http.StatusCreated, res.StatusCode)
	require.JSONEq(t, `{"status":"created","gomoneyTxnId":"1"}`, readBody(t, res))

	created := client.created[0]
	require.Equal(t, "USD", created.GetExpense().GetSourceCurrency())
	require.Equal(t, int32(1), created.GetExpense().GetSourceAccountId())
}

// USD rejected when the mapped Go Money account is IRR: currency mismatch.
func TestCreateUsdMappedAccountCurrencyMismatch(t *testing.T) {
	client := &fakeGoMoneyClient{} // default fake accounts are all IRR
	_, ts := newTestServer(t, client)

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, usdTxn(fingerprintHex(91))))
	require.Equal(t, http.StatusBadRequest, res.StatusCode)
	body := readBody(t, res)
	require.Contains(t, body, `"error":"validation"`)
	require.Contains(t, body, "mapped Go Money account currency does not match transaction currency")
}

// An explicit accountId (bound source account) takes precedence over the
// static mapping; a USD txn against that USD account is accepted.
func TestCreateUsdWithExplicitAccountIdAccepted(t *testing.T) {
	client := &fakeGoMoneyClient{accounts: usdAccounts()}
	_, ts := newTestServer(t, client)

	txn := usdTxn(fingerprintHex(92))
	accountID := int32(1)
	txn.AccountId = &accountID
	txn.Bank = "user"
	txn.AccountHint = "com.example.usd" // no static mapping for this pair

	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusCreated, res.StatusCode)
	created := client.created[0]
	require.Equal(t, "USD", created.GetExpense().GetSourceCurrency())
	require.Equal(t, int32(1), created.GetExpense().GetSourceAccountId())
}

// Blank (missing/empty) currency is rejected: "currency is required".
func TestCreateBlankCurrencyRejected(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	txn := validTxn(fingerprintHex(93))
	txn.Currency = ""
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusBadRequest, res.StatusCode)
	require.Contains(t, readBody(t, res), "currency is required")

	// Bulk: same rule per item (the bulk envelope echoes the error kind only).
	res2 := bridgePost(t, ts.URL+"/v1/transactions/bulk", "test-token",
		marshal(t, &BulkTransactionRequest{Transactions: []*NormalizedTransaction{txn}}))
	require.Equal(t, http.StatusOK, res2.StatusCode) // bulk returns per-item errors
	body := readBody(t, res2)
	require.Contains(t, body, `"error":"validation"`)
}

// Currency shape: 1–16 letters/digits; too long or non-alphanumeric rejected.
func TestCreateInvalidCurrencyShape(t *testing.T) {
	client := &fakeGoMoneyClient{}
	_, ts := newTestServer(t, client)

	for i, currency := range []string{
		strings.Repeat("A", 17), // too long
		"IR R",                  // space
		"IRR;DROP",              // punctuation
	} {
		txn := validTxn(fingerprintHex(byte(100 + i)))
		txn.Currency = currency
		res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
		require.Equal(t, http.StatusBadRequest, res.StatusCode, "currency %q", currency)
		require.Contains(t, readBody(t, res), "currency is invalid")
	}

	// A 16-letter/digit code passes the shape check and fails later on the
	// mapped-account currency match (account 1 is IRR in the default fake).
	txn := validTxn(fingerprintHex(120))
	txn.Currency = "ABCDEFGHIJKL1234"
	res := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn))
	require.Equal(t, http.StatusBadRequest, res.StatusCode)
	body := readBody(t, res)
	require.NotContains(t, body, "currency is invalid")
	require.Contains(t, body, "currency does not match")

	// Surrounding whitespace is trimmed before the shape check; a plain "IRR"
	// txn still delivers (older builds keep working).
	txn2 := validTxn(fingerprintHex(121))
	txn2.Currency = " IRR "
	res2 := bridgePost(t, ts.URL+"/v1/transactions", "test-token", marshal(t, txn2))
	require.Equal(t, http.StatusCreated, res2.StatusCode)
}

// Sanity: the amount formatter is currency-agnostic decimal rendering.
func TestFormatIRRRendering(t *testing.T) {
	require.Equal(t, "500000", formatIRR(500000))
	require.Equal(t, fmt.Sprintf("%d", int64(42)), formatIRR(42))
}
