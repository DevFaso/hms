# Platform Management — Gaps vs. Real-World Epic

> Audit date: 2026-05-04 · Baseline: `main` @ `b9f3fe0b` (MVP-c batch backend)
> + `develop` @ `050d4d94` (MVP-c2 backend + frontend, awaiting promote chain)
> Scope: Platform Management surface only. For the broader super-admin
> roadmap see [super-admin-gaps.md](super-admin-gaps.md).

This document compares HMS Platform Management against what Epic Systems
ships in real-world enterprise hospital deployments (Bridges, System Pulse,
Foundation, Cogito/Caboodle, Application Manager). It is descriptive of
*what is missing* — sequencing and prioritization are at the bottom.

## What HMS has today

### Backend surface

| Area | Where | Notes |
| --- | --- | --- |
| Release windows | [`PlatformReleaseWindow`](../hospital-core/src/main/java/com/example/hms/model/platform/PlatformReleaseWindow.java) + [`SuperAdminPlatformRegistryController`](../hospital-core/src/main/java/com/example/hms/controller/SuperAdminPlatformRegistryController.java) | name / environment string / start-end / freeze flag / owner / notes |
| Per-tenant service registry | [`OrganizationPlatformService`](../hospital-core/src/main/java/com/example/hms/model/platform/OrganizationPlatformService.java) | per org, hospital, department; 10 service types; status enum (ACTIVE / PILOT / INACTIVE / PENDING / DECOMMISSIONED) |
| Integration SPI | `PlatformIntegrationAdapter`, `EligibilityProvider`, `IntegrationConnectivityProbe`, `Resyncable`, `PartnerConnector` (stubs) | adapters: EHR, billing, inventory; partner stubs: NHIS / NHIA / CNAMGS / mutuelle |
| Health monitoring | [`IntegrationHealthSnapshot`](../hospital-core/src/main/java/com/example/hms/model/integration/IntegrationHealthSnapshot.java) + [`IntegrationHealthEvent`](../hospital-core/src/main/java/com/example/hms/model/integration/IntegrationHealthEvent.java) + [`SuperAdminIntegrationHealthController`](../hospital-core/src/main/java/com/example/hms/controller/SuperAdminIntegrationHealthController.java) | 24 h rolling counters; HEALTHY / DEGRADED / FAILING / NO_HISTORY; probe + resync + 24 h history endpoints |
| Snapshot export | `GET /super-admin/platform/registry/snapshot` | one-shot JSON dump |
| Public ops endpoints | Spring Actuator | `/actuator/health`, `/actuator/info`, `/actuator/prometheus` |

### Frontend surface

| Route | What it shows |
| --- | --- |
| `/platform` (Dashboard tab) | KPI cards (totals / pending / disabled links / active releases); module cards; automation tasks |
| `/platform` (Services tab) | org selector, status filter, per-org service grid, detail drawer + edit, hospital link toggles, register-new drawer |
| `/platform` (Catalog tab) | searchable integration catalog, provision-to-org button, docs/sandbox links |
| `/platform` (Releases tab) | release window form + scheduled releases list |
| `/super-admin/integration-health` | status chips, per-integration card, probe + resync buttons, 24 h sparkline drawer |

## Gap analysis

### Backend gaps

#### 1. Bridges-style interface engine
Epic's Bridges has *per-message* tracing across HL7 / FHIR / X12: every
inbound ADT, ORM, ORU and every outbound claim is logged with status,
parsed segments, error reason, and is replayable. HMS has *event-level*
recording only (probe outcomes), no message-level log, no dead-letter
queue, no replay endpoint, no per-message status. The SPI hooks
(`Resyncable`, `IntegrationConnectivityProbe`) are the right shape but do
not have a sibling `MessageTraceRecorder` / `DeadLetterReplayService`.

#### 2. Build / environment promotion model
Epic tracks POC → DEV → TST → PRD with versioned packages and config
diff. HMS stores `PlatformReleaseWindow.environment` as a free-text
string with no environment registry, no build version, no package
contents, no promotion API. Nothing answers "what build is on `uat` right
now?" or "what changed between `uat` and `main`?" — that lives in git
history alone.

#### 3. Cross-environment configuration diff
Epic's Compare tool. HMS produces a registry snapshot but has no
snapshot-vs-snapshot diff endpoint, even though the raw material is
already there.

