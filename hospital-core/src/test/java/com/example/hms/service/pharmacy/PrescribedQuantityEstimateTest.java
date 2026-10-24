package com.example.hms.service.pharmacy;

import com.example.hms.model.Prescription;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PrescribedQuantityEstimateTest {

    private static Prescription rx(String name, String dosage, String frequency, String duration) {
        Prescription p = new Prescription();
        p.setMedicationName(name);
        p.setDosage(dosage);
        p.setFrequency(frequency);
        p.setDuration(duration);
        return p;
    }

    @ParameterizedTest
    @CsvSource({
        "Paracétamol 500 mg,500 mg,three times a day,3 days,9",
        "Paracétamol 500 mg,500 mg,3 fois par jour,3 jours,9",
        "Paracétamol 500 mg,1 g,3 fois par jour,5 jours,30",
        "Amoxicilline,2 gélules,BID,1 semaine,28",
        "Ibuprofène 400 mg,400mg,q8h,2 días,6",
    })
    void readsAnUnambiguousRegimen(String name, String dosage, String frequency, String duration, String expected) {
        assertThat(PrescribedQuantityEstimate.perFill(rx(name, dosage, frequency, duration)))
            .isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @CsvSource({
        "Paracétamol 500 mg,500 mg,as needed,3 days",
        "Paracétamol 500 mg,500 mg,TID,1 mois",
        "Paracétamol 500 mg,750 mg,TID,3 days",
        "Paracétamol,500 mg,TID,3 days",
        "Artéméther 20 mg / Luméfantrine 120 mg,20 mg,BID,3 days",
        "Paracétamol 500 mg,,TID,3 days",
    })
    void anythingAmbiguousIsUnknown(String name, String dosage, String frequency, String duration) {
        assertThat(PrescribedQuantityEstimate.perFill(rx(name, dosage, frequency, duration))).isNull();
    }
}
