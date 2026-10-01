package com.gomoney.capture.source

import com.gomoney.capture.model.Direction
import com.gomoney.capture.parser.AmountNormalizer
import com.gomoney.capture.storage.TransactionSource
import com.gomoney.capture.storage.keywordList

/**
 * Result of matching one message against a compiled template
 * (contracts/source-template.md). [Failure.reason] is a sanitized summary and
 * never contains raw message text (FR-023).
 */
sealed interface MatchResult {
    data class Success(val direction: Direction, val amountMinor: Long) : MatchResult

    data class Failure(val reason: String) : MatchResult
}

/** Compiled form of a user template: literal anchors + ordered slots. */
data class CompiledTemplate(
    val anchors: List<String>,
    val slots: List<SlotKind>,
) {
    enum class SlotKind { DIRECTION, AMOUNT }
}

/**
 * Fill-in-the-blank template engine (research.md R4/R5/R6, FR-007/009/010/011/026).
 *
 * A template is literal text with exactly one `{direction}` and one `{amount}`.
 * Matching normalizes digits/whitespace, requires the literal anchors to occur
 * in order, and treats the text between anchors as each placeholder's segment.
 * The amount comes from the `{amount}` segment; the direction is resolved from
 * the source's income/expense keyword lists over the whole message.
 */
object TemplateMatcher {

    const val DIRECTION_PLACEHOLDER = "{direction}"
    const val AMOUNT_PLACEHOLDER = "{amount}"

    private val placeholderRegex = Regex("""\{(direction|amount)\}""")
    private val tokenRegex = Regex("""[\p{L}\p{N}]+""")
    private val numberRegex = Regex("""[\d][\d,.]*""")

    /** True when [template] compiles (exactly one of each placeholder). */
    fun isValidTemplate(template: String): Boolean = templateErrors(template).isEmpty()

    /** Human-readable validation errors naming the missing/duplicated placeholder. */
    fun templateErrors(template: String): List<String> {
        val kinds = placeholderRegex.findAll(template).map { it.groupValues[1] }.toList()
        val errors = mutableListOf<String>()
        if (kinds.count { it == "direction" } != 1) {
            errors += "Template must contain exactly one $DIRECTION_PLACEHOLDER placeholder"
        }
        if (kinds.count { it == "amount" } != 1) {
            errors += "Template must contain exactly one $AMOUNT_PLACEHOLDER placeholder"
        }
        return errors
    }

    /**
     * Compile a template into anchors and slots. Throws [IllegalArgumentException]
     * when the template does not contain exactly one of each placeholder.
     */
    fun compile(template: String): CompiledTemplate {
        val errors = templateErrors(template)
        require(errors.isEmpty()) { errors.joinToString("; ") }

        val matches = placeholderRegex.findAll(template).toList()
        val anchors = mutableListOf<String>()
        val slots = mutableListOf<CompiledTemplate.SlotKind>()
        var cursor = 0
        for (match in matches) {
            anchors += normalizeLiteral(template.substring(cursor, match.range.first))
            slots += if (match.groupValues[1] == "direction") {
                CompiledTemplate.SlotKind.DIRECTION
            } else {
                CompiledTemplate.SlotKind.AMOUNT
            }
            cursor = match.range.last + 1
        }
        anchors += normalizeLiteral(template.substring(cursor))
        return CompiledTemplate(anchors = anchors, slots = slots)
    }

