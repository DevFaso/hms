# SonarQube Remediation Instructions — HMS

> Companion playbook for [docs/copilot-review.md](./copilot-review.md), which
> is the raw export of the SonarCloud findings on `DevFaso_hms`. This
> document is **action-oriented**: each section is a *pattern* with a fix
> template, a list of the specific files affected, and verification
> guidance. Work through it pattern-by-pattern in dedicated PRs — do not
> try to fix everything in one mega-commit.
>
> Last regenerated against the SonarCloud snapshot dated 2026-05-10.

## At a glance

| | |
|---|---|
| Source | SonarCloud — `https://sonarcloud.io/project/overview?id=DevFaso_hms` |
| Total findings | **100** — 96 Code Smells + 4 Vulnerabilities |
| Severity mix | ~50 Critical · ~22 Major · ~24 Minor · ~8 Info |
| Largest individual debt | **PatientPortalServiceImpl.java L892** — Brain Method, Cognitive Complexity **40 → 15**, ~30 min |
| Largest aggregate debt | Duplicated role-string literals across service impls (~50 min total) |
| Local gate | `./gradlew :hospital-core:test :hospital-core:jacocoTestCoverageVerification` |
| CI gate | `.github/workflows/build.yml` runs SonarCloud on every push and PR |

## How to use this document

1. **Pick one pattern below** (e.g. "Pattern 5 — Duplicated string literals").
2. **Read the fix template** — it shows a before/after and the rationale.
3. **Work through the file list** for that pattern in a single dedicated PR
   titled `refactor(sonar): <pattern name>`.
4. **Run the gates locally** before pushing:
   ```
   cd hospital-portal && npm run lint && npm run format:check && npm run test:headless
   ./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification
   ```
5. **Sonar runs on push** via [.github/workflows/build.yml](../.github/workflows/build.yml).
   The PR will show the remaining issue count and quality-gate result.

> **Triage priority:** PR-order the patterns roughly by **Security (P0)** →
> **Critical code smells in security-sensitive files (P1)** → **Brain
> methods & cognitive complexity > 25 (P2)** → **Constant extraction
> (P3)** → **Style / minor (P4)**. See §"Suggested PR sequence" at the
> bottom.

---

## Vulnerabilities (P0 — Security)

### Pattern 1 — Lock workflow dependency versions

