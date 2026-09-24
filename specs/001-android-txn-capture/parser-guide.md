# Adding a New Bank Parser (US6, SC-005)

New bank support = **one new parser class + fixtures + one registry line**.
Zero changes to capture/queue/delivery code (FR-008).

## Steps

1. **Create the parser** in `android/app/src/main/kotlin/com/gomoney/capture/parser/<Bank>Parser.kt`:

   ```kotlin
   class MyBankParser : BankParser {
       override val name = "MyBankParser"          // recorded as parserName
       override val bank = "mybank"                // bank slug
       override val supportedSources = setOf(
           "com.mybank.app",   // notification package name
           "MYBANK",           // SMS sender id
       )

       override fun canParse(event: RawEvent): Boolean = sourceMatches(event)

       override fun parse(event: RawEvent): ParseResult {
           // MUST be pure: no I/O, no clock — timestamps come from the RawEvent.
           // All digits/amounts go through AmountNormalizer (FR-010) — no
           // hand-rolled Persian digit handling.
           val text = AmountNormalizer.normalizeDigits(event.text)
           val amount = AmountNormalizer.parseAmountMinor(text)
           val hint = AmountNormalizer.extractAccountHint(text)
               ?: return ParseResult.Failure("no card hint found", Confidence.MEDIUM)
           // ... type detection ...
           return ParseResult.Success(draft(event).copy(
               accountHint = hint,
               amountMinor = amount ?: 0,
               description = BankParser.cleanDescription(event),
           ))
       }
   }
   ```

2. **Add fixtures** under `android/app/src/test/resources/fixtures/<bank>/*.json`
   (raw event in → expected NormalizedTransaction out, and `expectedFailure`
   cases for unmatched messages). Every parser change must go through
   fixtures (FR-029).

3. **Register it in `ParserRegistry()`** — the ONLY core line touched:

   ```kotlin
   constructor() : this(
       listOf(
           MellatParser(),
           MelliParser(),
           SamanParser(),
           MyBankParser(),      // ← new line
           SampleBankParser(),
           GenericParser(),     // always last
       ),
   )
   ```

4. **Enable the package** in the app allow-list (Settings → bank app
   allow-list, FR-018) so its notifications reach the pipeline.

## Rules (contracts/parser-interface.md)

- Parsers are **pure functions** of `RawEvent` — no network/db/clock access.
- The registry picks the **first `canParse=true`** parser in priority order;
  it never re-checks the allow-list.
- Unmatched events are **retained and flagged `parse_error`** — never dropped.
- `Failure.reason` must be sanitized (no raw message text, FR-023/028).
- Fingerprints are computed by the pipeline, never by parsers.

Reference example: `SampleBankParser.kt` + `fixtures/samplebank/`.
