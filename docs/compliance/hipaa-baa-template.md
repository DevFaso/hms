# Business Associate Agreement — HMS template

**Status:** **DRAFT** — pending legal review. Not for execution without
sign-off from `{COMPANY_LEGAL_CONTACT}`.

Roadmap row 38 — `v2.0 / Compliance / HIPAA-equivalent posture`. This
template is the artefact behind §164.314(a)(1) ("Business Associate
Contracts") of the HIPAA Security Rule. The clauses are calibrated for
the HMS deployment topology described in
[`hipaa-gap.md`](./hipaa-gap.md): cloud-hosted Spring Boot on Railway,
managed Postgres, OIDC via Keycloak, AES-256-GCM PHI-at-rest, TLS
in-transit.

When a real customer name fills the placeholders, this file is copied
to `docs/compliance/baa/{customer-slug}-{YYYY-MM-DD}.md` and signed.
Each executed BAA stays archived in that directory for the §164.316(b)(2)(i)
6-year retention period.

---

## 1. Parties

This Business Associate Agreement ("**Agreement**") is entered into by:

- **Covered Entity:** `{CUSTOMER_LEGAL_NAME}` ("Covered Entity"), a
  `{healthcare provider / health plan / clearinghouse}` organised
  under the laws of `{JURISDICTION}` with offices at
  `{CUSTOMER_ADDRESS}`.

- **Business Associate:** `{COMPANY_LEGAL_NAME}` ("Business Associate"),
  doing business as **HMS**, organised under the laws of
  `{COMPANY_JURISDICTION}` with offices at `{COMPANY_ADDRESS}`.

Effective date: `{YYYY-MM-DD}`.

## 2. Definitions

Capitalised terms not otherwise defined have the meaning assigned in
the HIPAA Privacy Rule (45 CFR §164.500) and Security Rule (45 CFR
§164.302). Specifically:

- **Protected Health Information (PHI)** — individually identifiable
  health information transmitted or maintained in any form or medium.
  The exhaustive HMS inventory is in
  [`phi-inventory.md`](./phi-inventory.md).
- **Electronic PHI (ePHI)** — PHI transmitted or maintained in
  electronic media.
- **Subcontractor** — a person or entity to whom Business Associate
  delegates a function that involves the use or disclosure of PHI
  received from Covered Entity (e.g. the managed Postgres provider,
  the observability stack vendor).

## 3. Permitted uses and disclosures by Business Associate

Business Associate may use and disclose PHI:

3.1 To **perform the services** for Covered Entity described in the
underlying service agreement, including:

- Hosting the electronic health record (EHR) system on Business
  Associate's cloud infrastructure.
- Routing inbound HL7 v2 + FHIR R4 traffic from analyzers and
  partner systems.
- Generating clinical-decision-support recommendations.
- Producing audit logs + operational dashboards.
- Backing up, restoring, and replicating the database for disaster
  recovery and read-replica routing.

3.2 For Business Associate's **own management and administration**
provided that disclosure is required by law or that Business
Associate obtains reasonable assurances from the recipient that the
PHI will be held confidentially and used / disclosed only as
required by law or for the purpose disclosed.

3.3 To **provide data aggregation services** to Covered Entity as
permitted by 45 CFR §164.504(e)(2)(i)(B).

3.4 To **carry out its obligations under this Agreement.**

Business Associate may **NOT**:

- Use or disclose PHI for marketing, sale, or any commercial purpose
  not directly serving the Covered Entity.
- Train machine-learning or generative-AI models on PHI without
  the Covered Entity's written authorization.
- Use PHI in ways that would violate the HIPAA Privacy Rule if done
  by Covered Entity.

## 4. Safeguards

Business Associate will:

4.1 **Administrative safeguards** (§164.308). Maintain the controls
inventoried in [`hipaa-gap.md`](./hipaa-gap.md). The list below is the
**target state** Business Associate commits to reach on the documented
P0 critical path (target 2026-09-30, before first BAA execution); items
marked *(Pending)* are not yet evidenced in the repository as of the
date this template is signed:

- Named Security Officer + Privacy Officer. *(Pending — P0 in
  [`hipaa-gap.md`](./hipaa-gap.md), target 2026-08-31.)*
- Workforce security policy + termination procedures.
  *(Pending — P1, target 2026-12-31.)*
- Annual security-awareness training.
  *(Pending — P1, target 2026-12-31.)*
- Quarterly access reviews logged at
  `docs/compliance/access-review-log.md`.
  *(Pending — P0, target 2026-09-30; log file will be created on the
  first review.)*
- HIPAA-specific risk register reviewed annually.
  *(Pending — P0, target 2026-08-15.)*
- Incident-response runbook with breach-notification timing
  obligations (see Section 7 of this Agreement).
  *(Pending — P0, target 2026-09-30.)*

The Business Associate's status on each item must be re-attested by
Covered Entity at execution time; signature on this Agreement without
revised pending markers is the Business Associate's confirmation that
the corresponding artefacts have landed in the repository at the
referenced paths.

4.2 **Physical safeguards** (§164.310). Inherit from the underlying
cloud provider. Business Associate maintains and provides on request
the provider's most recent SOC 2 Type II report or equivalent
attestation.

4.3 **Technical safeguards** (§164.312). Maintain:

- AES-256-GCM encryption of PHI at rest (versioned wire format
  permitting algorithm rotation without destructive migration).
- TLS 1.2+ for all PHI in transit.
- Per-user authentication via OIDC + MFA for privileged roles.
- Role-based access control enforced at every API boundary.
- Comprehensive audit logging of access, modification, export, and
  consent events.
- Optimistic locking on clinical entities to prevent silent data
  loss under concurrent edits.
- Automatic logoff after 15 minutes of inactivity.
- Absolute session max-age of `{ABSOLUTE_SESSION_HOURS}` hours
  regardless of activity. *(Pending — engineering item P1 in
  [`hipaa-gap.md`](./hipaa-gap.md).)*

4.4 **Encryption key management.** Encryption keys are stored
separately from the encrypted data (Railway environment variable
isolated from the database). Business Associate commits to rotating
keys at least every **twelve (12) months**. *(Pending — the
key-rotation + crypto-shred procedure has not yet been authored as a
runbook. Target: `docs/runbooks/key-rotation.md`, P1 in
[`hipaa-gap.md`](./hipaa-gap.md), close 2026-12-31. Business Associate
will not sign this Agreement until the runbook is in place or this
clause is revised to reflect an interim manual procedure agreed with
Covered Entity.)*

## 5. Reporting

Business Associate will report to Covered Entity:

5.1 **Security incidents:** within **24 hours** of discovery, any
suspected or confirmed unauthorised access, use, disclosure,
modification, or destruction of PHI.

5.2 **Breaches of unsecured PHI** (HHS-defined): without unreasonable
delay and in no case later than **60 calendar days** after
discovery, with the content required by 45 CFR §164.410.

5.3 **Non-incident security events** material to Covered Entity
(e.g. provider attestation lapses, credential rotation outside the
documented cadence, threshold breaches on audit-log volume):
quarterly.

The incident-response runbook documenting these obligations on the
HMS side is at `docs/runbooks/hipaa-incident-response.md`. *(Pending —
P0 item in [`hipaa-gap.md`](./hipaa-gap.md), target close 2026-09-30.)*

## 6. Subcontractors

Business Associate will obtain, prior to allowing a Subcontractor to
create, receive, maintain, or transmit PHI on its behalf, written
satisfactory assurances that the Subcontractor will comply with the
applicable requirements of the HIPAA Rules and this Agreement
(45 CFR §164.502(e)(1)(ii)).

Current Subcontractor list (as of `{YYYY-MM-DD}`):

| Subcontractor              | Function                                | BAA / sub-BAA status |
| -------------------------- | --------------------------------------- | ---------------------|
| Railway, Inc.              | Compute + managed Postgres hosting      | `{status}`           |
| Splunk Inc.                | Audit-log + observability HEC ingestion | `{status}`           |
| Grafana Labs, Inc.         | Metric ingestion + dashboards           | `{status}`           |
| `{KEYCLOAK_HOST}`          | OIDC identity provider hosting          | `{status}`           |
| `{EMAIL_DELIVERY_VENDOR}`  | Patient + clinician notification email  | `{status}`           |
| `{SMS_DELIVERY_VENDOR}`    | Appointment + critical-result SMS       | `{status}`           |

Business Associate updates this table on every Subcontractor change
and notifies Covered Entity in writing within **30 days** of any
addition or removal.

## 7. Access, amendment, accounting

7.1 **Access (§164.524).** Within **30 days** of a written request,
Business Associate will make PHI available to Covered Entity or, as
directed by Covered Entity, to the Individual, to enable the
Individual's right of access. The patient portal + mobile apps satisfy
this obligation for most requests; out-of-band requests are honoured
via the export endpoints at
`/api/patient/{id}/export` (FHIR Bulk Data Access — pending v1.1
roadmap row 21).

7.2 **Amendment (§164.526).** Within **60 days** of a written request,
Business Associate will make any amendment to PHI directed by Covered
Entity. Operating procedure at
`docs/runbooks/patient-record-amendment.md` *(P1, pending)*.

7.3 **Accounting of disclosures (§164.528).** Business Associate will
document each disclosure of PHI other than for treatment, payment,
healthcare operations, or with Individual authorization, in sufficient
detail to enable Covered Entity to respond to a §164.528 request. The
`AuditEventLog` table captures the source dataset; the
patient-facing disclosure list endpoint is P2 in
[`hipaa-gap.md`](./hipaa-gap.md).

## 8. Audit + inspection

Business Associate will, on **30 days written notice**, allow Covered
Entity (or an independent auditor designated by Covered Entity) to
audit:

- The administrative, physical, and technical safeguards inventoried
  in [`hipaa-gap.md`](./hipaa-gap.md).
- The current state of the controls remediation backlog.
- Per-user audit logs scoped to Covered Entity's tenant.
- Subcontractor BAA / sub-BAA evidence.

Audit is at Covered Entity's expense unless a finding establishes
material non-compliance, in which case Business Associate bears the
cost.

## 9. Term + termination

9.1 **Term.** This Agreement is effective on the date first written
above and continues until the underlying service agreement terminates
or this Agreement is terminated under §9.2.

9.2 **Termination for cause.** Covered Entity may terminate this
Agreement immediately if Business Associate has violated a material
term of this Agreement and failed to cure within **30 days** of
written notice (or, if the violation is incurable, immediately).

9.3 **Effect of termination — return / destruction.** On termination,
Business Associate will, at Covered Entity's election:

- **Return** all PHI in a structured, machine-readable format
  (FHIR R4 Bundle preferred), or
- **Destroy** all PHI and certify destruction in writing within
  **30 days**.

If return or destruction is infeasible (e.g. PHI commingled in
backups that cannot be selectively purged), Business Associate
will continue to apply the safeguards in Section 4 for as long as
the PHI is retained, and limit any further use or disclosure to
those purposes that make return or destruction infeasible.

## 10. Indemnification + limitation of liability

10.1 Business Associate will indemnify Covered Entity against direct
damages arising from a breach of this Agreement caused by Business
Associate's gross negligence or wilful misconduct, capped at the
greater of:

- The amounts paid by Covered Entity to Business Associate in the
  **12 months** preceding the breach, or
- `{CAP_FLOOR}` (intended to ensure a meaningful floor for
  small-customer deployments).

10.2 In no event will either party be liable for indirect, incidental,
consequential, or punitive damages, except to the extent such damages
result from gross negligence or wilful misconduct.

## 11. Miscellaneous

11.1 **Regulatory amendments.** If the HIPAA Rules are amended or
replaced, the parties will amend this Agreement as necessary to
comply with the successor regime within **180 days** of the
effective date of the change.

11.2 **No third-party beneficiaries.** Except as required by 45 CFR
§164.504(e)(2)(ii)(F), this Agreement creates no third-party rights.

11.3 **Governing law.** This Agreement is governed by the laws of
`{GOVERNING_JURISDICTION}` without regard to its conflict-of-laws
provisions.

11.4 **Severability.** If any provision is held unenforceable, the
remainder remains in full force.

11.5 **Interpretation.** Any ambiguity is resolved in favour of an
interpretation that permits both parties to comply with the HIPAA
Rules.

11.6 **Counterparts + signatures.** This Agreement may be executed in
counterparts (including by DocuSign or equivalent), each of which is
deemed an original.

---

## Signatures

**Covered Entity**

```
Name:        ________________________________
Title:       ________________________________
Signature:   ________________________________
Date:        ________________________________
```

**Business Associate (HMS)**

```
Name:        ________________________________
Title:       ________________________________
Signature:   ________________________________
Date:        ________________________________
```

---

## Drafting notes

These notes do NOT become part of the executed Agreement and must be
removed before signature.

- Sections 4.3 (technical safeguards) is the strongest part because
  the engineering work is largely done. Don't dilute it during legal
  review.
- Section 4.4 (key management) commits to 12-month rotation. Get
  written ack from platform-ops before signing the first BAA.
- Section 5.2 (60-day breach notification) is the HHS-defined ceiling;
  some state regimes are tighter (e.g., 30 days in some US states,
  72 hours under GDPR). Customer's local regime takes precedence — add
  a per-customer clause if shorter.
- Section 6 (subcontractors) is a moving target. Keep the table in
  this template aligned with reality; an outdated table is itself a
  breach of representations.
- Section 10 (indemnification cap) intentionally leaves the floor
  amount blank. Negotiate per customer; tiered floors by customer
  size are workable.

Last updated: 2026-05-16.