> **Note (2026-05-10 correction):** Sonar S6321 ("Using dependencies
> without locking resolved versions") fires on **two distinct
> sub-patterns** in this codebase. They share a Sonar rule ID but
> need different fixes. Both are addressed in this section.

#### 1a. Pin every npm package in `run:` steps

The three SonarCloud findings tagged "Vulnerability — Major" at
[agent-lint.yml:18](../.github/workflows/agent-lint.yml#L18),
[agent-lint.yml:76](../.github/workflows/agent-lint.yml#L76), and
[deploy.yml:54](../.github/workflows/deploy.yml#L54) are about
`npm install -g <pkg>` invocations that resolve to *whatever was
latest at job-start time*. Pin to a specific published version.

**Fix template:**

```yaml
# BEFORE
- run: npm install -g markdownlint-cli

# AFTER
- run: npm install -g markdownlint-cli@0.48.0
```

How to resolve the current stable version:

```powershell
npm view <package> version
```

#### 1b. Pin every third-party action `uses:` line to a commit SHA

Floating tags (`@v4`, `@main`) let an upstream action publish
malicious code that your workflow then runs with the repo's
`GITHUB_TOKEN` privileges. Even though SonarCloud only currently
flags the npm cases above, the same S6321 rule applies to action
`uses:` lines and pinning them is good defense in depth (and
matches the GitHub OpenSSF Scorecard rule).

**Fix template:**

```yaml
# BEFORE
- uses: actions/checkout@v4

# AFTER  (pin to the immutable commit SHA + keep the human-readable tag in a comment)
- uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683   # v4.2.2
```

How to resolve the SHA for a tag:

```powershell
$r = Invoke-RestMethod -Uri "https://api.github.com/repos/<owner>/<repo>/git/ref/tags/<tag>"
if ($r.object.type -eq 'tag') {
    # Annotated tag — dereference once to get the commit SHA
    (Invoke-RestMethod -Uri "https://api.github.com/repos/<owner>/<repo>/git/tags/$($r.object.sha)").object.sha
} else { $r.object.sha }
```

**Files affected** (status as of `chore/sonar-pin-workflow-actions`):

| File | Sub-pattern | Status |
|---|---|---|
| [agent-lint.yml:18](../.github/workflows/agent-lint.yml#L18) | 1a — `markdownlint-cli` npm pin | ✅ pinned to `@0.48.0` |
| [agent-lint.yml:76](../.github/workflows/agent-lint.yml#L76) | 1a — `promptfoo` npm pin | ✅ pinned to `@0.121.11` |
| [agent-lint.yml](../.github/workflows/agent-lint.yml) | 1b — 3× `actions/checkout` + 1× `actions/setup-node` | ✅ SHA-pinned |
| [deploy.yml:54](../.github/workflows/deploy.yml#L54) | 1a — `@railway/cli` npm pin | ✅ pinned to `@4.57.2` |
| [deploy.yml](../.github/workflows/deploy.yml) | 1b — `actions/checkout` | ✅ SHA-pinned |
| [project-quality.yml](../.github/workflows/project-quality.yml) | 1b — `checkout` + `hadolint-action` + `action-yamllint` | ✅ SHA-pinned |

> **Exception policy:** GitHub-owned actions (`actions/*`,
> `github/codeql-*`) are *de facto* trusted but pinning is still
> recommended. Third-party actions (Docker Hub, marketplace) MUST be
> SHA-pinned — no exceptions.

### Pattern 2 — Move workflow-level read permissions to job level

**Why:** Workflow-level permissions apply to every job in the file —
including jobs that don't need read access. Sonar's principle: least
privilege per job. Refers to GitHub Actions' `permissions:` block.

**Fix template:**

```yaml
# BEFORE
permissions:
  contents: read     # applies to ALL jobs below

jobs:
  build: ...
  lint: ...

# AFTER
# (no top-level permissions block; each job declares its own)
jobs:
  build:
    permissions:
      contents: read
    ...
  lint:
    permissions:
      contents: read
    ...
```

**Files to fix:**
- [.github/workflows/project-quality.yml:10](../.github/workflows/project-quality.yml#L10) — 5 min

---

## Code Smells — High-leverage patterns

These are the patterns that cover the most issues. Tackle them first.

### Pattern 3 — Cognitive Complexity > 15

**Why:** SonarQube's "Cognitive Complexity" metric counts nested
conditionals, loops, and short-circuit operators. Anything > 15 is hard
to test, hard to review, and a magnet for bugs.

**Fix template:**

```java
// BEFORE — Cognitive Complexity = 19 (one method doing five things)
public DischargeSummary build(Encounter e, Patient p) {
    DischargeSummary s = new DischargeSummary();
    if (e != null && e.getStatus() == FINALIZED) {
        for (Prescription rx : e.getPrescriptions()) {
            if (rx.isActive() && !rx.isVoided()) {
                if (p.allergies() != null) {
                    for (Allergy a : p.allergies()) {
                        if (a.matches(rx.getDrug())) {
                            s.addWarning("allergy", rx);
                        }
                    }
                }
            }
        }
    }
    return s;
}

// AFTER — broken into small private helpers each ≤ 5
public DischargeSummary build(Encounter e, Patient p) {
    DischargeSummary s = new DischargeSummary();
    if (e == null || e.getStatus() != FINALIZED) return s;
    activePrescriptionsOf(e).forEach(rx -> addAllergyWarnings(s, rx, p));
    return s;
}

private Stream<Prescription> activePrescriptionsOf(Encounter e) {
    return e.getPrescriptions().stream().filter(Prescription::isActive).filter(rx -> !rx.isVoided());
}

private void addAllergyWarnings(DischargeSummary s, Prescription rx, Patient p) {
    List<Allergy> allergies = p.allergies();
    if (allergies == null) return;
    allergies.stream().filter(a -> a.matches(rx.getDrug())).forEach(a -> s.addWarning("allergy", rx));
}
```

**Heuristics that almost always reduce cognitive complexity:**
- *Guard clauses* — early `return` on the negative case
- *Extract method* — a nested loop with a clear name (`activePrescriptionsOf`)
  costs 0 cognitive points compared to inline iteration
- *Stream pipelines* (`.filter().forEach()`) cost less than `for` + `if`
- *Polymorphism* — replace `switch (status)` with strategy objects when
  there are > 3 branches

**Files to fix (sorted by score, highest first):**

| File | Method @ line | Score → Goal | Effort |
|---|---|---|---|
| [PatientSnapshotServiceImpl.java:52](../hospital-core/src/main/java/com/example/hms/service/PatientSnapshotServiceImpl.java#L52) | — | **65 → 15** | 55 min |
| [PatientPortalServiceImpl.java:892](../hospital-core/src/main/java/com/example/hms/service/impl/PatientPortalServiceImpl.java#L892) | — | 40 → 15 (+ Brain Method) | 30 min |
| [ResultReviewServiceImpl.java:111](../hospital-core/src/main/java/com/example/hms/service/ResultReviewServiceImpl.java#L111) | — | 29 → 15 | 19 min |
| [UserServiceImpl.java:258](../hospital-core/src/main/java/com/example/hms/service/UserServiceImpl.java#L258) | — | 28 → 15 | 18 min |
| [ChatMessageServiceImpl.java:121](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L121) | — | 24 → 15 | 14 min |
| [AuthController.java:218](../hospital-core/src/main/java/com/example/hms/controller/AuthController.java#L218) | — | 23 → 15 (+ Brain Method) | 13 min |
| [ResultReviewServiceImpl.java:57](../hospital-core/src/main/java/com/example/hms/service/ResultReviewServiceImpl.java#L57) | — | 21 → 15 | 11 min |
| [AuditEventLogServiceImpl.java:120](../hospital-core/src/main/java/com/example/hms/service/AuditEventLogServiceImpl.java#L120) | — | 19 → 15 | 9 min |
| [DischargeSummaryServiceImpl.java:567](../hospital-core/src/main/java/com/example/hms/service/DischargeSummaryServiceImpl.java#L567) | — | 19 → 15 | 9 min |
| [ChartReviewServiceImpl.java:204](../hospital-core/src/main/java/com/example/hms/service/impl/ChartReviewServiceImpl.java#L204) | — | 19 → 15 | 9 min |
| [AppointmentServiceImpl.java:998](../hospital-core/src/main/java/com/example/hms/service/AppointmentServiceImpl.java#L998) | — | 18 → 15 | 8 min |
| [DepartmentServiceImpl.java:188](../hospital-core/src/main/java/com/example/hms/service/DepartmentServiceImpl.java#L188) | — | 17 → 15 | 7 min |
| [MedicationAllergyCdsService.java:92](../hospital-core/src/main/java/com/example/hms/cdshooks/service/MedicationAllergyCdsService.java#L92) | — | 17 → 15 | 7 min |
| [PatientDocumentMapper.java:11](../hospital-core/src/main/java/com/example/hms/mapper/PatientDocumentMapper.java#L11) | — | 16 → 15 | 6 min |
| [FileUploadService.java:476](../hospital-core/src/main/java/com/example/hms/service/FileUploadService.java#L476) | — | 16 → 15 | 6 min |

> **Brain Method** is a related Sonar pattern triggered when a single
> method exceeds *multiple* limits at once (LOC, cyclomatic complexity,
> nesting, local variable count). The two methods above
> (`AuthController:218`, `PatientPortalServiceImpl:892`) trip both
> Cognitive Complexity and Brain Method — fixing the cognitive
> complexity usually fixes the Brain Method too because the extracted
> helpers absorb the variable count.

### Pattern 4 — Loop with more than one `break` / `continue`

**Why:** Multiple jump statements in one loop signal that the loop is
doing two things at once. Extract or refactor.

**Fix template:**

```java
// BEFORE — 3 continues + 1 break
for (Order o : orders) {
    if (o.getStatus() != ACTIVE) continue;
    if (o.getPatient() == null) continue;
    if (alreadySeen.contains(o.getId())) continue;
    if (o.getPriority() > MAX_PRIORITY) break;
    process(o);
}

// AFTER — guard logic moved into a Stream filter; loop has zero jumps
orders.stream()
      .filter(o -> o.getStatus() == ACTIVE)
      .filter(o -> o.getPatient() != null)
      .filter(o -> !alreadySeen.contains(o.getId()))
      .takeWhile(o -> o.getPriority() <= MAX_PRIORITY)   // Java 9+: replaces the break
      .forEach(this::process);
```

`takeWhile` is the idiomatic replacement for *"break when condition met"*
in a Java 9+ stream pipeline.

**Files to fix:**
- [SepsisQsofaProtocolRule.java:83](../hospital-core/src/main/java/com/example/hms/cdshooks/bpa/SepsisQsofaProtocolRule.java#L83) — 20 min
- [CdsHookContext.java:55](../hospital-core/src/main/java/com/example/hms/cdshooks/service/CdsHookContext.java#L55) — 40 min
- [MedicationAllergyCdsService.java:104](../hospital-core/src/main/java/com/example/hms/cdshooks/service/MedicationAllergyCdsService.java#L104) — 20 min
- [MedicationPrescribeRulesCdsService.java:90](../hospital-core/src/main/java/com/example/hms/cdshooks/service/MedicationPrescribeRulesCdsService.java#L90) — 20 min
- [RxNormCodingExtractor.java:40](../hospital-core/src/main/java/com/example/hms/cdshooks/terminology/RxNormCodingExtractor.java#L40) — 40 min
- [DoctorWorklistServiceImpl.java:165, 181, 197](../hospital-core/src/main/java/com/example/hms/service/DoctorWorklistServiceImpl.java#L165) — 60 min (3 loops)
- [ResultReviewServiceImpl.java:71](../hospital-core/src/main/java/com/example/hms/service/ResultReviewServiceImpl.java#L71) — 20 min

### Pattern 5 — Duplicated string literals — extract to constants

**Why:** The same literal appearing 3 + times is the strongest single
signal in this report. Most occurrences are role names, status enum
strings, and i18n keys. A literal typo on the 4th occurrence becomes a
latent bug.

**Fix template:**

```java
// BEFORE
@PreAuthorize("hasRole('ROLE_SUPER_ADMIN')")  // duplicated 5x in this file
public ResponseEntity<Encounter> view(...) {...}

// AFTER — declare once, reuse everywhere
public final class SecurityConstants {
    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    // ... existing role constants ...
}

@PreAuthorize("hasRole('" + SecurityConstants.ROLE_SUPER_ADMIN + "')")
public ResponseEntity<Encounter> view(...) {...}
```

For **i18n keys** like `"laborder.notfound"`, the constant belongs in
the service's `MessageKeys` inner class:

```java
private static final class MessageKeys {
    static final String LAB_ORDER_NOT_FOUND = "laborder.notfound";
    private MessageKeys() {}
}
```

> The HMS codebase ALREADY has [`SecurityConstants.java`](../hospital-core/src/main/java/com/example/hms/config/SecurityConstants.java)
> with the 20 role constants. **Use it.** The literals below are all
> places someone forgot to import it.

**Files to fix — Role-name literals (single PR — touches many call sites
but is mechanical):**

| File | Literal | Count | Replace with |
|---|---|---|---|
| [EncounterController.java:84](../hospital-core/src/main/java/com/example/hms/controller/EncounterController.java#L84) | `"ROLE_SUPER_ADMIN"` | 5 | `SecurityConstants.ROLE_SUPER_ADMIN` |
| [ChatMessageServiceImpl.java:59](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L59) | `"ROLE_HOSPITAL_ADMIN"` | 8 | constant |
| [ChatMessageServiceImpl.java:61](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L61) | `"ROLE_MIDWIFE"` | 9 | constant |
| [ChatMessageServiceImpl.java:61](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L61) | `"ROLE_DOCTOR"` | 9 | constant |
| [ChatMessageServiceImpl.java:61](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L61) | `"ROLE_NURSE"` | 9 | constant |
| [ChatMessageServiceImpl.java:62](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L62) | `"ROLE_RECEPTIONIST"` | 8 | constant |
| [ChatMessageServiceImpl.java:62](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L62) | `"ROLE_LAB_SCIENTIST"` | 8 | constant |
| [ChatMessageServiceImpl.java:62](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L62) | `"ROLE_STAFF"` | 8 | constant |
| [ChatMessageServiceImpl.java:63](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L63) | `"ROLE_PATIENT"` | 5 | constant |
| [UserServiceImpl.java:469](../hospital-core/src/main/java/com/example/hms/service/UserServiceImpl.java#L469) | `"ROLE_"` | 3 | `SecurityConstants.ROLE_PREFIX` |

**Files to fix — Status enum literals (a single dedicated PR per service):**

| File | Literal | Count |
|---|---|---|
| [DoctorWorklistServiceImpl.java:185](../hospital-core/src/main/java/com/example/hms/service/DoctorWorklistServiceImpl.java#L185) | `"SCHEDULED"` | 4 |
| [DoctorWorklistServiceImpl.java:226](../hospital-core/src/main/java/com/example/hms/service/DoctorWorklistServiceImpl.java#L226) | `"ROUTINE"` | 4 |
| [DoctorWorklistServiceImpl.java:325](../hospital-core/src/main/java/com/example/hms/service/DoctorWorklistServiceImpl.java#L325) | `"IN_PROGRESS"` | 5 |
| [DoctorWorklistServiceImpl.java:328](../hospital-core/src/main/java/com/example/hms/service/DoctorWorklistServiceImpl.java#L328) | `"COMPLETED"` | 3 |
| [ClinicalDashboardServiceImpl.java:264](../hospital-core/src/main/java/com/example/hms/service/ClinicalDashboardServiceImpl.java#L264) | `"stable"` | 4 |
| [ResultReviewServiceImpl.java:223](../hospital-core/src/main/java/com/example/hms/service/ResultReviewServiceImpl.java#L223) | `"CRITICAL"` | 3 |

> Prefer the matching `enum` (`AppointmentStatus`, `Priority`, `Severity`)
> over a string constant where one exists. If no enum exists, add one
> in `com.example.hms.enums` and migrate.

**Files to fix — i18n message-key literals:**

| File | Literal | Count |
|---|---|---|
| [LabOrderServiceImpl.java:78](../hospital-core/src/main/java/com/example/hms/service/LabOrderServiceImpl.java#L78) | `"laborder.notfound"` | 7 |
| [PrescriptionServiceImpl.java:101](../hospital-core/src/main/java/com/example/hms/service/PrescriptionServiceImpl.java#L101) | `"prescription.notfound"` | 5 |
| [InvoiceItemServiceImpl.java:45](../hospital-core/src/main/java/com/example/hms/service/InvoiceItemServiceImpl.java#L45) | `"billinginvoice.notfound"` | 4 |
| [StaffServiceImpl.java:275](../hospital-core/src/main/java/com/example/hms/service/StaffServiceImpl.java#L275) | `"staff.notFound"` | 3 |
| [TreatmentServiceImpl.java:91](../hospital-core/src/main/java/com/example/hms/service/TreatmentServiceImpl.java#L91) | `"treatment.notFound"` | 3 |
| [AppointmentServiceImpl.java:71](../hospital-core/src/main/java/com/example/hms/service/AppointmentServiceImpl.java#L71) | `"Patient not found for username: "` | 3 |

**Files to fix — Misc duplications:**

| File | Literal | Count |
|---|---|---|
| [SecurityConfig.java:253](../hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java#L253) | (use existing `API_ME_PATIENT_PATTERN`) | 2 |
| [SecurityConfig.java:410](../hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java#L410) | `"/departments/**"` | 5 |
| [SecurityConfig.java:434](../hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java#L434) | `"/billing-invoices/**"` | 4 |
| [SecurityConfig.java:452](../hospital-core/src/main/java/com/example/hms/config/SecurityConfig.java#L452) | `"/invoice-items/**"` | 4 |
| [PrescriptionMapper.java:33](../hospital-core/src/main/java/com/example/hms/mapper/PrescriptionMapper.java#L33) | `"Prescription"` | 4 |
| [ConsultationServiceImpl.java:626](../hospital-core/src/main/java/com/example/hms/service/impl/ConsultationServiceImpl.java#L626) | `"Consultation"` | 5 |
| [EmailServiceImpl.java:275](../hospital-core/src/main/java/com/example/hms/service/EmailServiceImpl.java#L275) | `"<div style=\"padding:36px 40px;\">"` | 3 |
| [FileUploadService.java:64](../hospital-core/src/main/java/com/example/hms/service/FileUploadService.java#L64) | `".jpeg"` | 3 |

---

## Code Smells — Spring / framework patterns

### Pattern 6 — Self-invoked `@Transactional` via `this.foo()` is silently broken

**Why:** Spring's transaction proxy is bypassed when a method calls
another method on `this`. The inner method's `@Transactional` annotation
is ignored — the call runs in whatever transactional context the outer
caller had, or none at all. Sonar reports as
*"Call transactional methods via an injected dependency instead of
directly via 'this'."*

**Fix template:**

Option A — *Refactor so the inner method becomes the outer entry point.*
Often the simplest fix.

Option B — *Inject a self-proxy reference* (most common HMS pattern):

```java
// BEFORE
@Service
@RequiredArgsConstructor
public class PatientServiceImpl implements PatientService {

    @Transactional
    public void registerNewPatient(PatientDto dto) {
        // ...
        this.createInitialEncounter(patientId);   // ❌ @Transactional ignored
    }

    @Transactional(propagation = REQUIRES_NEW)
    public void createInitialEncounter(UUID patientId) { ... }
}

// AFTER — inject "self" via @Lazy to break the cycle
@Service
public class PatientServiceImpl implements PatientService {

    private final PatientService self;        // ← injected, goes through the proxy

    public PatientServiceImpl(@Lazy PatientService self, ...) {
        this.self = self;
    }

    @Transactional
    public void registerNewPatient(PatientDto dto) {
        // ...
        self.createInitialEncounter(patientId);   // ✓ proxy intercepts
    }

    @Transactional(propagation = REQUIRES_NEW)
    public void createInitialEncounter(UUID patientId) { ... }
}
```

**Files to fix:**
- [PatientServiceImpl.java:504](../hospital-core/src/main/java/com/example/hms/service/PatientServiceImpl.java#L504)
- [TreatmentPlanServiceImpl.java:147](../hospital-core/src/main/java/com/example/hms/service/TreatmentPlanServiceImpl.java#L147)
- [AdmissionServiceImpl.java:274](../hospital-core/src/main/java/com/example/hms/service/impl/AdmissionServiceImpl.java#L274)
- [ConsultationServiceImpl.java:173](../hospital-core/src/main/java/com/example/hms/service/impl/ConsultationServiceImpl.java#L173)
- [ImagingOrderServiceImpl.java:201](../hospital-core/src/main/java/com/example/hms/service/impl/ImagingOrderServiceImpl.java#L201)

> ⚠️ **Add a test that exercises the transactional boundary.** A
> common failure mode of this fix is "the proxy now exists but the
> inner method's propagation level was misconfigured the whole time,
> nobody noticed because `this` was bypassing it." Write a test that
> deliberately fails the inner method and verifies the outer
> transaction rolled back as expected.

### Pattern 7 — Constructor parameter overload (> 7)

**Why:** A constructor with 21 dependencies is a layering smell — the
class is doing too much. Sonar flags > 7.

**Affected:**
- [AuthController.java:123](../hospital-core/src/main/java/com/example/hms/controller/AuthController.java#L123) — **21 parameters**, 20 min

**Fix options (pick one — discuss in the PR):**
1. **Extract collaborators into a helper service.** E.g. group every
   parameter dealing with multi-factor auth into an `MfaSupport`
   component, every parameter dealing with bootstrap into
   `BootstrapSupport`. Aim for 5–6 top-level deps.
2. **Split the controller** by responsibility (e.g. `AuthController`
   for sign-in, `AccountSetupController` for first-login flow,
   `MfaController` for enrolment / challenge — some of this split
   already exists; this PR finishes it).

---

## Code Smells — Java idiom modernization

These are the "1–5 min each" fixes — perfect for a single sweep PR.

### Pattern 8 — Nested ternaries

```java
// BEFORE
String severity = score > 90 ? "CRITICAL"
                : score > 60 ? "MAJOR"
                : score > 30 ? "MINOR" : "INFO";

// AFTER — pull each branch out
String severity;
if (score > 90)      severity = "CRITICAL";
else if (score > 60) severity = "MAJOR";
else if (score > 30) severity = "MINOR";
else                  severity = "INFO";
// or extract to a private helper String severityFor(int score)
```

- [AppointmentServiceImpl.java:1002, 1005, 1007](../hospital-core/src/main/java/com/example/hms/service/AppointmentServiceImpl.java#L1002) — 3 occurrences, 5 min each

### Pattern 9 — Lambda → method reference

```java
// BEFORE
.filter(x -> x != null)
.map(s -> s.getUser())

// AFTER
.filter(Objects::nonNull)
.map(Staff::getUser)
```

- [AuthBootstrapServiceImpl.java:68](../hospital-core/src/main/java/com/example/hms/service/AuthBootstrapServiceImpl.java#L68) — `Objects::nonNull`
- [ChatMessageServiceImpl.java:138](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L138) — `Staff::getUser`

### Pattern 10 — Java 21 idioms

`Math.clamp` replaces `Math.min(Math.max(v, lo), hi)`:

```java
// BEFORE
int size = Math.min(Math.max(requested, 1), 100);

// AFTER (Java 21+)
int size = (int) Math.clamp(requested, 1, 100);
```

Pattern-match guard replaces `if` chains inside a switch:

```java
// BEFORE (inside a switch-case)
case TextPhrase t -> {
    if (t.getLanguage() == FR) ... else if (t.getLanguage() == ES) ...
}

// AFTER
case TextPhrase t when t.getLanguage() == FR -> ...
case TextPhrase t when t.getLanguage() == ES -> ...
```

- [AppointmentController.java:181](../hospital-core/src/main/java/com/example/hms/controller/AppointmentController.java#L181) — `Math.clamp`
- [SmartPhraseServiceImpl.java:354, 364](../hospital-core/src/main/java/com/example/hms/service/SmartPhraseServiceImpl.java#L354) — pattern-match guards (2 occurrences)

### Pattern 11 — `Optional` / null hygiene

Return an empty collection, never `null`. Sonar correctly flags
`return null;` from a method whose declared return type is `List<X>`.

```java
// BEFORE
public List<Feature> activeFeatures() {
    if (subscription == null) return null;
    ...
}

// AFTER
public List<Feature> activeFeatures() {
    if (subscription == null) return List.of();
    ...
}
```

- [SubscriptionFeatureGateServiceImpl.java:79, 84](../hospital-core/src/main/java/com/example/hms/service/impl/SubscriptionFeatureGateServiceImpl.java#L79) — 2 occurrences, 30 min each (callers may rely on the `null`)

### Pattern 12 — Single-return refactors

```java
// BEFORE
if (encounter == null) {
    return false;
} else {
    return encounter.getStatus() == ACTIVE;
}

// AFTER
return encounter != null && encounter.getStatus() == ACTIVE;
```

- [PatientTrackerServiceImpl.java:124](../hospital-core/src/main/java/com/example/hms/service/PatientTrackerServiceImpl.java#L124) — 2 min

### Pattern 13 — Merge nested `if` statements

```java
// BEFORE
if (a) {
    if (b) {
        doX();
    }
}

// AFTER
if (a && b) {
    doX();
}
```

- [DischargeSummaryServiceImpl.java:578](../hospital-core/src/main/java/com/example/hms/service/DischargeSummaryServiceImpl.java#L578)
- [PatientServiceImpl.java:221](../hospital-core/src/main/java/com/example/hms/service/PatientServiceImpl.java#L221)

### Pattern 14 — Useless curly braces

```java
// BEFORE
if (predicate) {
    return value;
}

// AFTER (Sonar's preference for single-statement if; you may prefer
//        to keep braces for safety — this is style, low priority)
if (predicate) return value;
```

- [ResultReviewServiceImpl.java:143, 180](../hospital-core/src/main/java/com/example/hms/service/ResultReviewServiceImpl.java#L143) — 2 occurrences

> ⚠️ **Local style override allowed.** Most teams (including ours)
> prefer to keep braces even for single-statement `if` to prevent
> "the goto fail bug". If you don't want this fix, mark the issue as
> "won't fix" in Sonar with a comment pointing at the team style
> guide. Document the decision in the same PR.

---

## Code Smells — Housekeeping

### Pattern 15 — Unused imports

Trivial, 1 min each. Use IDE "optimize imports" on the whole module.

- [BillingInvoiceController.java:42](../hospital-core/src/main/java/com/example/hms/controller/BillingInvoiceController.java#L42) — `SecurityConstants`
- [support/ControllerAuthUtils.java:4](../hospital-core/src/main/java/com/example/hms/controller/support/ControllerAuthUtils.java#L4) — `UserRoleHospitalAssignment`
- [pharmacy/PrescriptionRoutingMapper.java:4](../hospital-core/src/main/java/com/example/hms/mapper/pharmacy/PrescriptionRoutingMapper.java#L4) — `RoutingType`

### Pattern 16 — Commented-out code blocks

Remove. Git history is the place for dead code. If the block is
*intentional* commentary on why something is missing, convert it to
prose:

```java
// BEFORE
// public List<User> getAllUsers() {
//     return userRepository.findAll();  // disabled pending RBAC review
// }

// AFTER
// getAllUsers() removed pending RBAC review — see HMS-1234.
```

- [FeatureFlagController.java:60](../hospital-core/src/main/java/com/example/hms/controller/FeatureFlagController.java#L60)
- [UserRepository.java:87](../hospital-core/src/main/java/com/example/hms/repository/UserRepository.java#L87)
- [JwtAuthenticationFilter.java:133](../hospital-core/src/main/java/com/example/hms/security/JwtAuthenticationFilter.java#L133)
- [EncounterServiceImpl.java:401](../hospital-core/src/main/java/com/example/hms/service/EncounterServiceImpl.java#L401)

### Pattern 17 — Hard-coded URIs / path delimiters

Move to configuration so they can be overridden per environment.

```java
// BEFORE
private static final String SPLUNK_HEC_URL = "https://hec.example.com:8088/services/collector";

// AFTER — Spring @Value with sane default
@Value("${hms.splunk.hec.url:https://hec.example.com:8088/services/collector}")
private String splunkHecUrl;
```

Hard-coded `/` or `\` in a file path → use `File.separator` or `Path.of`.

- [SplunkHecAppender.java:45](../hospital-core/src/main/java/com/example/hms/logging/SplunkHecAppender.java#L45)
- [AppointmentServiceImpl.java:95, 96](../hospital-core/src/main/java/com/example/hms/service/AppointmentServiceImpl.java#L95) — 2 occurrences
- [PatientPortalServiceImpl.java:173, 174](../hospital-core/src/main/java/com/example/hms/service/impl/PatientPortalServiceImpl.java#L173) — 2 occurrences
- [FileUploadService.java:356](../hospital-core/src/main/java/com/example/hms/service/FileUploadService.java#L356) — `File.separator`

### Pattern 18 — Unused method parameter

If the parameter is part of an interface override, suppress with
`@SuppressWarnings("unused")` and a comment. If it isn't, delete it.

- [ChatMessageServiceImpl.java:316](../hospital-core/src/main/java/com/example/hms/service/ChatMessageServiceImpl.java#L316) — `sender` parameter

### Pattern 19 — Missing `@deprecated` Javadoc

When you annotate a method `@Deprecated`, add a matching `@deprecated`
Javadoc tag explaining what to use instead and when removal is planned:

```java
/**
 * Returns the legacy single-role-per-user mapping.
 *
 * @deprecated since 0.9 — use {@link #findRolesByUser(UUID)} which
 *             returns the multi-role list. Removal target: v1.1.
 */
@Deprecated(forRemoval = true)
public Role findSingleRole(UUID userId) { ... }
```

- [UserRoleHospitalAssignmentController.java:206](../hospital-core/src/main/java/com/example/hms/controller/UserRoleHospitalAssignmentController.java#L206)

### Pattern 20 — `final` field should be `static` too

If a `final` field's value is a compile-time constant and never
references instance state, make it `static final`:

```java
// BEFORE
private final ZoneId UTC = ZoneId.of("UTC");

// AFTER
private static final ZoneId UTC = ZoneId.of("UTC");
```

- [OrganizationLifecycleStatusServiceImpl.java:38](../hospital-core/src/main/java/com/example/hms/service/impl/OrganizationLifecycleStatusServiceImpl.java#L38)

### Pattern 21 — Conditional method invocation

Avoid calling expensive methods inside a logger or assertion that may
be disabled:

```java
// BEFORE
log.debug("computed dispense plan: " + buildExpensiveSummary());

// AFTER — only build the summary if debug is enabled
if (log.isDebugEnabled()) {
    log.debug("computed dispense plan: {}", buildExpensiveSummary());
}
```

- [PrescriptionServiceImpl.java:442](../hospital-core/src/main/java/com/example/hms/service/PrescriptionServiceImpl.java#L442)

### Pattern 22 — Outstanding TODO / deprecation removal

Either complete the work or remove the comment. If keeping the TODO,
link it to an issue (`// TODO(HMS-1234): remove after Keycloak phase D`).

- [KeycloakHospitalContextFilter.java:79](../hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakHospitalContextFilter.java#L79) — TODO
- [UserRoleHospitalAssignmentService.java:57](../hospital-core/src/main/java/com/example/hms/service/UserRoleHospitalAssignmentService.java#L57) — deprecated-code removal reminder

---

## Suggested PR sequence

To clear the SonarCloud queue without burning out a single contributor,
the recommended order:

| # | PR title | Pattern(s) | Files | Effort |
|---|---|---|---|---|
| 1 | `chore(ci): pin workflow actions to commit SHAs` | 1 | 3 workflow files | 3h |
| 2 | `chore(ci): move workflow-level perms to job level` | 2 | 1 workflow | 5 min |
| 3 | `refactor(security): extract SecurityConfig path constants` | 5 | SecurityConfig.java | 30 min |
| 4 | `refactor(security): use SecurityConstants for role literals` | 5 | EncounterController + ChatMessageServiceImpl + UserServiceImpl | 2h |
| 5 | `refactor(service): extract i18n message-key constants` | 5 | LabOrder + Prescription + Invoice + Staff + Treatment + Appointment | 1h |
| 6 | `refactor(service): extract status enum literals` | 5 | DoctorWorklist + ClinicalDashboard + ResultReview | 1h |
| 7 | `refactor(service): fix @Transactional self-invocation` | 6 | 5 service impls + 5 new transactional tests | 4h |
| 8 | `refactor(auth): split AuthController collaborators` | 7 | AuthController + new MfaSupport / BootstrapSupport | 4h |
| 9 | `refactor(service): reduce cognitive complexity (PatientSnapshot, PatientPortal)` | 3 | 2 worst offenders + tests | 4h |
| 10 | `refactor(service): cognitive complexity sweep (the other 13)` | 3 | Multiple | 2h |
| 11 | `refactor(loops): replace multi-break/continue with stream pipelines` | 4 | 8 files | 3h |
| 12 | `refactor(modernization): nested ternaries / method refs / Math.clamp / pattern guards` | 8, 9, 10 | 7 files | 1h |
| 13 | `chore(java): housekeeping — unused imports, commented code, unused params` | 15, 16, 18 | 12 files | 30 min |
| 14 | `refactor(config): externalize hard-coded URIs / path delimiters` | 17 | 5 files | 2h |
| 15 | `refactor(api): return empty collection (not null) from SubscriptionFeatureGate` | 11 | 1 file + caller test sweep | 1h |
| 16 | `refactor(misc): merge ifs, single-return, deprecated javadoc, static-final` | 12, 13, 19, 20 | 5 files | 30 min |

Total estimated effort: **~30 hours** spread across ~16 PRs. Sonar's
own effort estimates sum to ~25 hours; the gap is review + tests.

## Verification per PR

Every PR in the queue above must show:

1. `npm run lint && npm run format:check && npm run test:headless` — green
2. `./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification` — `BUILD SUCCESSFUL`
3. Coverage on the touched classes — **must not drop**. Sonar's diff
   coverage gate enforces 80% on new/changed lines.
4. The Sonar comment on the PR — issue count must go **down**, not up.
5. No new "Major" or "Critical" issues introduced.

## When NOT to fix a Sonar finding

Some Sonar rules are wrong for this codebase:

- **Useless curly braces (Pattern 14).** We keep braces for single-line
  `if` bodies to prevent goto-fail-style bugs. Mark these as "won't
  fix" with a comment pointing at this doc.
- **Cognitive complexity in CDS-Hooks rule classes** when the
  complexity is a faithful encoding of the published clinical rule
  (e.g. qSOFA scoring). Reducing complexity by extracting one-line
  helpers *increases* indirection without aiding readability. Mark as
  "won't fix" with a link to the source rule (CDC, NICE, etc.).
- **Cognitive complexity in `@Transactional` orchestration methods**
  where the steps are inherently sequential and breaking them up
  would invalidate the transactional boundary. Add a comment, mark
  as "won't fix."

Document every "won't fix" decision in the Sonar UI **with a link
back to this section**.

## See also

- [docs/copilot-review.md](./copilot-review.md) — raw Sonar export this
  doc is derived from
- [docs/ui/accessibility.md](./ui/accessibility.md) — frontend
  equivalent for axe-core / WCAG (row 11 of the roadmap)
- [build.gradle](../build.gradle) — Sonar + JaCoCo configuration
- [.github/workflows/build.yml](../.github/workflows/build.yml) — CI
  workflow that runs Sonar on every push / PR
