package com.gomoney.capture.source

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.gomoney.capture.model.Channel
import com.gomoney.capture.storage.AppDatabase
import com.gomoney.capture.storage.ParseErrorDao
import com.gomoney.capture.storage.ServerAccount
import com.gomoney.capture.storage.TransactionSource
import com.gomoney.capture.storage.TransactionSourceDao
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow

/** Outcome of a save attempt. */
sealed interface SaveResult {
    /** Saved; [stampedHeld] counts held rows stamped `held → queued` by this save (005 T028). */
    data class Saved(val stampedHeld: Int = 0) : SaveResult

    data class Invalid(val errors: List<ValidationError>) : SaveResult
}

/**
 * Persistence for user-defined sources (FR-001/002/003/005/006, US4). Saving
 * pre-validates via [SourceValidator] and relies on the `(identifier, channel)`
 * unique index as the race backstop (SC-009).
 *
 * 005-account-currency-sources (contracts/source-admission.md):
 *  - [seedDefaults] inserts the former built-in banks as ordinary sources,
 *    idempotently on `(identifier, channel)`; user edits are never overwritten.
 *  - saving a source with a NEW bound account stamps its currency onto that
 *    source's still-held empty-currency rows and moves them `held → queued`
 *    in one local transaction (FR-011). Rows that already have a currency —
 *    including `sent` — are never rewritten.
 */
