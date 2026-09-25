package androidbridge

import (
	"context"
	"errors"
	"math"
	"net/http"
	"time"

	accountsv1connect "buf.build/gen/go/xskydev/go-money-pb/connectrpc/go/gomoneypb/accounts/v1/accountsv1connect"
	currencyv1connect "buf.build/gen/go/xskydev/go-money-pb/connectrpc/go/gomoneypb/currency/v1/currencyv1connect"
	transactionsv1connect "buf.build/gen/go/xskydev/go-money-pb/connectrpc/go/gomoneypb/transactions/v1/transactionsv1connect"
	accountsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/accounts/v1"
	currencyv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/currency/v1"
	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const goMoneyDefaultAccountFlag int64 = 1 << 0 // mirrors database.AccountFlagIsDefault

// bearerTransport injects the Go Money service token on every request
// (contracts/gomoney-integration.md — service token, never user credentials).
type bearerTransport struct {
	token string
	base  http.RoundTripper
}

func (t *bearerTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	r := req.Clone(req.Context())
	r.Header.Set("Authorization", "Bearer "+t.token)
	if t.base == nil {
		return http.DefaultTransport.RoundTrip(r)
	}
	return t.base.RoundTrip(r)
}

// newProtoTimestamp converts a time.Time to a protobuf timestamp.
func newProtoTimestamp(t time.Time) *timestamppb.Timestamp {
	return timestamppb.New(t)
}

// GoMoneyConnectClient implements GoMoneyClient against Go Money's public
// ConnectRPC API.
type GoMoneyConnectClient struct {
	client         transactionsv1connect.TransactionsServiceClient
	accountsClient accountsv1connect.AccountsServiceClient
	currencyClient currencyv1connect.CurrencyServiceClient
}

// NewGoMoneyConnectClient builds the connect-based client for the given Go
// Money base URL + service token.
func NewGoMoneyConnectClient(baseURL, token string) *GoMoneyConnectClient {
	httpClient := &http.Client{
		Transport: &bearerTransport{token: token, base: http.DefaultTransport},
		Timeout:   30 * time.Second,
	}

	return &GoMoneyConnectClient{
		client:         transactionsv1connect.NewTransactionsServiceClient(httpClient, baseURL),
		accountsClient: accountsv1connect.NewAccountsServiceClient(httpClient, baseURL),
		currencyClient: currencyv1connect.NewCurrencyServiceClient(httpClient, baseURL),
	}
}

// ListAccounts returns the minimal account metadata the bridge needs to
// resolve the mapped bank account and Go Money's default counterpart account.
// Account names, balances, and other user data are intentionally discarded.
func (c *GoMoneyConnectClient) ListAccounts(ctx context.Context) ([]GoMoneyAccount, error) {
	res, err := c.accountsClient.ListAccounts(ctx, connect.NewRequest(&accountsv1.ListAccountsRequest{}))
	if err != nil {
		return nil, err
	}

	accounts := make([]GoMoneyAccount, 0, len(res.Msg.GetAccounts()))
	for _, item := range res.Msg.GetAccounts() {
		if item == nil || item.GetAccount() == nil {
			continue
		}
		account := item.GetAccount()
		accounts = append(accounts, GoMoneyAccount{
			ID:        account.GetId(),
			Type:      account.GetType(),
			Currency:  account.GetCurrency(),
			IsDefault: account.GetFlags()&goMoneyDefaultAccountFlag != 0,
		})
	}
	return accounts, nil
}

// ListCurrencies returns configured exchange rates and target precision for
// the requested currency codes. It deliberately exposes no unrelated currency
// metadata.
func (c *GoMoneyConnectClient) ListCurrencies(ctx context.Context, ids []string) ([]GoMoneyCurrency, error) {
	res, err := c.currencyClient.GetCurrencies(ctx, connect.NewRequest(&currencyv1.GetCurrenciesRequest{
		Ids: ids,
	}))
	if err != nil {
		return nil, err
	}

	currencies := make([]GoMoneyCurrency, 0, len(res.Msg.GetCurrencies()))
	for _, item := range res.Msg.GetCurrencies() {
		if item == nil {
			continue
		}
		currencies = append(currencies, GoMoneyCurrency{
			ID:            item.GetId(),
			Rate:          item.GetRate(),
			DecimalPlaces: item.GetDecimalPlaces(),
		})
	}
	return currencies, nil
}

// CreateTransaction forwards the mapped CreateTransactionRequest.
func (c *GoMoneyConnectClient) CreateTransaction(
	ctx context.Context,
	req *transactionsv1.CreateTransactionRequest,
) (*transactionsv1.CreateTransactionResponse, error) {
	res, err := c.client.CreateTransaction(ctx, connect.NewRequest(req))
	if err != nil {
		return nil, err
	}
	return res.Msg, nil
}

// GetTransactionByID reads one transaction for a title-only memo update.
func (c *GoMoneyConnectClient) GetTransactionByID(
	ctx context.Context,
	id int64,
) (*gomoneypbv1.Transaction, error) {
	if id <= 0 || id > math.MaxInt32 {
		return nil, errors.New("transaction id is outside the supported range")
	}
	res, err := c.client.ListTransactions(ctx, connect.NewRequest(&transactionsv1.ListTransactionsRequest{
		Ids:   []int32{int32(id)},
		Limit: 1,
	}))
	if err != nil {
		return nil, err
	}
	for _, txn := range res.Msg.GetTransactions() {
		if txn != nil && txn.GetId() == id {
			return txn, nil
		}
	}
	return nil, errors.New("transaction not found")
}

// UpdateTransaction forwards a title-only or full transaction update.
func (c *GoMoneyConnectClient) UpdateTransaction(
	ctx context.Context,
	req *transactionsv1.UpdateTransactionRequest,
) (*transactionsv1.UpdateTransactionResponse, error) {
	res, err := c.client.UpdateTransaction(ctx, connect.NewRequest(req))
	if err != nil {
		return nil, err
	}
	return res.Msg, nil
}

// Ping does a cheap read-only call to establish reachability for /v1/ping.
func (c *GoMoneyConnectClient) Ping(ctx context.Context) error {
	_, err := c.client.GetApplicableAccounts(ctx, connect.NewRequest(
		&transactionsv1.GetApplicableAccountsRequest{}))
	return err
}