#### 4. SSO / IdP federation registry
No `IdentityProvider` entity. Real Epic deployments register SAML/OIDC
IdPs per-tenant (Imprivata, Azure AD, Okta) with metadata upload,
cert-expiry tracking, claim-mapping, and fallback. HMS has JWT auth only
— no per-tenant SAML metadata, no cert-expiry alerts.

#### 5. Service-account / API-credential inventory
`OrganizationPlatformService.apiKeyReference` is a free-text string. No
credential-vault entity, no rotation schedule, no `lastRotatedAt`, no
expiry alerts. Epic's vendor-key management tracks each credential and
warns on upcoming expiry.

#### 6. License / seat-consumption telemetry
MVP-6 added `SubscriptionPlan` but there is no seat-consumption feed
(concurrent sessions, named users by role). Epic surfaces concurrent
license usage in real time on System Pulse.

#### 7. ETL / batch-job status registry
Epic's Cogito / Caboodle dashboards show ETL runs. HMS has no
batch-job registry, no scheduled-job status endpoint, no failure
timeline. Spring `@Scheduled` jobs are invisible to ops staff.

#### 8. Backup / DR readiness tracking
No entity tracking last-backup, RPO, mirror-lag, DR drill outcome.
Production hospital systems require this for accreditation.

#### 9. Per-tenant capacity telemetry
DB size by tenant, storage by tenant, request-rate by tenant — none
surfaced. `RateLimitFilter` exists but does not expose its counters.

#### 10. Master-data governance
No patient-merge queue, no duplicate-patient detection, no
provider-directory cross-tenant view, no NPI / DEA registry. Epic's MPI /
Provider Master is core to platform administration.

#### 11. Outbound-feed reconciliation
For state immunization registries (IIS), PDMP, public-health reporting:
"did the message land?". HMS has no outbound-feed acknowledgment table.

### Frontend gaps

#### 1. System Pulse equivalent
No real-time dashboard. The platform Dashboard tab is a static
card-stats view refreshed on load. No live session count, no in-flight
request rate, no DB latency, no queue depth. Epic ops staff watch
System Pulse all day.

#### 2. Environment switcher / promotion flow UI
No UI to view "what is on each environment" or trigger promotion.
Today this lives in the operator's terminal and `git log`.

#### 3. Message-trace / DLQ console
Corollary of backend gap #1. Bridges UI lets ops staff search
individual messages by MRN, timestamp, interface, status, and replay
selected ones.

#### 4. SSO admin UI
No `/platform/sso` or `/platform/identity-providers` route to register
IdPs, upload SAML metadata, view cert expiry.

#### 5. Credential vault UI
Pairs with backend gap #5 — needs a screen showing upcoming rotations
per integration with a "rotate now" action.

#### 6. Job / ETL monitor
No UI for scheduled jobs (last run, next run, failures, manual
trigger).

#### 7. Platform audit view
`audit-search` covers tenant data. There is no equivalent for
*platform-config changes* (who edited a release window, who toggled
`managedByPlatform`, who provisioned a partner connector). Some
emission likely exists in `PermissionMatrixAuditEvent`, but no
dedicated platform-audit view.

#### 8. Per-tenant resource utilization view
"This org used X GB storage, Y API calls, Z concurrent sessions this
month."

#### 9. Deprecation / sunset planner
Epic surfaces "this version sunsets on date X". The HMS release-window
entity is forward-only; there is no API or feature deprecation
timeline.

#### 10. Capacity / cost dashboard
Pairs with backend gap #9.

## What shipped (MVP-c3 — branch `feature/super-admin-mvp-c3-foot-guns`)

**Tier 1 #3 — KEK fail-fast at startup.** [`TenantArchiveEncryptionServiceImpl.@PostConstruct`](../hospital-core/src/main/java/com/example/hms/service/tenant/TenantArchiveEncryptionServiceImpl.java) aborts boot with `IllegalStateException` when `hms.tenant-archive.kek-source=noop` is observed outside dev / test profiles. Production can no longer start with a no-op key.