class TransactionSourceRepository(
    private val sourceDao: TransactionSourceDao,
    private val parseErrorDao: ParseErrorDao,
    private val db: AppDatabase? = null,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
) {

    fun observeAll(): Flow<List<TransactionSource>> = sourceDao.observeAll()

    suspend fun all(): List<TransactionSource> = sourceDao.all()

    suspend fun enabled(): List<TransactionSource> = sourceDao.enabled()

    suspend fun byId(id: String): TransactionSource? = sourceDao.byId(id)

    /** Resolve the source for an event's identifier + channel (enabled or not). */
    suspend fun resolve(identifier: String, channel: Channel): TransactionSource? =
        sourceDao.byIdentifierAndChannel(identifier, channel.wire)

    /** Uniqueness pre-check hook used by the editor before saving (FR-005). */
    suspend fun validateForSave(source: TransactionSource): List<ValidationError> =
        SourceValidator.validate(source, sourceDao.all())

    suspend fun save(source: TransactionSource): SaveResult {
        val errors = validateForSave(source)
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)

        val now = clock().toString()
        val existing = sourceDao.byId(source.id)
        val toStore = source.copy(
            name = source.name.trim(),
            identifier = source.identifier.trim(),
            createdAt = existing?.createdAt ?: source.createdAt.ifBlank { now },
            updatedAt = now,
        )
        return try {
            var stampedHeld = 0
            if (db != null) {
                // Bind + stamp + held→queued in ONE local transaction (T006/T028).
                db.withTransaction {
                    upsert(toStore, existing)
                    stampedHeld = stampHeldRows(toStore, existing)
                }
            } else {
                upsert(toStore, existing)
            }
            SaveResult.Saved(stampedHeld)
        } catch (conflict: SQLiteConstraintException) {
            SaveResult.Invalid(
                listOf(
                    ValidationError(
                        "identifier",
                        "Identifier and channel are already used by another source",
                    ),
                ),
            )
        }
    }

    private suspend fun upsert(toStore: TransactionSource, existing: TransactionSource?) {
        if (existing == null) {
            sourceDao.insert(toStore)
        } else {
            sourceDao.update(toStore)
        }
    }

    /**
     * When a save binds/rebinds the source to an account that exists in the
     * cached catalog with a non-blank currency, stamp that currency onto the
     * source's still-held rows with empty currency and move them
     * `held → queued` (005 FR-011, contracts/source-admission.md "Stamp on
     * bind"). Rows that already have a non-empty currency (including `sent`)
     * are never rewritten; rebinding never rewrites stamped rows.
     */
    private suspend fun stampHeldRows(source: TransactionSource, previous: TransactionSource?): Int {
        val database = db ?: return 0
        val newBinding = source.boundAccountId ?: return 0
        if (previous?.boundAccountId == newBinding) return 0 // binding unchanged

        // Stale/absent account or blank currency → nothing to stamp; the
        // source still saves as unbound-equivalent (FR-006).
        val account = database.serverAccountDao().byId(newBinding) ?: return 0
        val currency = account.currency.trim()
        if (currency.isEmpty()) return 0

        database.normalizedTransactionDao().stampHeldEmptyCurrency(source.id, currency, newBinding)
        return database.deliveryRecordDao().moveHeldToQueuedForSource(source.id, currency)
    }

    /** Enable/disable without deleting (FR-003, US4.1/US4.2). */
    suspend fun setEnabled(id: String, enabled: Boolean): Boolean =
        sourceDao.setEnabled(id, enabled, clock().toString()) > 0

    /**
     * Remove the source row, keep already-captured transactions, and null the
     * parse-error reference so the review list survives (US4.3, FR-003).
     */
    suspend fun remove(id: String): Boolean {
        val deleted = sourceDao.deleteById(id)
        parseErrorDao.clearSource(id)
        return deleted > 0
    }

    /**
     * Seed the former built-in banks (005 T022, data-model.md §Schema
     * migration) as ordinary `TransactionSource` rows. Idempotent on
     * `(identifier, channel)`: an existing row — user-edited or not — is left
     * exactly as the user made it. Generic and SampleBank are NOT seeded.
     */
    suspend fun seedDefaults(): Int {
        val now = clock().toString()
        var inserted = 0
        for (seed in DEFAULT_SEEDS) {
            val existingRow = sourceDao.byIdentifierAndChannel(seed.identifier, seed.channel)
            if (existingRow != null) continue
            try {
                sourceDao.insert(
                    TransactionSource(
                        id = "seed-${seed.identifier}-${seed.channel}",
                        name = seed.name,
                        identifier = seed.identifier,
                        channel = seed.channel,
                        enabled = true,
                        template = seed.template,
                        incomeKeywords = seed.incomeKeywords,
                        expenseKeywords = seed.expenseKeywords,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                inserted++
            } catch (conflict: SQLiteConstraintException) {
                // Unique-index race: another caller seeded it first.
            }
        }
        return inserted
    }

    companion object {
        /**
         * Former built-in bank identifiers (data-model.md §Schema migration
         * table, plus each parser's SMS sender). Templates are the shared
         * `<direction> … مبلغ <amount> ریال` phrasing of those banks' real
         * messages; Blue uses `ريال`. Generic and SampleBank are excluded.
         */
        internal val DEFAULT_SEEDS: List<SeedSource> = listOf(
            SeedSource(
                name = "Mellat",
                identifier = "ir.mellat.mellatab",
                channel = "notification",
                template = "{direction} مبلغ {amount} ریال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت",
            ),
            SeedSource(
                name = "Mellat SMS",
                identifier = "MELLAT",
                channel = "sms",
                template = "{direction} مبلغ {amount} ریال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت",
            ),
            SeedSource(
                name = "Melli",
                identifier = "ir.bmi.mobilebank",
                channel = "notification",
                template = "{direction} مبلغ {amount} ریال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت,انتقال",
            ),
            SeedSource(
                name = "Melli SMS",
                identifier = "BMI",
                channel = "sms",
                template = "{direction} مبلغ {amount} ریال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت,انتقال",
            ),
            SeedSource(
                name = "Saman",
                identifier = "ir.sb24.saman",
                channel = "notification",
                template = "{direction} مبلغ {amount} ریال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت",
            ),
            SeedSource(
                name = "Saman SMS",
                identifier = "SAMAN",
                channel = "sms",
                template = "{direction} مبلغ {amount} ریال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت",
            ),
            SeedSource(
                name = "Blue",
                identifier = "com.samanpr.blu",
                channel = "notification",
                template = "{direction} {amount} ريال",
                incomeKeywords = "واریز,واريز",
                expenseKeywords = "خريد,خرید,برداشت",
            ),
        )
    }
}

/** A former built-in bank to seed as a normal, editable source (005 T022). */
data class SeedSource(
    val name: String,
    val identifier: String,
    val channel: String,
    val template: String,
    val incomeKeywords: String,
    val expenseKeywords: String,
)
