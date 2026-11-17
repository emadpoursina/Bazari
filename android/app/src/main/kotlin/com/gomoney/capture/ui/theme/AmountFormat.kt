package com.gomoney.capture.ui.theme

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object AmountFormat {
    fun formatAmountMinor(
        amountMinor: Long,
        currency: String? = null,
        locale: Locale = Locale.getDefault(),
    ): String {
        val symbols = DecimalFormatSymbols(locale)
        val formatter = DecimalFormat("#,##0", symbols)
        val formattedNumber = formatter.format(amountMinor)
        val cleanCurrency = currency?.trim().orEmpty()
        return if (cleanCurrency.isEmpty()) {
            formattedNumber
        } else {
            "$formattedNumber $cleanCurrency"
        }
    }
}

fun formatAmountMinor(
    amountMinor: Long,
    currency: String? = null,
    locale: Locale = Locale.getDefault(),
): String = AmountFormat.formatAmountMinor(amountMinor, currency, locale)
