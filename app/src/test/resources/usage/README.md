# Usage display boundary cases

`display-cases.json` is shared by App `MessageUsageStatsTest` and Web `tests/message-usage.test.ts`.
`message` uses persisted `UIMessage` JSON (including lowercase `role: "assistant"`), not Web DTO role casing. The Web pure function consumes only statistics and timestamps.

Expected fields describe both numeric values and display eligibility. A null output is unknown, not zero; null duration/TPS must not be displayed. `usageComplete: null` (or absent in the message) is legacy unknown, not complete. Explicit exact zero/negative durations must never use a timestamp fallback. Sub-millisecond legacy intervals are truncated after subtraction, matching Kotlin `Duration.toMillis`.

Non-finite JS numbers and invalid date strings cannot be represented in valid Kotlin message JSON; Web-only tests cover those separately. No fixture depends on viewing time or calls a provider.
