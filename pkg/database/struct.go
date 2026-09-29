package database

import (
	"github.com/shopspring/decimal"
	"gorm.io/gorm"
	"time"
)

type CurrencyRateMode string

const (
	CurrencyRateModeManual    CurrencyRateMode = "manual"
	CurrencyRateModeAutomatic CurrencyRateMode = "automatic"
)

type Currency struct {
	ID       string // Currency ID
	Rate     decimal.Decimal
	RateMode *CurrencyRateMode `gorm:"type:text;default:manual"`

	IsActive bool

	DecimalPlaces int32
	UpdatedAt     time.Time
	DeletedAt     gorm.DeletedAt
}

func (c *Currency) TableName() string {
	return "currencies"
}
