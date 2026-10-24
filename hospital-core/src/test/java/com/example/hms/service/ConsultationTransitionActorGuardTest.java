package com.example.hms.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D704/D735: every consultation state transition must check WHO acts, not
 * only the controller's role list. /assign was guarded by role alone, so any
 * doctor assigned the consult to himself and completed it. A new transition
 * method that writes the status without an actor check fails here.
 */
class ConsultationTransitionActorGuardTest {

    private static final Path SOURCE = Path.of(
        "src/main/java/com/example/hms/service/impl/ConsultationServiceImpl.java");

    /** Public methods that call setStatus(...) — each must call an actor check. */
    @Test
    void everyStatusWritingMethodChecksTheActor() throws Exception {
        String src = Files.readString(SOURCE);
        Matcher m = Pattern.compile("\\n    public \\S+ (\\w+)\\(").matcher(src);
        List<int[]> starts = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        while (m.find()) {
            starts.add(new int[]{m.start()});
            names.add(m.group(1));
        }
        List<String> unguarded = new java.util.ArrayList<>();
        int checked = 0;
        for (int i = 0; i < names.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : src.length();
            String body = src.substring(starts.get(i)[0], end);
            if (!body.contains(".setStatus(") || "createConsultation".equals(names.get(i))) {
                continue;
            }
            checked++;
            // The check must be a top-level statement of the method, not inside
            // an if: a conditional call let /acknowledge on an unassigned consult
            // skip the actor check entirely (self-review of #938).
            if (!Pattern.compile("\n        require(AssignedConsultant|Coordinator|RequesterOrSuperAdmin|Acknowledger|Reassigner|AssignedConsultantOrCoordinator|Updater)\\(")
                    .matcher(body).find()) {
                unguarded.add(names.get(i));
            }
        }
        assertThat(checked).as("transition methods found").isGreaterThanOrEqualTo(9);
        assertThat(unguarded).as("transitions without an actor check").isEmpty();
    }
}
