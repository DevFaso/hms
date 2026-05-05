# Changelog

All notable changes to the HMS platform are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions track the Flyway migration high-water mark on `main` plus
the named MVP increment shipping that batch.

The repo's authoritative roadmap and per-MVP narrative live in
[docs/super-admin-gaps.md](docs/super-admin-gaps.md) and
[docs/platform-management-gaps-vs-epic.md](docs/platform-management-gaps-vs-epic.md);
this file is the migration-keyed quick reference for operators
running deploys.

## [Unreleased] — MVP-c3 (branch `feature/super-admin-mvp-c3-foot-guns`)

Closes the two pre-launch foot-guns + the platform-config audit gap +
the Bridges-style message log gap from
[docs/platform-management-gaps-vs-epic.md](docs/platform-management-gaps-vs-epic.md).
Targets `develop`; awaiting `develop → uat → main` promote chain.

### Added

- **V89 migration — `clinical.integration_message_event`.** Strictly
  additive. New table for the Bridges-style per-message log with
  indexes on `(integration_id, received_at)`, `(status, received_at)`,
  partial `(organization_id, received_at)`, and `(correlation_id)`.
- `IntegrationMessageEvent` entity, `IntegrationMessageEventRepository`,
  `IntegrationMessageRecorder` (REQUIRES_NEW + 64 KB payload truncation
  + best-effort persistence), `SuperAdminIntegrationMessageService`
  + impl, and `SuperAdminIntegrationMessageController` exposing
  `GET /super-admin/integration-messages` and
  `POST /super-admin/integration-messages/{id}/replay`.
- `IntegrationMessageDirection` and `IntegrationMessageStatus` enums.
- `RegionPolicyCapabilitiesDTO` + `GET /super-admin/data-residency/policies/capabilities`
  endpoint exposing `remoteProvisioningCapable` for the editor UI.
- `AuditSource.PLATFORM_CONFIG` — logical view over `audit_event_logs`
  filtered to platform-administration writes.
- `AuditEventLogRepository.findByDateRangeAndEventTypeIn` /
  `findByDateRangeAndEventTypeNotIn` so the aggregator can split
  PLATFORM_CONFIG vs. SUPPORT without double-counting.
- Frontend route `/super-admin/integration-messages` and component
  with search filters, DLQ badge + shortcut, per-row replay action.
- Audit-search aggregation tab gains the PLATFORM_CONFIG toggle (on
  by default) with EN / FR / ES labels and a tag colour.
- Data-residency policy editor surfaces a stub-mode banner +
  read-only deployment-URL input when `remoteProvisioningCapable`
  is false.
- EN / FR / ES i18n keys for the new surfaces.

### Changed

- `TenantArchiveEncryptionServiceImpl` now validates KEK configuration
  in `@PostConstruct` — boot aborts with `IllegalStateException` if
  `hms.tenant-archive.kek-source=noop` is observed outside dev / test
  profiles. Replaces the prior lazy check that only fired on first
  encryption attempt.
- `TenantProvisioningClient` SPI gains a default
  `isRemoteCapable() { return true; }`. `StubTenantProvisioningClient`
  overrides it to `false`.
- `RegionPolicyServiceImpl.update()` rejects writes that introduce a
  non-empty `target_deployment_url` while only the stub provisioning
  client is wired (HTTP 400). Clearing or no-op writes always pass so
  legacy rows can be recovered.
- `SuperAdminAuditAggregationServiceImpl` splits `audit_event_logs`
  rows by event-type set — every row is tagged as exactly one of
  `SUPPORT` or `PLATFORM_CONFIG` so the source toggle never double-
  counts.
- `FeatureFlagServiceImpl` now emits `CONFIGURATION_CHANGED` audit
  rows on override upsert / delete. Audit failures are swallowed and
  logged; the operator's write still succeeds.
- `SuperAdminPlatformRegistryServiceImpl.scheduleReleaseWindow` now
  emits `PLATFORM_REGISTRY_UPDATED` audit rows with the same swallow-
  and-log posture.
- `StubPartnerConnector` accepts an optional `IntegrationMessageRecorder`
  so probe / re-sync activity also lands in the Bridges-style message
  log. Subclasses (`NhisConnector`, `NhiaConnector`, `CnamgsConnector`,
  `MutuelleConnector`) updated to forward the dependency.
- Frontend `RegionPolicyService` base path corrected from
  `/super-admin/region-policies` to `/super-admin/data-residency/policies`
  to match the backend `@RequestMapping`. Pre-existing typo that
  Karma SpyObj specs never caught.

### Tests

- New backend service + recorder tests:
  `IntegrationMessageRecorderTest`,
  `SuperAdminIntegrationMessageServiceImplTest`.
- Updates: `SuperAdminAuditAggregationServiceImplTest`,
  `SuperAdminPlatformRegistryServiceImplTest`,
  `RegionPolicyServiceImplTest`, `FeatureFlagServiceImplTest`,
  `TenantArchiveEncryptionServiceImplTest`,
  `StubPartnerConnectorTest`, `StubTenantProvisioningClientTest`.
- New Karma specs:
  `integration-messages.spec.ts` (8 cases covering DLQ shortcut,
  reset, replay success / failure, paging),
  data-residency stub banner + capability flow specs,
  audit-search PLATFORM_CONFIG toggle spec.
- All gates green at branch tip: `:hospital-core:test`,
  `:hospital-core:jacocoTestCoverageVerification` (80% INSTRUCTION),
  `npx ng test`, `npx ng build --configuration production`.

### Operator notes

- **MVP-c3 KEK abort.** A production deploy that omits
  `HMS_TENANT_ARCHIVE_KEK` will now refuse to boot. Set the env var
  (base64-encoded 32 bytes) and `hms.tenant-archive.kek-source=env`
  before deploying.
- **MVP-c3 provisioning guard is non-breaking.** Existing region
  rows with a populated `target_deployment_url` are untouched —
  the guard only fires when an operator attempts to *change* the
  value with the stub client wired. Clearing always works.
- **V89 is additive.** Rolling back drops the table; the recorder
  is best-effort and tolerates the table's absence.
- **Partner-connector message log entries are synthetic.** Until
  real wire protocols replace `StubPartnerConnector`, the message
  log will show one OUTBOUND/FAILED row per probe + one
  OUTBOUND/SENT row per re-sync. The DLQ count reflects the
  cumulative stub failures — not real partner traffic — until a
  real connector is wired.

## [V88] — MVP-c batch (live on `main` `b9f3fe0b`)

Promoted via `feature/super-admin-gaps-mvp-c-batch`.

- V84 hospital_lifecycle, V85 subscription_plan_feature_keys_jsonb,
  V86 region_policy, V87 audit_saved_search,
  V88 integration_health_event.
- MVP-2c GDPR packager + AES-256-GCM purge archive envelope.
- MVP-3b connector SPI + `/probe` + `/resync` + `/history`
  endpoints + stub NHIS / NHIA / CNAMGS / mutuelle providers.
- MVP-5c nginx 301s for `/feature-flags` + `/analytics`.
- MVP-6c jsonb `feature_keys` + plan-tier audit emission with
  bounded dedup.
- MVP-8c saved-search REST CRUD.
- MVP-9c per-region retention + export-format policy backend wired
  through `TenantPurgeExecutor` + `TenantExportPackager`.
- Hospital-level lifecycle state machine + JWT login-block + MFA
  step-up.

See [docs/super-admin-gaps.md](docs/super-admin-gaps.md) for the
full per-item narrative and prior MVP history (MVP-1 through
MVP-9 + sub-MVPs 4b–9b).
