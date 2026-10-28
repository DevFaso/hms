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
 */
class ClinicalDocumentAuthorGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final List<String> DOCUMENT_SERVICES = List.of(
        "DischargeSummaryServiceImpl.java",
        "TreatmentPlanServiceImpl.java",
        "impl/MaternalHistoryServiceImpl.java",
        "impl/GeneralReferralServiceImpl.java",
        "DischargeApprovalServiceImpl.java",
        "impl/ConsultationServiceImpl.java");
    private static final Pattern WRITE_METHOD = Pattern.compile(
        "\\n    public [A-Za-z<>.?, ]+ ((?:update|delete|cancel|finalize|amend|withdraw)\\w*)\\(");
    /** Calls that decide who may act on an existing record. */
    private static final Pattern AUTHOR_CHECK = Pattern.compile(
        "CallerIdentity\\.require\\w*\\(|requireAuthor\\(|requireCallerIsReferrer\\(|requireUpdater\\("
            + "|requireRequesterOrSuperAdmin\\(|requireCallerUnlessSuperAdmin\\(");
    /** Writes the whole team may make, and why. */
    private static final Map<String, String> TEAM_OWNED = Map.of(
        "TreatmentPlanServiceImpl.updateFollowUp",
            "a follow-up is done and edited by the staff it is assigned to, not only the plan's author",
        "MaternalHistoryServiceImpl.updateMaternalHistory",
            "an update writes a new version (the previous one is kept), as the midwives and doctors "
                + "following the pregnancy add what they learn");

    @Test
    void everyDocumentWriteChecksWhoActs() throws IOException {
        Map<String, String> unguarded = new TreeMap<>();
        int checked = 0;
        for (String relative : DOCUMENT_SERVICES) {
            Path file = SERVICES.resolve(relative);
            String src = Files.readString(file);
            String service = file.getFileName().toString().replace(".java", "");
            Matcher m = WRITE_METHOD.matcher(src);
            List<Integer> starts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            while (m.find()) {
                starts.add(m.start());
                names.add(m.group(1));
            }
            for (int i = 0; i < names.size(); i++) {
                String key = service + "." + names.get(i);
                checked++;
                if (TEAM_OWNED.containsKey(key)) {
                    continue;
                }
                int end = i + 1 < starts.size() ? starts.get(i + 1) : src.length();
                String body = src.substring(starts.get(i), end);
                if (!AUTHOR_CHECK.matcher(body).find() && !callsCheckedHelper(src, body)) {
                    unguarded.put(key, "no author check");
                }
            }
        }
        assertThat(checked).as("document write methods found").isGreaterThanOrEqualTo(10);
        assertThat(unguarded).as("document writes open to any colleague").isEmpty();
    }

    /**
     * A write may delegate the check to a private helper of the same class
     * (cancelConsultation, for one); follow one level of private calls.
     */
    private static boolean callsCheckedHelper(String src, String body) {
        Matcher call = Pattern.compile("\\b(require\\w+)\\(").matcher(body);
        while (call.find()) {
            Matcher def = Pattern.compile("private [\\w<>.]+ " + call.group(1) + "\\([^)]*\\)\\s*\\{").matcher(src);
            if (def.find()) {
                int open = def.end();
                String helper = src.substring(open, Math.min(src.length(), open + 1500));
                if (AUTHOR_CHECK.matcher(helper).find() || helper.contains("getCurrentUserId()")) {
                    return true;
                }
            }
        }
        return false;
    }
}
