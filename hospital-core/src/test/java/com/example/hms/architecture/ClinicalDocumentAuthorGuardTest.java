package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D930 guard: a clinical document one clinician wrote is rewritten,
 * deleted, cancelled or finalized by that clinician (or a super-admin in
 * global view), never by any colleague at the same hospital. Any doctor could
 * delete or rewrite another doctor's draft discharge summary because update
 * and delete checked the hospital only; the treatment plan's check could be
 * skipped by sending back its own author; a colleague could hard-delete a
 * maternal history.
 *
 * <p>For each document service below, every public update / delete / cancel
 * / finalize method must either call an author check or be listed in
 * {@link #TEAM_OWNED} with the reason it is the team's to change. A new write
 * method in one of these services fails here until it decides.
 *
 * <p>Round 16 siblings: the class reached clinical orders too (a colleague
 * rewrote an unsigned prescription, signed, revised or voided another
 * clinician's imaging read, cancelled or signed another doctor's imaging
 * order, rewrote or cancelled an ultrasound order, cancelled a midwife's
 * OB/GYN referral or another clinician's transfusion request). Judged team-
 * owned on purpose, and left out of this list: admissions,
 * transfers, isolation precautions, problems, allergies, family and social
 * history, birth plans (the patient's own) and high-risk care plans (the
 * ward or the pregnancy's team acts on them); digital signatures (each is the
 * signer's own row and alters no record); a death record amendment (a
 * reasoned, audited correction).
 */
class ClinicalDocumentAuthorGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final List<String> DOCUMENT_SERVICES = List.of(
        "DischargeSummaryServiceImpl.java",
        "TreatmentPlanServiceImpl.java",
        "impl/MaternalHistoryServiceImpl.java",
        "impl/GeneralReferralServiceImpl.java",
        "DischargeApprovalServiceImpl.java",
        "impl/ConsultationServiceImpl.java",
        // Round 16 siblings (QA D930 class): clinical orders and drafts.
        "PrescriptionServiceImpl.java",
        "LabOrderServiceImpl.java",
        "impl/ImagingReportServiceImpl.java",
        "impl/ImagingOrderServiceImpl.java",
        "impl/UltrasoundServiceImpl.java",
        "impl/ObgynReferralServiceImpl.java",
        "impl/TransfusionServiceImpl.java",
        // Product decision 2026-10-28: a procedure order belongs to the
        // clinician who placed it (it was listed as team-owned until then).
        // Ending it (POST /cancel, or CANCELLED sent through the update) is
        // the ordering clinician's, or a physician's for a nurse's or
        // midwife's order; recording its execution stays with the team.
        "impl/ProcedureOrderServiceImpl.java");
    /** Round 16 siblings: sign, void and an order's signature capture are writes on the author's record too. */
    private static final Pattern WRITE_METHOD = Pattern.compile(
        "\\n    public [A-Za-z<>.?, ]+ ((?:update|delete|cancel|finalize|amend|withdraw|sign|void"
            + "|capture\\w*Signature)\\w*)\\(");
    /**
     * Calls that decide who may act on an existing record (review of #971:
     * {@code CallerIdentity.requireAtHospital} is a hospital check, not an
     * author check, so it does not count).
     */
    private static final Pattern AUTHOR_CHECK = Pattern.compile(
        // QA D1112: requireAuthor / requireAuthorUnlessSuperAdmin are the authorship checks with their own key.
        "CallerIdentity\\.(?:requireCaller|requireCallerUnlessSuperAdmin|isCaller|requireAuthorUnlessSuperAdmin)\\(|requireAuthor\\("
            + "|requireCallerIsReferrer\\(|requireUpdater\\(|requireRequesterOrSuperAdmin\\("
            // Round 16 siblings: each helper below compares the caller with the
            // record's author (prescriber, ordering clinician, interpreting
            // provider, referring midwife) and documents its own declared
            // exceptions (the physician who signs a nurse's draft, a lab
            // manager, a radiologist, a super-admin in global view).
            + "|requireDraftAuthor\\(|resolveSigningPrescriber\\(|requireMayWithdrawSigned\\("
            + "|requireReportAuthor\\(|requireEditableBy\\(|requireCancelAuthor\\("
            + "|requireSignatureAuthor\\(|requireOrderAuthor\\(|requireReferringMidwife\\(");
    /** Writes the whole team may make (or a supervisor role decides), and why. */
    private static final Map<String, String> TEAM_OWNED = Map.ofEntries(
        Map.entry("TreatmentPlanServiceImpl.updateFollowUp",
            "a follow-up is done and edited by the staff it is assigned to, not only the plan's author"),
        Map.entry("MaternalHistoryServiceImpl.updateMaternalHistory",
            "an update writes a new version (the previous one is kept), as the midwives and doctors "
                + "following the pregnancy add what they learn"),
        Map.entry("LabOrderServiceImpl.updateLabOrder",
            "an edit keeps the ordering clinician (a nurse corrects a doctor's order, keptOrderingStaff); "
                + "naming another clinician goes through the placing rule, which requires the caller"),
        Map.entry("LabOrderServiceImpl.voidLabOrder",
            "supervisor path: entered-in-error is a lab manager's or director's decision "
                + "(requireOrderDecisionAuthority), whoever ordered it"));
    /**
     * Administrative hard deletes no clinician can reach: the controller admits
     * administrators only, so there is no colleague to refuse. Checked below
     * against the controller's {@code @PreAuthorize}: the day a clinical role
     * is admitted, the write must gain an author check.
     */
    private static final Map<String, String> ADMIN_ONLY = Map.of(
        "PrescriptionServiceImpl.deletePrescription", "PrescriptionController.java",
        "LabOrderServiceImpl.deleteLabOrder", "LabOrderController.java");
    private static final Pattern CLINICAL_ROLE = Pattern.compile(
        "DOCTOR|PHYSICIAN|SURGEON|NURSE|MIDWIFE|RADIOLOGIST|LAB_|PHARMACIST|ANESTH");

    @Test
    void adminOnlyDeletesStayAdminOnly() throws IOException {
        for (Map.Entry<String, String> entry : ADMIN_ONLY.entrySet()) {
            String method = entry.getKey().substring(entry.getKey().indexOf('.') + 1);
            String src = Files.readString(Path.of("src/main/java/com/example/hms/controller").resolve(entry.getValue()));
            int call = src.indexOf("." + method + "(");
            assertThat(call).as(entry.getKey() + " is called from " + entry.getValue()).isPositive();
            int annotation = src.lastIndexOf("@PreAuthorize(", call);
            String rule = src.substring(annotation, src.indexOf('\n', annotation));
            assertThat(CLINICAL_ROLE.matcher(rule).find())
                .as(entry.getKey() + " admits a clinical role (" + rule.trim() + "): add an author check")
                .isFalse();
        }
    }

    @Test
    void everyDocumentWriteChecksWhoActs() throws IOException {
        Map<String, String> unguarded = new TreeMap<>();
        java.util.Set<String> found = new java.util.TreeSet<>();
        int checked = 0;
        for (String relative : DOCUMENT_SERVICES) {
            Path file = SERVICES.resolve(relative);
            // Comments blanked, so a Javadoc that names a check is not one.
            String src = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(file));
            String service = file.getFileName().toString().replace(".java", "");
            Matcher m = WRITE_METHOD.matcher(src);
            List<Integer> starts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (m.find()) {
                starts.add(m.end() - 1);
                names.add(m.group(1));
            }
            for (int i = 0; i < names.size(); i++) {
                String key = service + "." + names.get(i);
                checked++;
                found.add(key);
                if (TEAM_OWNED.containsKey(key) || ADMIN_ONLY.containsKey(key)) {
                    continue;
                }
                // Round 16 siblings: the method's own body (balanced braces).
                // The old window ran to the next write method, so a helper's
                // definition below a write (or at the end of the file) counted
                // as the write's check: falsifying an author check went green.
                String body = methodBody(src, starts.get(i));
                if (!AUTHOR_CHECK.matcher(body).find() && !callsCheckedHelper(src, body)) {
                    unguarded.put(key, "no author check");
                }
            }
        }
        assertThat(checked).as("document write methods found").isGreaterThanOrEqualTo(25);
        assertThat(found).as("every team-owned entry names a write that still exists (delete a stale line)")
            .containsAll(TEAM_OWNED.keySet())
            .containsAll(ADMIN_ONLY.keySet());
        assertThat(unguarded).as("document writes open to any colleague").isEmpty();
    }

    /**
     * Review of #976 (QA D930 class): the imaging order checked the cancel
     * author only on the move INTO CANCELLED, but stamped who cancelled it,
     * when and why on every request that said CANCELLED, so a colleague who
     * re-sent CANCELLED rewrote the author's stamp. A cancellation / void /
     * withdrawal stamp keyed on a single status equality (the shape of that
     * bug) is refused anywhere under {@code service/}: the stamp belongs to the
     * transition ({@code requested == X && current != X}), or to a method that
     * has already refused a record in that state. What this scan cannot see,
     * the way back out of CANCELLED, is pinned by the per-service unit tests
     * (ImagingOrderServiceImplTest review976_*).
     */
    @Test
    void cancellationStampIsWrittenOnTheTransitionOnly() throws IOException {
        Pattern ifOpen = Pattern.compile("\\bif\\s*\\(");
        Pattern singleStatusTest = Pattern.compile(
            "^[\\w.()]+\\.getStatus\\(\\)\\s*==\\s*\\w+\\.(?:CANCELLED|CANCELED|VOIDED|VOID|DISCONTINUED"
                + "|WITHDRAWN|REVOKED|ENTERED_IN_ERROR|ERROR)$");
        Pattern stamp = Pattern.compile("\\.set(?:Cancel|Void|Withdraw|Revok|Discontinu)\\w*\\(");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        List<Path> files;
        try (java.util.stream.Stream<Path> walk = Files.walk(SERVICES)) {
            files = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            String src = com.example.hms.testsupport.SourceScan.stripComments(Files.readString(file));
            scanned++;
            Matcher m = ifOpen.matcher(src);
            while (m.find()) {
                int open = m.end() - 1;
                int close = com.example.hms.testsupport.SourceScan.closingParen(src, open);
                String condition = src.substring(open + 1, Math.min(close, src.length())).replaceAll("\\s+", " ").trim();
                if (!singleStatusTest.matcher(condition).matches()) {
                    continue;
                }
                int brace = src.indexOf('{', close);
                if (brace < 0 || !src.substring(close + 1, brace).isBlank()) {
                    continue;
                }
                String body = src.substring(brace, com.example.hms.testsupport.SourceScan.closingBrace(src, brace));
                if (stamp.matcher(body).find()) {
                    offenders.add(SERVICES.relativize(file) + ":" + com.example.hms.testsupport.SourceScan.lineOf(src, open)
                        + " if (" + condition + ")");
                }
            }
        }
        assertThat(scanned).as("service sources scanned").isGreaterThan(100);
        assertThat(offenders).as("cancellation stamps written on every request that carries the status").isEmpty();
    }

    /**
     * A write may delegate the check to a private helper of the same class
     * (cancelConsultation, for one); follow one level of private calls.
     */
    /** The body of the method whose parameter list opens at {@code paren}: from its {@code {} to the matching {@code }}. */
    private static String methodBody(String src, int paren) {
        int open = src.indexOf('{', com.example.hms.testsupport.SourceScan.closingParen(src, paren));
        return src.substring(open, com.example.hms.testsupport.SourceScan.closingBrace(src, open));
    }

    private static boolean callsCheckedHelper(String src, String body) {
        Matcher call = Pattern.compile("\\b(require\\w+)\\(").matcher(body);
        while (call.find()) {
            Matcher def = Pattern.compile("private [\\w<>.]+ " + call.group(1) + "\\([^)]*\\)\\s*\\{").matcher(src);
            if (def.find()) {
                int open = def.end() - 1;
                String helper = src.substring(open, com.example.hms.testsupport.SourceScan.closingBrace(src, open));
                if (AUTHOR_CHECK.matcher(helper).find()) {
                    return true;
                }
            }
        }
        return false;
    }
}
