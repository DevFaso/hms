package com.example.hms.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D955: every step of a referral checks WHO acts, so the clinician who sent
 * it can never also be the one who accepts, starts or completes it. The sender
 * steps (submit, cancel) call {@code requireCallerIsReferrer}; every receiving
 * step calls {@code requireReceivingSide}, which refuses the referrer. A new
 * public step method that changes a referral without either fails here, the
 * way {@link ConsultationTransitionActorGuardTest} guards consultations.
 */
class ReferralStepActorGuardTest {

    private static final Path SOURCE = Path.of(
        "src/main/java/com/example/hms/service/impl/GeneralReferralServiceImpl.java");
    private static final Pattern STEP = Pattern.compile("\\n    public \\S+ (\\w+Referral)\\(");
    private static final Pattern ACTOR_CHECK = Pattern.compile(
        "\\n        (?:UUID \\w+ = )?require(CallerIsReferrer|ReceivingSide)\\(");

    @Test
    void everyReferralStepChecksTheActor() throws Exception {
        String src = Files.readString(SOURCE);
        Matcher m = STEP.matcher(src);
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            names.add(m.group(1));
        }
        List<String> unguarded = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            // createReferral checks the caller through requireReferrerIsCaller; getReferral reads.
            if (name.equals("createReferral") || name.equals("getReferral")) {
                continue;
            }
            int end = i + 1 < starts.size() ? starts.get(i + 1) : src.length();
            String body = src.substring(starts.get(i), end);
            checked++;
            if (!ACTOR_CHECK.matcher(body).find()) {
                unguarded.add(name);
            }
        }
        assertThat(checked).as("referral steps found").isGreaterThanOrEqualTo(7);
        assertThat(unguarded).as("referral steps without an actor check").isEmpty();

        // The receiving-side check itself refuses the referrer.
        int at = src.indexOf("private UUID requireReceivingSide(");
        String helper = src.substring(at, src.indexOf("\n    }\n", at));
        // The refusal names the not-referrer key, through its constant (whose value is checked too).
        assertThat(helper).as("requireReceivingSide must refuse the referrer (QA D955)")
            .contains("getReferringProvider()").contains("RECEIVER_NOT_REFERRER");
        assertThat(src).as("the not-referrer constant holds the not-referrer key")
            .contains("RECEIVER_NOT_REFERRER = \"generalReferral.receiver.notReferrer\"");
    }
}
