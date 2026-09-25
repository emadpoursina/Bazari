package androidbridge

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	accountsv1connect "buf.build/gen/go/xskydev/go-money-pb/connectrpc/go/gomoneypb/accounts/v1/accountsv1connect"
	currencyv1connect "buf.build/gen/go/xskydev/go-money-pb/connectrpc/go/gomoneypb/currency/v1/currencyv1connect"
	accountsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/accounts/v1"
	currencyv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/currency/v1"
	gomoneypbv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/v1"
	"connectrpc.com/connect"
	"github.com/stretchr/testify/require"
)

func TestGoMoneyConnectClientListAccounts(t *testing.T) {
	var authorization string
	mux := http.NewServeMux()
	listAccounts := connect.NewUnaryHandler(
		accountsv1connect.AccountsServiceListAccountsProcedure,
		func(
			_ context.Context,
			_ *connect.Request[accountsv1.ListAccountsRequest],
		) (*connect.Response[accountsv1.ListAccountsResponse], error) {
			return connect.NewResponse(&accountsv1.ListAccountsResponse{
				Accounts: []*accountsv1.ListAccountsResponse_AccountItem{
					{Account: &gomoneypbv1.Account{
						Id:       11,
						Type:     gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET,
						Currency: "IRR",
						Name:     "must not be returned",
					}},
					{Account: &gomoneypbv1.Account{
						Id:       12,
						Type:     gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE,
						Currency: "IRR",
						Flags:    goMoneyDefaultAccountFlag,
					}},
				},
			}), nil
		},
	)
	mux.Handle(accountsv1connect.AccountsServiceListAccountsProcedure, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authorization = r.Header.Get("Authorization")
		listAccounts.ServeHTTP(w, r)
	}))
	server := httptest.NewServer(mux)
	defer server.Close()

	client := NewGoMoneyConnectClient(server.URL, "service-token")
	accounts, err := client.ListAccounts(context.Background())

	require.NoError(t, err)
	require.Equal(t, "Bearer service-token", authorization)
	require.Equal(t, []GoMoneyAccount{
		{ID: 11, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_ASSET, Currency: "IRR"},
		{ID: 12, Type: gomoneypbv1.AccountType_ACCOUNT_TYPE_EXPENSE, Currency: "IRR", IsDefault: true},
	}, accounts)
}

func TestGoMoneyConnectClientListCurrencies(t *testing.T) {
	var authorization string
	var requestedIDs []string
	mux := http.NewServeMux()
	getCurrencies := connect.NewUnaryHandler(
		currencyv1connect.CurrencyServiceGetCurrenciesProcedure,
		func(
			_ context.Context,
			req *connect.Request[currencyv1.GetCurrenciesRequest],
		) (*connect.Response[currencyv1.GetCurrenciesResponse], error) {
			requestedIDs = req.Msg.GetIds()
			return connect.NewResponse(&currencyv1.GetCurrenciesResponse{
				Currencies: []*gomoneypbv1.Currency{
					{Id: "IRR", Rate: "230000.0", DecimalPlaces: 1, IsActive: true},
					{Id: "USD", Rate: "1.00", DecimalPlaces: 2, IsActive: true},
				},
			}), nil
		},
	)
	mux.Handle(currencyv1connect.CurrencyServiceGetCurrenciesProcedure, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authorization = r.Header.Get("Authorization")
		getCurrencies.ServeHTTP(w, r)
	}))
	server := httptest.NewServer(mux)
	defer server.Close()

	client := NewGoMoneyConnectClient(server.URL, "service-token")
	currencies, err := client.ListCurrencies(context.Background(), []string{"IRR", "USD"})

	require.NoError(t, err)
	require.Equal(t, "Bearer service-token", authorization)
	require.Equal(t, []string{"IRR", "USD"}, requestedIDs)
	require.Equal(t, []GoMoneyCurrency{
		{ID: "IRR", Rate: "230000.0", DecimalPlaces: 1},
		{ID: "USD", Rate: "1.00", DecimalPlaces: 2},
	}, currencies)
}
