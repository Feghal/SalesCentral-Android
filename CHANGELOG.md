# Changelog

All notable changes to the SalesCentral Android SDK are tracked here. Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [semver](https://semver.org).

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
