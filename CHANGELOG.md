# Changelog

All notable changes to the SalesCentral Android SDK are tracked here. Format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions
follow [semver](https://semver.org).

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
