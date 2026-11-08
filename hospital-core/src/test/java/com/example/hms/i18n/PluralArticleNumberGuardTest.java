package com.example.hms.i18n;

import com.example.hms.config.MessageBundles;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1177 (round 21): "2 cp par prise dépasse les 1 cp de la tranche de
 * poids" / "supera los 1 cp": a plural article in front of a number the
 * caller fills in reads wrong whenever that number is 1. A French or Spanish
 * message puts a plural article (les, des, ces, los, las) before a numeric
 * placeholder only when the number can never be 1 (a constant, a clock time,
 * a limit), and each such key is listed here with why. A new one fails.
 */
class PluralArticleNumberGuardTest {

    private static final Pattern PLURAL_BEFORE_NUMBER =
        Pattern.compile("\\b(les|des|ces|los|las)\\s+(?:[^\\W\\d_]+\\s+)?\\{\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Keys whose number is never 1, with why. */
    private static final Map<String, String> NEVER_ONE = new TreeMap<>(Map.ofEntries(
        Map.entry("portal.booking.outsideHours", "\"a las {0}\": a clock time (Spanish 'las' before an hour)"),
        Map.entry("cds.bpa.pregnancyHtn.reading", "\"a las {2} semanas\": gestational age of a pregnancy reading, counted in weeks (a BPA card, not a count of 1)"),
        Map.entry("cds.duplicate.detail", "DuplicateMedicationOrderRule.LOOKBACK, a constant of 7 days"),
        Map.entry("news2.escalation.high", "News2EscalationService monitoring interval, a constant number of minutes"),
        Map.entry("news2.escalation.medium", "News2EscalationService monitoring interval, a constant number of minutes"),
        Map.entry("prenatal.reminder.default", "\"a las {1}\": a clock time"),
        Map.entry("labor.alert.msg.labor-preterm", "\"a las {0} semanas\": gestational weeks of a birth"),
        Map.entry("labor.alert.msg.labor-weight-implausible", "\"a las {1} semanas\": gestational weeks of a birth"),
        Map.entry("sms.appointment.reminder", "\"a las {1}\": a clock time"),
        Map.entry("sms.waitlist.offer", "\"a las {1}\": a clock time"),
        Map.entry("email.appointment.cancelled.body.intro", "\"a las {3}\": a clock time"),
        Map.entry("email.appointment.completed.body.intro", "\"a las {3}\": a clock time"),
        Map.entry("email.appointment.noshow.body.intro", "\"a las {3}\": a clock time"),
        Map.entry("email.password.changed.body.intro", "\"a las {1}\": a clock time"),
        Map.entry("email.account.restored.body.intro", "\"a las {1}\": a clock time"),
        Map.entry("email.change.notice.body.intro", "\"a las {2}\": a clock time"),
        Map.entry("documents.notes.tooLong", "a column length limit"),
        Map.entry("pharmacy.register.truncated", "ControlledDrugRegisterService's row limit, a constant"),
        Map.entry("appointment.conflict", "\"a las {1}\": a clock time"),
        Map.entry("appointment.status.reasonTooLong", "a column length limit")));

    @Test
    void noPluralArticleStandsBeforeANumberThatCanBeOne() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        for (String suffix : new String[] {"_fr", "_es"}) {
            for (String area : MessageBundles.AREAS) {
                Properties file = MessageBundleParityTest.loadFile(area, suffix);
                for (String key : file.stringPropertyNames()) {
                    Matcher m = PLURAL_BEFORE_NUMBER.matcher(file.getProperty(key));
                    if (m.find()) {
                        found.add(key);
                    }
                }
            }
        }
        assertThat(found).as("plural article before a number").isNotEmpty();
        TreeSet<String> unlisted = new TreeSet<>(found);
        unlisted.removeAll(NEVER_ONE.keySet());
        TreeSet<String> stale = new TreeSet<>(NEVER_ONE.keySet());
        stale.removeAll(found);
        assertThat(unlisted)
            .as("a plural article before a number that can be 1 reads \"les 1 cp\" (QA D1177): reword it%s",
                SourceScan.MANDATE)
            .isEmpty();
        assertThat(stale).as("listed keys that no longer match: delete their line").isEmpty();
    }

    @Test
    void theScanSeesTheD1177Shape() {
        assertThat(PLURAL_BEFORE_NUMBER.matcher("dépasse les {3} {2} de la tranche").find()).isTrue();
        assertThat(PLURAL_BEFORE_NUMBER.matcher("supera los {3} {2} del tramo").find()).isTrue();
        assertThat(PLURAL_BEFORE_NUMBER.matcher("en los últimos {0} días").find()).isTrue();
        assertThat(PLURAL_BEFORE_NUMBER.matcher("dépasse le maximum de {3} {2}").find()).isFalse();
    }
}
