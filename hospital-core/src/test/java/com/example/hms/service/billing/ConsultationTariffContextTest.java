package com.example.hms.service.billing;

import com.example.hms.enums.EncounterType;
import com.example.hms.enums.PriceListSystemKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4221 (round 81): which consultation tariff a visit takes, and the guard of the class: every consultation key
 * a hospital can link in its price list is one some visit is billed by. A key added to
 * {@link PriceListSystemKey} that no visit ever takes would be offered in the price list screen and price nothing.
 */
@DisplayName("Consultation tariff: the visit's kind picks the price-list key")
class ConsultationTariffContextTest {

    /** A Friday. */
    private static final LocalDateTime FRIDAY_MORNING = LocalDateTime.of(2026, 11, 27, 10, 0);

    @ParameterizedTest(name = "{0} / {1} / {2} at {3} -> {4}")
    @CsvSource(delimiter = ';', nullValues = "-", value = {
        // the QA case: an emergency walk-in at 05:35
        "EMERGENCY;Urgences;URG;2026-11-27T05:35;CONSULTATION_EMERGENCY_NIGHT,CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        "EMERGENCY;Urgences;URG;2026-11-27T06:00;CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        "EMERGENCY;Urgences;URG;2026-11-27T19:59;CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        "EMERGENCY;Urgences;URG;2026-11-27T20:00;CONSULTATION_EMERGENCY_NIGHT,CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        "EMERGENCY;-;-;2026-11-29T11:00;CONSULTATION_EMERGENCY_NIGHT,CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        "EMERGENCY;-;-;-;CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        // a consultation opened in the emergency department is an emergency visit
        "CONSULTATION;Service des urgences;-;2026-11-27T10:00;CONSULTATION_EMERGENCY,CONSULTATION_DEFAULT",
        "CONSULTATION;Médecine générale;MG;2026-11-27T05:35;CONSULTATION_DEFAULT",
        "CONSULTATION;-;-;-;CONSULTATION_DEFAULT",
        "-;-;-;-;CONSULTATION_DEFAULT",
        "FOLLOW_UP;Médecine générale;MG;2026-11-27T10:00;CONSULTATION_FOLLOW_UP,CONSULTATION_DEFAULT",
        "FOLLOW_UP;Pédiatrie;PED;2026-11-27T10:00;CONSULTATION_FOLLOW_UP,CONSULTATION_PAEDIATRICS,CONSULTATION_DEFAULT",
        "CONSULTATION;Pédiatrie;PED;2026-11-27T10:00;CONSULTATION_PAEDIATRICS,CONSULTATION_DEFAULT",
        "CONSULTATION;Paediatrics;-;2026-11-27T10:00;CONSULTATION_PAEDIATRICS,CONSULTATION_DEFAULT",
        "CONSULTATION;Gynécologie-obstétrique;GYN;2026-11-27T10:00;CONSULTATION_GYNAECOLOGY,CONSULTATION_DEFAULT",
        "CONSULTATION;Chirurgie générale;CHIR;2026-11-27T10:00;CONSULTATION_SURGERY,CONSULTATION_DEFAULT",
        "CONSULTATION;Médecine interne;MED;2026-11-27T10:00;CONSULTATION_INTERNAL_MEDICINE,CONSULTATION_DEFAULT",
        "CONSULTATION;Consultation prénatale;CPN;2026-11-27T10:00;CONSULTATION_PRENATAL,CONSULTATION_DEFAULT",
        "CONSULTATION;Maternité;CPON;2026-11-27T10:00;CONSULTATION_POSTNATAL,CONSULTATION_DEFAULT",
        "OUTPATIENT;Cardiologie;CARDIO;2026-11-27T10:00;CONSULTATION_DEFAULT"})
    void theKeysOfAVisit(EncounterType type, String department, String code, LocalDateTime at, String expected) {
        List<PriceListSystemKey> keys = ConsultationTariffContext.keysFor(type, department, code, at);

        assertThat(keys).extracting(Enum::name).containsExactly(expected.split(","));
    }

    @Test
    @DisplayName("the default consultation always closes the list, so a hospital with one tariff is billed as before")
    void theDefaultIsAlwaysTheLastResort() {
        for (EncounterType type : EncounterType.values()) {
            assertThat(ConsultationTariffContext.keysFor(type, "Urgences pédiatriques", "CPN", FRIDAY_MORNING))
                .last().isEqualTo(PriceListSystemKey.CONSULTATION_DEFAULT);
        }
    }

    @Test
    @DisplayName("guard: every CONSULTATION_ key the price list offers is taken by some visit")
    void everyConsultationKeyIsReachable() {
        Set<PriceListSystemKey> reached = EnumSet.noneOf(PriceListSystemKey.class);
        String[] departments = {null, "Urgences", "Pédiatrie", "Gynécologie", "Chirurgie", "Médecine interne",
            "CPN", "CPON"};
        LocalDateTime[] times = {FRIDAY_MORNING, LocalDateTime.of(2026, 11, 27, 23, 0)};
        for (EncounterType type : EncounterType.values()) {
            for (String department : departments) {
                for (LocalDateTime time : times) {
                    reached.addAll(ConsultationTariffContext.keysFor(type, department, null, time));
                }
            }
        }
        Set<PriceListSystemKey> offered = EnumSet.noneOf(PriceListSystemKey.class);
        for (PriceListSystemKey key : PriceListSystemKey.values()) {
            if (key.name().startsWith("CONSULTATION_")) {
                offered.add(key);
            }
        }
        assertThat(reached).containsExactlyInAnyOrderElementsOf(offered);
    }
}