    /**
     * Pure, fixture-testable match (contracts/source-template.md rule 5). No
     * I/O, no clock.
     */
    fun match(compiled: CompiledTemplate, message: String, source: TransactionSource): MatchResult {
        val normalizedMessage = normalize(message)

        // Anchor-and-scan: every non-empty literal anchor must appear in
        // template order. A literal that normalizes to empty (for example the
        // single space between two adjacent placeholders) is zero-width, so a
        // placeholder's segment spans from the end of the previous non-empty
        // anchor to the start of the next non-empty anchor.
        val anchors = compiled.anchors
        val anchorStart = IntArray(anchors.size)
        var searchFrom = 0
        for (index in anchors.indices) {
            val anchor = anchors[index]
            if (anchor.isEmpty()) {
                anchorStart[index] = searchFrom
                continue
            }
            val found = normalizedMessage.indexOf(anchor, searchFrom)
            if (found < 0) return MatchResult.Failure("message does not match template")
            anchorStart[index] = found
            searchFrom = found + anchor.length
        }

        fun segmentFor(slotIndex: Int): String {
            var start = 0
            for (index in slotIndex downTo 0) {
                if (anchors[index].isNotEmpty()) {
                    start = anchorStart[index] + anchors[index].length
                    break
                }
            }
            var end = normalizedMessage.length
            for (index in slotIndex + 1 until anchors.size) {
                if (anchors[index].isNotEmpty()) {
                    end = anchorStart[index]
                    break
                }
            }
            return normalizedMessage.substring(start, end.coerceAtLeast(start)).trim()
        }

        val amountSegment = segmentFor(compiled.slots.indexOf(CompiledTemplate.SlotKind.AMOUNT))
        val amount = extractAmount(amountSegment)
            ?: return MatchResult.Failure("amount not found")
        if (amount <= 0) return MatchResult.Failure("amount is not a positive number")

        val direction = resolveDirection(normalizedMessage, source)
            ?: return MatchResult.Failure(directionFailureReason(normalizedMessage, source))

        return MatchResult.Success(direction = direction, amountMinor = amount)
    }

    /** Offline preview used by the source editor's optional Test button (FR-027). */
    fun preview(
        template: String,
        message: String,
        incomeKeywords: List<String>,
        expenseKeywords: List<String>,
    ): MatchResult {
        val compiled = runCatching { compile(template) }
            .getOrElse { return MatchResult.Failure(it.message ?: "invalid template") }
        val synthetic = TransactionSource(
            id = "",
            name = "",
            identifier = "",
            channel = "notification",
            enabled = true,
            template = template,
            incomeKeywords = incomeKeywords.joinToString(","),
            expenseKeywords = expenseKeywords.joinToString(","),
            createdAt = "",
            updatedAt = "",
        )
        return match(compiled, message, synthetic)
    }

    // --- internals ---

    private fun extractAmount(segment: String): Long? {
        val normalized = AmountNormalizer.normalizeDigits(segment)
        val match = numberRegex.find(normalized) ?: return null
        val remainder = normalized.substring(match.range.last + 1)
        return AmountNormalizer.toMinorUnits(match.value, remainder)
    }

    private fun resolveDirection(message: String, source: TransactionSource): Direction? {
        val income = matchesKeywords(message, source.incomeKeywords.keywordList())
        val expense = matchesKeywords(message, source.expenseKeywords.keywordList())
        return when {
            income && !expense -> Direction.INCOME
            expense && !income -> Direction.EXPENSE
            else -> null
        }
    }

    private fun directionFailureReason(message: String, source: TransactionSource): String {
        val income = matchesKeywords(message, source.incomeKeywords.keywordList())
        val expense = matchesKeywords(message, source.expenseKeywords.keywordList())
        return if (income || expense) "direction is ambiguous" else "direction not recognized"
    }

    private fun matchesKeywords(message: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val tokens = tokenRegex.findAll(message).map { it.value.lowercase() }.toSet()
        return keywords.any { keyword ->
            val normalizedKeyword = normalizeLiteral(keyword)
            when {
                normalizedKeyword.isEmpty() -> false
                normalizedKeyword.any { it.isWhitespace() } -> message.contains(normalizedKeyword)
                else -> tokens.contains(normalizedKeyword)
            }
        }
    }

    private fun normalize(value: String): String =
        AmountNormalizer.normalizeDigits(value)
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    private fun normalizeLiteral(value: String): String = normalize(value)
}
