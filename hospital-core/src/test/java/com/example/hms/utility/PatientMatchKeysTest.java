package com.example.hms.utility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D924, D942, D943: the folds every patient search and match path shares. */
class PatientMatchKeysTest {

    @Test
    @DisplayName("the SQL fold maps each accented letter to the letter the Java fold produces")
    void theSqlAndJavaFoldsAgree() {
        assertThat(PatientMatchKeys.NAME_FOLD_FROM).hasSameSizeAs(PatientMatchKeys.NAME_FOLD_TO);
        for (int i = 0; i < PatientMatchKeys.NAME_FOLD_FROM.length(); i++) {
            String accented = String.valueOf(PatientMatchKeys.NAME_FOLD_FROM.charAt(i));
            String plain = String.valueOf(PatientMatchKeys.NAME_FOLD_TO.charAt(i));
            assertThat(PatientMatchKeys.foldName(accented)).as(accented).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("D943: names fold accents, case and inner spaces")
    void namesFold() {
        assertThat(PatientMatchKeys.foldName("  Aïcha   KABORÉ ")).isEqualTo("aicha kabore");
        assertThat(PatientMatchKeys.nameContainsPattern("Kaboré")).isEqualTo("%kabore%");
        assertThat(PatientMatchKeys.nameContainsPattern("50%_")).isEqualTo("%50\\%\\_%");
        assertThat(PatientMatchKeys.nameContainsPattern("  ")).isNull();
        assertThat(PatientMatchKeys.nameContains("Aïcha Kaboré", "aicha kabore")).isTrue();
    }

    @Test
    @DisplayName("D942: every spelling of one number has the same eight-digit key")
    void phonesFold() {
        for (String spelling : new String[] {"+22670001002", "70 00 10 02", "0022670001002",
            "70001002", "+226 70-00.10/02", "(226) 70001002"}) {
            assertThat(PatientMatchKeys.phoneKey(spelling)).as(spelling).isEqualTo("70001002");
        }
        assertThat(PatientMatchKeys.samePhone("70001002", "+22670001002")).isTrue();
        assertThat(PatientMatchKeys.samePhone("70001002", "70001003")).isFalse();
        assertThat(PatientMatchKeys.phoneKey("7000")).isEqualTo("7000");
        assertThat(PatientMatchKeys.isSubscriberKey("7000")).isFalse();
        assertThat(PatientMatchKeys.phoneKey("abc")).isNull();
        assertThat(PatientMatchKeys.phoneContainsPattern("70 00")).isEqualTo("%7000%");
    }

    @Test
    @DisplayName("review: the full number decides, whatever country, national or international")
    void theFullNumberDecides() {
        // Two Ivorian numbers that end alike are two subscribers.
        assertThat(PatientMatchKeys.samePhone("+2250701234567", "+2250501234567")).isFalse();
        // A national form typed at an Ivorian hospital matches its stored E.164 (trunk zero dropped).
        assertThat(PatientMatchKeys.samePhone("+225701234567", "07 01 23 45 67")).isTrue();
        assertThat(PatientMatchKeys.samePhone("0022670001002", "70 00 10 02")).isTrue();
        assertThat(PatientMatchKeys.samePhone("+22670001002", "70001003")).isFalse();
    }

    @Test
    @DisplayName("review: only phone-shaped identifiers are looked up as phones")
    void phoneShaped() {
        assertThat(PatientMatchKeys.looksLikePhone("+226 70 00 10 02")).isTrue();
        assertThat(PatientMatchKeys.looksLikePhone("awa.70001002@gmail.com")).isFalse();
        assertThat(PatientMatchKeys.looksLikePhone("CHW70001002")).isFalse();
        assertThat(PatientMatchKeys.phoneContainsPattern("unknown")).isEqualTo("%unknown%");
    }
}
