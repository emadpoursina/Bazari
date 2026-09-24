package com.gomoney.capture.parser

import com.gomoney.capture.storage.RawEvent

/**
 * Ordered parser registry (contracts/parser-interface.md §1). First parser
 * where canParse(event) is true wins; if none matches the event is retained
 * and flagged PARSE_ERROR (never silently dropped — US6 scenario 2).
 *
 * The registry does NOT re-check the allow-list — that gate lives in the
 * capture pipeline (FR-001). Adding a bank = new BankParser + fixtures +
 * ONE line below (SC-005).
 */
class ParserRegistry(parsers: List<BankParser>) {

    private val ordered: List<BankParser> = parsers

    /** MVP priority order: Mellat, Melli, Saman, SampleBank, Generic last. */
    constructor() : this(
        listOf(
            MellatParser(),
            MelliParser(),
            SamanParser(),
            SampleBankParser(),
            GenericParser(),
        ),
    )

    val parsers: List<BankParser> get() = ordered

    /** First canParse=true wins; null when no parser claims the event. */
    fun select(event: RawEvent): BankParser? = ordered.firstOrNull { it.canParse(event) }
}