**Tier 1 #4 — Provisioning foot-gun guard.** [`TenantProvisioningClient.isRemoteCapable()`](../hospital-core/src/main/java/com/example/hms/service/provisioning/TenantProvisioningClient.java) defaults `true`; the [stub overrides to `false`](../hospital-core/src/main/java/com/example/hms/service/provisioning/StubTenantProvisioningClient.java). [`RegionPolicyServiceImpl.update()`](../hospital-core/src/main/java/com/example/hms/service/impl/RegionPolicyServiceImpl.java) rejects writes that set `target_deployment_url` to a non-empty value when only the stub is wired (HTTP 400). The [data-residency policy editor](../hospital-portal/src/app/super-admin/data-residency-policy/data-residency-policy.ts) reads `GET /super-admin/data-residency/policies/capabilities`, disables the deployment-URL input, and shows a stub-mode banner.

**Tier 2 #6 — Platform-config audit view.** Added [`AuditSource.PLATFORM_CONFIG`](../hospital-core/src/main/java/com/example/hms/enums/AuditSource.java); [`SuperAdminAuditAggregationServiceImpl`](../hospital-core/src/main/java/com/example/hms/service/impl/SuperAdminAuditAggregationServiceImpl.java) splits `audit_event_logs` rows by event-type set so PLATFORM_CONFIG is its own filterable source without double-counting. Audit emission added on [`FeatureFlagServiceImpl`](../hospital-core/src/main/java/com/example/hms/service/impl/FeatureFlagServiceImpl.java) upsert / delete (CONFIGURATION_CHANGED) and [`SuperAdminPlatformRegistryServiceImpl.scheduleReleaseWindow`](../hospital-core/src/main/java/com/example/hms/service/impl/SuperAdminPlatformRegistryServiceImpl.java) (PLATFORM_REGISTRY_UPDATED). UI: PLATFORM_CONFIG toggle + colour added to [audit-search](../hospital-portal/src/app/super-admin/audit-search/audit-search.ts).

**Tier 2 #5 — Bridges-style message log + DLQ + replay.**

- **V89 migration** — `clinical.integration_message_event` (additive). Indexes on `(integration_id, received_at)`, `(status, received_at)`, partial `(organization_id)`, and `(correlation_id)`.
- Backend chain: [`IntegrationMessageEvent` entity](../hospital-core/src/main/java/com/example/hms/model/integration/IntegrationMessageEvent.java) + [repository](../hospital-core/src/main/java/com/example/hms/repository/integration/IntegrationMessageEventRepository.java) + [recorder](../hospital-core/src/main/java/com/example/hms/service/integration/message/IntegrationMessageRecorder.java) (REQUIRES_NEW, never throws, 64 KB payload truncation) + [search/replay service](../hospital-core/src/main/java/com/example/hms/service/impl/SuperAdminIntegrationMessageServiceImpl.java) + [controller](../hospital-core/src/main/java/com/example/hms/controller/SuperAdminIntegrationMessageController.java) at `GET /super-admin/integration-messages` and `POST /super-admin/integration-messages/{id}/replay`.
- Wired into the four [partner-connector stubs](../hospital-core/src/main/java/com/example/hms/service/integration/partner/) so probe / re-sync activity surfaces immediately.
- Frontend: [`/super-admin/integration-messages`](../hospital-portal/src/app/super-admin/integration-messages/integration-messages.ts) — search filters (integration / org / status / time range), DLQ badge + shortcut, per-row replay button with per-row error state, EN / FR / ES i18n.

## Suggested next-MVP picks

After the MVP-c2 promote chain lands on `main`, the highest-leverage
follow-ons are:

1. **Bridges-style message log + DLQ + replay** — biggest *real* Epic
   gap; the SPI hooks already exist, so the work is one new entity, one
   recorder, one controller, one search/replay UI.
2. **Environment & build-version registry** — small entity; unblocks
   the promotion-flow UI and answers "what is where" without `git log`.
3. **Platform-config audit view** — reuses MVP-8c aggregation
   infrastructure; only one new audit source to wire.

The other items (SSO registry, credential vault, ETL monitor, capacity
telemetry, MPI) are each their own MVP and most are not warranted
before the first paying multi-tenant customer is real. Flag them on the
roadmap; do not build them yet.

## References

- HMS today: [super-admin-gaps.md](super-admin-gaps.md), `SuperAdmin*Controller.java`, `hospital-portal/src/app/platform/`, `hospital-portal/src/app/super-admin/integration-health/`.
- Epic equivalents (for the reader, not URLs): Bridges, System Pulse,
  Foundation System, Application Manager, Cogito / Caboodle,
  Hyperdrive deployment, MPI / Provider Master.
