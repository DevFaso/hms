package com.example.hms.terminology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TerminologyCodesTest {

    /* ---------- LOINC ---------- */

    @ParameterizedTest
    @ValueSource(strings = {
        "8310-5",      // body temperature
        "718-7",       // hemoglobin
        "2339-0",      // glucose
        "29463-7",     // body weight
        "1234567-8"    // 7-digit max
    })
    void acceptsValidLoincCodes(String code) {
        assertThat(TerminologyCodes.isValidLoinc(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8310",        // missing check digit
        "8310-",       // trailing dash
        "8310-55",     // two-digit check
        "ABC-1",       // non-numeric stem
        "12345678-9"   // 8-digit stem too long
    })
    void rejectsMalformedLoinc(String code) {
        assertThat(TerminologyCodes.isValidLoinc(code)).isFalse();
    }

    @Test
    void rejectsNullLoinc() {
        assertThat(TerminologyCodes.isValidLoinc(null)).isFalse();
    }

    /* ---------- ICD-10 ---------- */

    @ParameterizedTest
    @ValueSource(strings = {
        "A00",         // cholera
        "J45.901",     // unspecified asthma
        "O80",         // single spontaneous delivery
        "Z00.00"       // general adult medical exam
    })
    void acceptsValidIcd10(String code) {
        assertThat(TerminologyCodes.isValidIcd10(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "A0",          // too short
        "AA0",         // letter where digit expected
        "A00.",        // dangling dot
        "A00.12345"    // suffix too long
    })
    void rejectsMalformedIcd10(String code) {
        assertThat(TerminologyCodes.isValidIcd10(code)).isFalse();
    }

    /* ---------- ICD-11 ---------- */

    @ParameterizedTest
    @ValueSource(strings = {
        "1A00",        // cholera (ICD-11 stem)
        "8A00.1",      // motor neuron disease subdivision
        "MG30",        // chronic primary pain
        "1B95.Z"       // tuberculosis subdivision
    })
    void acceptsValidIcd11(String code) {
        assertThat(TerminologyCodes.isValidIcd11(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1A0",         // too short
        "I000",        // contains forbidden 'I' (ICD-11 reserves I/O out)
        "OB00",        // contains forbidden 'O'
        "1A00.",       // trailing dot
        "1A00..1"      // double dot
    })
    void rejectsMalformedIcd11(String code) {
        assertThat(TerminologyCodes.isValidIcd11(code)).isFalse();
    }

    /* ---------- ATC ---------- */

    @ParameterizedTest
    @ValueSource(strings = {
        "J01CA04",     // amoxicillin
        "N02BE01",     // paracetamol
        "C09AA02",     // enalapril
        "P01BC01"      // chloroquine — endemic in West Africa
    })
    void acceptsValidAtc(String code) {
        assertThat(TerminologyCodes.isValidAtc(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "J01CA",       // missing trailing digits
        "JJ1CA04",     // bad anatomical layer
        "J01CA0",      // 6 chars not 7
        "J01CAA04"     // extra letter
    })
    void rejectsMalformedAtc(String code) {
        assertThat(TerminologyCodes.isValidAtc(code)).isFalse();
    }

    @Test
    void atcValidationNormalisesCase() {
        assertThat(TerminologyCodes.isValidAtc("j01ca04")).isTrue();
    }

    /* ---------- RxNorm ---------- */

    @ParameterizedTest
    @ValueSource(strings = {
        "1",
        "8640",        // erythromycin
        "161",         // acetaminophen
        "999999999999" // 12 digits — boundary
    })
    void acceptsValidRxNorm(String code) {
        assertThat(TerminologyCodes.isValidRxNorm(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ABC",                  // letters
        "8640A",                // mixed
        "1234567890123",        // 13 digits — over 12 max
        "  "                    // blanks
    })
    void rejectsMalformedRxNorm(String code) {
        assertThat(TerminologyCodes.isValidRxNorm(code)).isFalse();
    }

    /* ---------- ICD system URI selection ---------- */

    @Test
    void icdSystemForResolvesIcd10AndIcd11AndDefaultsLocal() {
        assertThat(TerminologyCodes.icdSystemFor("ICD-10")).isEqualTo(TerminologyCodes.SYSTEM_ICD10);
        assertThat(TerminologyCodes.icdSystemFor("CIM-10")).isEqualTo(TerminologyCodes.SYSTEM_ICD10);
        assertThat(TerminologyCodes.icdSystemFor("icd11")).isEqualTo(TerminologyCodes.SYSTEM_ICD11);
        assertThat(TerminologyCodes.icdSystemFor("MMS")).isEqualTo(TerminologyCodes.SYSTEM_HMS_PROBLEM_LOCAL);
        assertThat(TerminologyCodes.icdSystemFor(null)).isEqualTo(TerminologyCodes.SYSTEM_HMS_PROBLEM_LOCAL);
        assertThat(TerminologyCodes.icdSystemFor("freetext")).isEqualTo(TerminologyCodes.SYSTEM_HMS_PROBLEM_LOCAL);
    }
}
