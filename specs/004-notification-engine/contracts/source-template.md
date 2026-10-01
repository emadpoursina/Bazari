# Contract: User Source & Template Matching (in-app, Kotlin)

**Scope**: Internal contract within the Android capture app. It defines how a user-authored `TransactionSource` is validated, how its `MessageTemplate` is compiled and matched, and how the result plugs into the existing capture pipeline. Adding or editing a source requires no code change or app release (FR-008).

## Source model

```kotlin
enum class Channel { NOTIFICATION, SMS }

data class TransactionSource(
    val id: String,                 // uuid
    val name: String,
    val identifier: String,         // package id (notification) or SMS sender id
    val channel: Channel,
    val enabled: Boolean,
    val template: String,           // contains exactly one {direction} and one {amount}
    val incomeKeywords: List<String>,
    val expenseKeywords: List<String>,
    val boundAccountId: Int?,       // server account id, or null
    val boundAccountLabel: String?,
)
```

## Validation contract (`SourceValidator`)

`SourceValidator.validate(source, existing: List<TransactionSource>): List<ValidationError>`

1. `name` non-blank.
2. `identifier` non-blank.
3. `template` compiles and contains exactly one `{direction}` and one `{amount}`; a missing placeholder is reported by name (FR-012).
4. `incomeKeywords` and `expenseKeywords` each contain at least one non-empty, de-duplicated word (FR-012).
5. No other source shares the same `(identifier, channel)`; the error names the conflicting source (FR-005, SC-009). This mirrors the `transaction_sources` unique index, which is the race backstop.
6. An unbound source (`boundAccountId == null`) is valid; the server list being empty or unavailable is not an error (FR-013 edge case).

Saving is allowed whether or not the optional test preview was run (FR-027).

## Template grammar

- Literal text plus exactly two placeholder tokens: `{direction}` and `{amount}`.
- Placeholders may appear anywhere in the sample; either may precede the other.
- The literal text around the placeholders forms the anchors that identify a message from this source.
- The user builds the template by pasting a real message and inserting the two markers.

## Matching contract (`TemplateMatcher`)

```kotlin
sealed interface MatchResult {
    data class Success(val direction: Direction, val amountMinor: Long) : MatchResult
    data class Failure(val reason: String) : MatchResult   // sanitized; never contains raw text
}

enum class Direction { INCOME, EXPENSE }

object TemplateMatcher {
    fun compile(template: String): CompiledTemplate          // throws/returns error on invalid template
    fun match(compiled: CompiledTemplate, message: String, source: TransactionSource): MatchResult
}
```

Rules:

1. **Normalization**: map Persian/Arabic-Indic digits and separators to ASCII and collapse whitespace via the shared `AmountNormalizer` before matching (FR-011). Matching is case-insensitive.
2. **Anchoring**: literal anchors must occur in the message in template order. Text before the first anchor, after the last anchor, and between anchors is tolerated, so dates and reference numbers may differ between messages (spec assumption).
3. **Amount**: take the positive integer token from the `{amount}` segment; run it through `AmountNormalizer` to whole-IRR minor units. Missing, zero, or non-numeric → `Failure` (spec edge case, FR-009).
4. **Direction**: resolve against `source.incomeKeywords` / `source.expenseKeywords` over the normalized message. Exactly one list matching → that direction; neither or both → `Failure` (FR-026, spec edge case).
5. **Purity**: `match` is a pure function of its inputs (no I/O, no clock) and is fixture-testable.
6. **Safety**: `Failure.reason` is a sanitized summary only (for example "amount not found", "direction not recognized", "message does not match template"); it never contains raw message text (FR-023).

## Pipeline integration

`CapturePipeline` resolves a `TransactionSource` for an event by `(event.sourcePackage == source.identifier)` and `(event.source == source.channel)` among enabled sources (FR-004, FR-024), before falling back to the built-in allow-list + `ParserRegistry`.

- **Match success** → build a `NormalizedTransaction` with `sourceId` = the source id, `accountId` = `boundAccountId`, `type` = resolved direction, `amountMinor` = extracted amount, `bank`/`accountHint` stable values for fingerprinting, `parserName` = `UserSourceParser`, and feed it through the **existing** fingerprint, dedup, outbox, and delivery path unchanged (FR-022).
- **Match failure** → retain the raw event, create a `ParseErrorMessage` (sanitized reason) and a `DeliveryRecord` parse error, and create no transaction (FR-010).
- **No source and not allow-listed** → ignored without persistence (existing behavior, FR-004).

## Test preview

The source editor's **Test** button calls `TemplateMatcher.match` on the pasted sample and shows the extracted direction and amount, or the failure reason, before saving. Testing is optional and works offline; saving without testing is allowed (FR-027, SC-011).
