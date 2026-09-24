package com.gomoney.capture.parser

/**
 * Shared Persian/Arabic-Indic numeral & format normalizer (research.md R10,
 * FR-010). All amount parsing MUST go through this object — parsers must not
 * hand-roll digit/separator handling.
 */
object AmountNormalizer {

    /** Map ۰-۹ (U+06F0–F9) and ٠-٩ (U+0660–69) to ASCII digits. */
    fun normalizeDigits(input: String): String = buildString(input.length) {
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> append('0' + (ch - '۰'))
                in '٠'..'٩' -> append('0' + (ch - '٠'))
                '٬' -> append(',') // Persian thousand separator U+066C → ASCII
                '٫' -> append('.') // Persian decimal separator U+066B → ASCII
                else -> append(ch)
            }
        }
    }

    /** Currency words stripped before parsing amounts (FR-010). */
    private val currencyWords = listOf("ریال", "ريال", "ریال‌ها", "Rial", "IRR", "IRR", "تومان", "Toman", "toman")

    /**
     * Parse the transaction amount in [text] into canonical integer minor
     * units (whole IRR). Returns null when no amount is present.
     *
     * Anchors on currency words to avoid picking card-number prefixes:
     *  - `مبلغ <n> ریال` ("amount n rial")
     *  - `<n> ریال|ريال|تومان|Toman` (number directly followed by currency word)
     *
     * A trailing تومان (toman) multiplies by 10 (1 toman = 10 rial).
     */
    fun parseAmountMinor(text: String): Long? {
        val normalized = normalizeDigits(text)

        // "مبلغ ۵۰۰٬۰۰۰ ریال" — amount introduced by the word "مبلغ".
        val labeled = Regex("""مبلغ\s*([\d,]+)\s*(ریال|ريال|تومان|Toman|toman)?""").find(normalized)
        if (labeled != null) {
            return toMinorUnits(labeled.groupValues[1], labeled.groupValues[2] + normalized.substring(labeled.range.last + 1))
        }

        // "۵۰۰٬۰۰۰ ریال" — bare number directly followed by a currency word.
        val trailing = Regex("""([\d,]+)\s*(ریال|ريال|تومان|Toman|toman)""").find(normalized)
        if (trailing != null) {
            return toMinorUnits(trailing.groupValues[1], trailing.groupValues[2])
        }

        return null
    }

    /**
     * Parse an explicit numeric token (already digit-normalized) into minor
     * units, given the text following it (for currency-word context).
     */
    fun toMinorUnits(numberToken: String, remainder: String): Long? {
        val cleaned = numberToken
            .replace(",", "")
            .replace("٬", "")
            .let { if (it.contains('.')) it.substringBefore('.') else it } // IRR = whole rials
            .trim()
            .takeWhile { it.isDigit() }
        val value = cleaned.toLongOrNull() ?: return null

        val rest = remainder.trimStart()
        val isToman = rest.startsWith("تومان") || rest.startsWith("Toman")
        return if (isToman) value * 10 else value
    }

    /** Strip currency words and collapse whitespace (used for descriptions). */
    fun stripCurrencyWords(text: String): String {
        var result = normalizeDigits(text)
        for (word in currencyWords) {
            result = result.replace(word, "", ignoreCase = true)
        }
        return result.trim()
    }

    /** Fingerprint description normalization (data-model.md §Fingerprint). */
    fun normalizeDescription(description: String): String =
        description.trim().replace(Regex("\\s+"), " ").lowercase()

    /** Masked-account hint, e.g. "****1234" from "6104.****1234" or "****1234". */
    fun extractAccountHint(text: String): String? {
        val normalized = normalizeDigits(text)
        val match = Regex("""\*{4}\d{3,4}""").find(normalized) ?: return null
        return match.value
    }
}
