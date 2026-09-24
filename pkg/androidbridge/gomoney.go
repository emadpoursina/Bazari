package androidbridge

import (
	"context"
	"net/http"
	"time"

	transactionsv1connect "buf.build/gen/go/xskydev/go-money-pb/connectrpc/go/gomoneypb/transactions/v1/transactionsv1connect"
	transactionsv1 "buf.build/gen/go/xskydev/go-money-pb/protocolbuffers/go/gomoneypb/transactions/v1"
	"connectrpc.com/connect"
	"google.golang.org/protobuf/types/known/timestamppb"
)

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
// ConnectRPC API (TransactionsService/CreateTransaction).
type GoMoneyConnectClient struct {
	client transactionsv1connect.TransactionsServiceClient
}

// NewGoMoneyConnectClient builds the connect-based client for the given Go
// Money base URL + service token.
func NewGoMoneyConnectClient(baseURL, token string) *GoMoneyConnectClient {
	httpClient := &http.Client{
		Transport: &bearerTransport{token: token, base: http.DefaultTransport},
		Timeout:   30 * time.Second,
	}

	return &GoMoneyConnectClient{
		client: transactionsv1connect.NewTransactionsServiceClient(httpClient, baseURL),
	}
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

// Ping does a cheap read-only call to establish reachability for /v1/ping.
func (c *GoMoneyConnectClient) Ping(ctx context.Context) error {
	_, err := c.client.GetApplicableAccounts(ctx, connect.NewRequest(
		&transactionsv1.GetApplicableAccountsRequest{}))
	return err
}
