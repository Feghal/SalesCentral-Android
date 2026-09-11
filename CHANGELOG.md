# Changelog

All notable changes to the SalesCentral Android SDK are tracked here. Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [semver](https://semver.org).

## [1.3.0] - 2026-09-11

### Added
- **Server-driven analytics-only.** Every config bundle (`createOrFetchUser`,
  `restoreUser`) carries the server's per-platform `analyticsOnly`
  (Settings → Android → Analytics-only (Android) in the admin). When it is
  on, `SalesCentral.start()` skips the Play Billing observer, the product
  prefetch and the subscription fetch, `SalesStore.refreshSubscription()`
  is a no-op, and transaction APIs (`loadProducts` / `purchase` /
  `applyReceipts` / `currentSubscription` / `spendCredits` / `claimReward` /
  `restorePurchases`) throw `SalesError.InvalidState("analytics_only")` — no
  `SalesCentral.json` key, no app update. Cached in the `TokenStore`
  (`SharedPreferences` in the default store) so relaunches are correct
  before bootstrap.
- `TokenStore.readServerAnalyticsOnly()` / `writeServerAnalyticsOnly(...)`
  with default implementations, so custom stores keep compiling.

## [1.2.1] - 2026-09-11

Event-tracking parity with the Swift SDK: every `track`-side API it has —
the outbox, enqueue-time `occurredAt`, `flush()`, and now registered event
properties (Swift 1.3.2) — exists here with the same semantics, bar the
deliberate never-send-directly delivery difference documented under 1.2.0.

### Added
- **Registered event ("super") properties** (port of Swift 1.3.2).
  `SalesClient.setEventProperties(mapOf(...))` — and `setEventProperty` /
  `removeEventProperty` / `clearEventProperties`, all forwarded by
  `SalesStore` too — registers properties the SDK merges into every
  subsequent `track` / `trackBatch` event, so a persistent trait segments
  event analytics without being passed at each call site. Per-call
  properties override a registered key; the merge happens at ENQUEUE time,
  so an event queued before the user exists carries the values registered
  when it was tracked, not when it flushes. In-memory (re-register each
  launch) and untouched by `clearUser()`, same as iOS. Values take the same
  shapes as `track`'s `properties` (`Map<String, Any?>`); a null value rides
  as JSON null exactly like a null per-call property. Typical use: an
  analytics-only app that handles subscriptions elsewhere registers
  `"plan" to "premium"|"trial"|"free"` from its subscription callback, then
  segments events by `properties.plan` in the admin.

## [1.2.0] - 2026-09-11

Analytics parity with the Swift SDK's outbox (1.3.1+). Every existing call
site keeps compiling; see "Changed" for the two signature changes.

### Added
- **Analytics outbox** (`Outbox.kt`, port of `Outbox.swift`): `track` /
  `trackBatch` / `recordSession` now queue into an in-memory FIFO (cap 500,
  oldest dropped) and the SDK's own drain coroutine delivers it — after the
  user is established (`ensureUser` / `restorePurchases`), on network
  reconnect (`NetworkMonitor`), and on every enqueue (coalesced into a
  single in-flight pass). Sends are token-checked (no doomed 401
  round-trips), batched at 50 events per request, and a batch is only let
  go once the server acknowledges it: network errors, 5xx and 401 re-queue
  it at the FRONT (order preserved), validation-class 4xx drop it with a
  `SalesLog` warning. Known limitations, same as iOS: memory-only (lost on
  process kill), no idempotency key (a lost 2xx can duplicate a batch on
  retry), and `clearUser()` empties the queue — **an event tracked
  immediately before `clearUser()` or process exit is dropped** before it
  ever reaches the wire; `await flush()` first if you need it delivered
  (e.g. a final `sign_out`-style event). `clearUser()` now logs a
  `SalesLog.warn` naming how many queued items it discarded, so a non-zero
  drop is at least visible in logcat even when that call was skipped.
- `occurredAt` on events: `SalesClient.track(name, properties, occurredAt =
  now)` and `SalesEvent.occurredAt` (defaults to construction time) put the
  ENQUEUE time on the wire, so a late flush no longer skews timelines.
  Previously `track` stamped `Instant.now()` at send time and `trackBatch`
  stamped one `now` for the whole batch.
- `SalesClient.flush(): FlushResult` — awaits one drain pass and reports
  `Delivered(count)` / `Retryable(reason)` / `Permanent(reason)` /
  `NothingToSend`. Production code never needs it (the SDK flushes on its
  own); it exists so a caller or a test can await delivery.
- `SalesClient.pendingAnalyticsCount`, `SalesLog.Category.OUTBOX`.
- `SalesClient` constructor gained `clock: Clock` and `outboxScope:
  CoroutineScope` (both defaulted) for tests.

### Changed
- `SalesClient.track` / `trackBatch` / `recordSession` and
  `SalesStore.track` are **no longer `suspend`** and return immediately —
  an app-side call never blocks on the 15s/30s HTTP timeouts again.
  Source-compatible for the usual call sites (calling a plain function from
  a coroutine is fine; `SessionTracker` and `SalesStore` were updated); a
  caller that passed `::track` as a `suspend` function reference needs a
  lambda. **Binary-incompatible**, though, for anyone consuming a
  precompiled artifact: dropping `suspend` changes the JVM method signature
  (it drops the trailing `Continuation` parameter and the `Any?` return
  used to bridge suspension), so a module built against 1.1.0 and shipped
  as an AAR/JAR — not rebuilt from source against 1.2.0 — can fail to link
  at runtime even though its *source* still compiles unchanged against
  1.2.0. Recompile any such dependent. `recordSession` no longer throws:
  retryable failures queue, permanent ones are logged and dropped.
- `track` used to POST the single-event wire shape (`{name, properties,
  occurredAt}`); everything now goes out as the batch shape
  (`{events: [...]}`), which the events endpoint has always accepted.
- Failed sends are no longer silently swallowed (`catch (_: Exception) {}`
  is gone): they are re-queued or logged, and `flush()` reports them.

## [1.1.0] - 2026-09-11

Toolchain release — **no public API change**. `SalesCentral`, `SalesClient`,
`SalesStore` and `PlayBillingConnector.purchase` / `loadProducts` /
`queryCurrentReceipts` keep their signatures and behaviour.

### Changed
- Build: AGP 9.4.0 with built-in Kotlin (the standalone
  `org.jetbrains.kotlin.android` plugin is no longer applied — AGP 9 rejects
  it), Kotlin 2.4.20, Gradle 9.7.1 wrapper, `compileSdk` 34 → 37 (Android
  17; minSdk stays 26). The removed `kotlinOptions { jvmTarget = "17" }`
  String DSL is now `kotlin { compilerOptions { jvmTarget = JVM_17 } }`.
  Consumers therefore need AGP 9.1+ / compileSdk 37+ (AGP 9 enforces the
  same-or-higher compileSdk rule for library consumers by default).
- Google Play Billing Library `billing-ktx` 6.2.1 → 9.1.0. Google Play has
  required Billing Library 8+ for every new app and update since 2026-08-31
  (extension deadline 2026-11-01); 9.x is supported until 2028-08-31.
  Migrations inside `PlayBillingConnector` (behaviour preserved —
  acknowledge-after-server-accept, unacknowledged-purchase auto-refund,
  single-flight purchase mutex and launch sweep are unchanged):
  - `BillingClient.Builder.enablePendingPurchases()` (removed in 8.0) →
    `enablePendingPurchases(PendingPurchasesParams)` with
    `enableOneTimeProducts()`, Google's documented functional equivalent.
    Prepaid-plan pending transactions are deliberately not opted into.
  - `queryProductDetailsAsync` (8.0) now delivers a `QueryProductDetailsResult`
    (fetched + unfetched products) — the connector reads it directly instead
    of the ktx `queryProductDetails` wrapper, and `loadProducts`' "Google Play
    did not return" warning now names WHY each SKU is missing (invalid id
    format / product not found / no eligible offer).
  - Nothing else in the connector was affected: `launchBillingFlow`,
    `queryPurchasesAsync(QueryPurchasesParams)`, the ktx `acknowledgePurchase`
    / `consumePurchase` wrappers and `Purchase` accessors kept their
    signatures. Suspended subscriptions (8.1) are not included in purchase
    queries unless opted into, so sweeps and restores see the same purchases
    as before.
- `play:integrity` 1.3.0 → 1.6.0 (API unchanged for
  `PlayIntegrityAttestService`), `kotlinx-coroutines-android` 1.7.3 → 1.11.0,
  `lifecycle-process` 2.6.2 → 2.11.0.

## [1.0.0] - 2026-07-09

Initial release — feature parity with the Swift SDK 1.2.0.

### Added
- `SalesCentral` facade: `start(context)`, assets-file configuration
  (`SalesCentral.json`), product loading from admin-registered SKUs,
  end-to-end `purchase(activity, product)` via Google Play Billing
  (acknowledge-after-server-accept), FCM push token registration.
- `SalesClient`: users (idempotent creation via stable clientId), receipts,
  subscriptions, credits + spend receipts with idempotency keys, retention
  rewards, sessions, custom events, user properties, paywalls, remote
  config, experiments.
- `SalesStore`: `StateFlow`-based observable state for Compose.
- Play Integrity device attestation (`"playIntegrity": true` in
  `SalesCentral.json`) — sessions attest like the iOS SDK's App Attest and
  run as production identities; without it, sessions are sandbox
  identities.
- Out-of-band purchase observer (renewals, pending purchases, launch
  sweep of unacknowledged purchases).
