# Contract: Sources and Settings chrome

**Feature**: 010-android-ui-refresh | **Date**: 2026-11-17

Behaviors from 005/008 stay. This contract is grouping, empty states, and permission actions.

## 1. Sources list

**Empty**: why sources matter (one sentence) + primary **Add source**. Bottom bar visible.

**Populated rows**: name, channel, identifier, bound account or “No account bound”, enabled switch, stale-binding warning when id is in `staleBindingIds`. Edit and Remove; Remove still confirms.

**Unparsed messages**: distinct section below sources (existing `ParseErrorReviewList`), not mixed into source cards. Sanitized reasons + Dismiss.

## 2. Source editor

Titled **Add source** / **Edit source**. Fields unchanged: name, identifier, channel, template, keywords, optional account, sample test. Validation messages sit next to the field. Test success shows **formatted** amount + direction (not raw `of 500000 IRR` only). Account bind still requires currency on the account (existing validator). Cancel/Back discards unsaved editor state as today.

## 3. Settings groups (scroll order)

Labeled sections, visually distinct cards:

1. **Connection destinations** — endpoint list; reorder / test / edit / delete **on each card**; add endpoint; fallback test. Secrets unchanged.
2. **Capture** — notification capture and SMS capture switches.
3. **Defaults** — default category/account (008 stamping: new captures only; stale IDs warn; pickers disable when catalog unavailable).
4. **Permissions** — notification access and SMS status; **missing notification access includes a control** that opens the system notification-listener screen (same intent as Home/Events).
5. **Diagnostics** — debug mode switch (quieter than capture toggles).

Do not restore a bank/package allow-list (005).

## 4. Shared permission copy

Home, Events scan-blocked, and Settings permission group use the same explanation: capture cannot read notifications until access is granted, plus a control that opens system settings.
