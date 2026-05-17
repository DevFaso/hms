# ECOWAS data-residency support — decision record (foundation pass)

**Status:** foundation pass shipped on `feat/v2.0-foundation-batch` (roadmap row 39).
**Scope today:** a decision-record document capturing the prerequisites + the two viable cloud-procurement options + the criteria for picking between them. **No code changes** — Railway is the active platform; per-tenant region pinning lands once the procurement decision is made.

---

## Why a decision record + no code

The deliverable target is "Per-tenant region pinning (Railway → AWS or OVH-Africa)" — a cloud-procurement decision the engineering team cannot make unilaterally. Shipping code (a region-pin column on `hospital.organizations`, a `RegionRoutingService`, etc.) before the cloud-vendor decision is **premature**: the schema + service shape are different between an AWS-Africa rollout (multi-region S3 + RDS + VPC peering) and an OVH-Africa rollout (single-region OVH Public Cloud Postgres + per-cluster Railway projects).

Row 39 stays `started` with this decision record + the `Organization.region` column from V82 (already shipped — that's the data plane the eventual routing layer will key off). The row flips to `completed` only after:

1. The cloud-vendor decision lands (signed by Platform + Legal + the customer paying for ECOWAS).
2. The region-pin enforcement service ships (separate PR — schema, routing, audit, runbook).
3. At least one pilot ECOWAS tenant is moved end-to-end in UAT and soaks clean for 5 business days.

---

## ECOWAS country-by-country residency requirements

| Country | Law | Practical residency requirement |
| --- | --- | --- |
| Senegal | Loi n° 2008-12 (CDP) | Personal health data must stay on Senegalese soil unless explicit consent + adequacy decision by the CDP. |
| Côte d'Ivoire | Loi n° 2013-450 (ARTCI) | Health data storage outside CI requires a transfer authorization. EU adequacy partially recognized. |
| Ghana | Data Protection Act 2012 (DPC) | Cross-border transfer permitted with the data subject's consent OR a country-of-destination adequacy assessment. |
| Burkina Faso | Loi n° 010-2004/AN + CIL guidelines | Sensitive personal data must be hosted on Burkinabe soil unless a CIL exemption is granted. |
| Nigeria | NDPR + NDPA | Cross-border transfer to countries on the NDPC's adequacy list (currently includes EU, Canada, UK) permitted; others require explicit consent + safeguards. |
| Mali, Niger, Benin, Togo, Guinea, Cabo Verde | Various — generally GDPR-inspired | Treat as Senegal/CI equivalent until per-country counsel is engaged. |

**Common thread:** every ECOWAS deployment we're chasing today (Senegal, CI, Ghana, BF) needs the customer's PHI to land in storage physically located in the country OR in a country that the home regulator has marked adequate. Railway today has no African region.

---

## Two viable options

### Option A — AWS-Africa (Cape Town `af-south-1`)

- **Region**: `af-south-1` (Cape Town) is the only AWS region in Africa. Latency from BF / SN / CI ≈ 200-260 ms; Ghana / Nigeria ≈ 180 ms.
- **Storage**: RDS for Postgres + S3-compatible buckets, both pinned to `af-south-1`. Cross-region replication explicitly disabled.
- **Network**: VPC per tenant; ingress via CloudFront with TLS termination at the edge (origin lock pinned to `af-south-1`).
- **Migration shape**: forklift current Railway services into ECS Fargate / EKS. Significant CI/CD + IaC rewrite; existing Postgres dumps re-import cleanly.
- **Strengths**: mature managed services, well-understood compliance posture, S3 bulk-export (row 21) lands naturally.
- **Weaknesses**: data-residency in `af-south-1` is "Africa" but not specifically Senegal / CI / Ghana — local counsel in each country needs to bless this as meeting their residency requirement. Some require "data on national soil"; `af-south-1` is South Africa, which is in the continent but may not satisfy strict reading of e.g. Senegalese CDP.
- **Cost**: ≈ 1.4-1.7× current Railway spend at pilot scale, dropping as services consolidate.

### Option B — OVH-Africa (`af-paris` proxy via OVH Dakar PoP) + local in-country Postgres

- **Region**: OVH does not have an Africa public-cloud region yet. The closest viable approach is **dedicated bare-metal in Dakar** via OVH's Dakar PoP plus an EU-Paris Public Cloud Postgres replica with replication explicitly disabled for PHI tables.
- **Storage**: Postgres on a managed-by-us bare-metal node in Dakar; non-PHI metadata can live in Paris Public Cloud.
- **Network**: VPN-overlay between Dakar nodes + per-country site-local DNS.
- **Migration shape**: Postgres can be restored from `pg_dump` directly onto the Dakar node. Application services run as containers on the same node initially (single-node footprint).
- **Strengths**: data **physically on national soil** in Senegal — satisfies the strictest reading of CDP requirements. Operational team in Dakar has direct hardware access.
- **Weaknesses**: bare-metal ops — no managed Postgres, no managed Kubernetes, manual patch / DR / snapshot story. Significant ops overhead before HMS hits scale where the residency win matters.
- **Cost**: ≈ 2.2× current Railway spend at pilot scale, **most of which is ops headcount**, not cloud-line-item.

---

## Decision criteria

The choice tips on which axis the first ECOWAS customer cares most about:

| Axis | Option A wins | Option B wins |
| --- | --- | --- |
| Strict residency (national soil) | ❌ — `af-south-1` is in Africa but not the customer's country | ✅ — Dakar bare-metal IS in the customer's country |
| Ops surface | ✅ — managed services | ❌ — bare-metal patch / DR / snapshot is on us |
| Latency to BF / SN / CI | ⚠ 200-260 ms | ✅ < 30 ms in-country |
| Path to multi-country expansion | ✅ — same region scales to all | ❌ — each country needs its own footprint |
| Compatible with existing IaC | ⚠ rewrite needed | ⚠ rewrite needed |
| Compatible with row 21 (S3 export) | ✅ — native S3 | ⚠ — needs S3-compat layer |

**Recommendation (engineering-side, not authoritative):** Option A for the first ECOWAS customer **if** local counsel signs off on `af-south-1` as satisfying their country's residency requirement. Option B if that sign-off can't be obtained for a strictly-reading country (likely Senegal CDP).

This recommendation needs Platform + Legal + Sales sign-off before any code lands. The decision blocker is owned by Sales (which customer signs first and what their counsel demands), not by Engineering.

---

## What lands today (foundation pass)

- This document.
- The `Organization.region` column from V82 stays unchanged — it's the data plane the eventual routing layer will key off.
- No new feature flag, no new schema, no new service. The row-39 follow-on is the routing layer itself once the cloud-vendor decision lands.

## Row-39 follow-on (after decision)

- New schema: `hospital.organizations.region_pin VARCHAR(32) NOT NULL` (FK to a new `platform.cloud_regions` table) + per-region routing policy.
- New service: `RegionRoutingService` that resolves a request's target region from `Organization.region_pin` + the active `HospitalContext`.
- Migration playbook: tenant-by-tenant move from Railway → target region with explicit cutover windows.
- Audit emission: `ORGANIZATION_REGION_UPDATED` (already exists, V82 lane) extended with the cloud-target metadata.

---

## Reference

- `hospital-core/src/main/resources/db/migration/V82__organization_region.sql` (already shipped — the column the follow-on routing layer keys off)
- `docs/compliance/hipaa-gap.md` (cross-border PHI transfer obligations — overlapping but not identical concern)
- `docs/runbooks/disaster-recovery.md` (Railway snapshot+restore is the migration's source-of-truth)
- Roadmap row 33 (schema-per-tenant) — the routing layer composes with schema-per-tenant for strong-isolation customers in ECOWAS.
