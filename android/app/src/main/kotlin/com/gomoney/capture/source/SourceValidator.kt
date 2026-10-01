package com.gomoney.capture.source

import com.gomoney.capture.model.Channel
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.TransactionSource
import com.gomoney.capture.storage.keywordList

/** One user-facing source validation failure (contracts/source-template.md). */
data class ValidationError(val field: String, val message: String)

/**
 * Source validation contract (FR-005/012, SC-009): non-blank name/identifier,
 * a template with exactly one `{direction}` and one `{amount}`, non-empty
 * income/expense keyword lists, and no other source sharing the same
 * `(identifier, channel)`.
 */
object SourceValidator {

    const val MAX_NAME_LENGTH = 100

    fun validate(source: TransactionSource, existing: List<TransactionSource>): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val name = source.name.trim()
        if (name.isEmpty()) {
            errors += ValidationError("name", "Name is required")
        } else if (name.length > MAX_NAME_LENGTH) {
            errors += ValidationError("name", "Name must be $MAX_NAME_LENGTH characters or fewer")
        }

        val identifier = source.identifier.trim()
        if (identifier.isEmpty()) {
            errors += ValidationError("identifier", "Identifier is required")
        }

        if (Channel.wire(source.channel) == null) {
            errors += ValidationError("channel", "Channel must be notification or sms")
        }

        for (templateError in TemplateMatcher.templateErrors(source.template)) {
            errors += ValidationError("template", templateError)
        }

        if (source.incomeKeywords.keywordList().isEmpty()) {
            errors += ValidationError("incomeKeywords", "At least one income keyword is required")
        }
        if (source.expenseKeywords.keywordList().isEmpty()) {
            errors += ValidationError("expenseKeywords", "At least one expense keyword is required")
        }

        val conflict = existing.firstOrNull {
            it.id != source.id &&
                it.identifier.trim() == identifier &&
                it.channel.equals(source.channel, ignoreCase = true)
        }
        if (conflict != null) {
            errors += ValidationError(
                "identifier",
                "Identifier and channel are already used by \"${conflict.name}\"; " +
                    "edit or remove that source first",
            )
        }

        return errors
    }

    /**
     * 005 FR-006: a server account with a blank currency cannot be bound. The
     * returned error names the account so the user is told why; callers keep
     * the previous binding unchanged.
     */
    fun validateBinding(boundAccountId: Int?, accounts: List<ServerAccount>): ValidationError? {
        val id = boundAccountId ?: return null
        val account = accounts.firstOrNull { it.id == id } ?: return null // stale → handled elsewhere
        if (account.currency.isBlank()) {
            return ValidationError(
                "boundAccountId",
                "Account \"${account.label}\" has no currency; it cannot be used until a currency is set",
            )
        }
        return null
    }
}
