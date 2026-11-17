package com.example.hms.fhir.mapper;

import com.example.hms.model.Prescription;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round 33: stripping everything but digits exported "12,5" as 125 and "2 a 4" as 24.
 * One number is a structured dose; anything else stays in the dosage's text.
 */
@DisplayName("the FHIR structured dose is one number, or absent")
class MedicationRequestStructuredDoseTest {

    private static Dosage dosage(String typed) throws ReflectiveOperationException {
        Prescription rx = new Prescription();
        rx.setDosage(typed);
        rx.setDoseUnit("mg");
        Dosage out = new Dosage();
        Method add = MedicationRequestFhirMapper.class.getDeclaredMethod("addStructuredDose", Dosage.class, Prescription.class);
        add.setAccessible(true);
        add.invoke(null, out, rx);
        return out;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"500|500", "500 mg|500", "12,5|12.5", "12.5 mg|12.5", " 0,25 g |0.25"})
    void oneNumberIsTheDose(String typed, String expected) throws ReflectiveOperationException {
        Quantity dose = dosage(typed).getDoseAndRateFirstRep().getDoseQuantity();

        assertThat(dose.getValue()).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(dose.getUnit()).isEqualTo("mg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2-4", "2-4 mg", "2 a 4", "2 à 4 mg", "1,5-2,5 mg", "de 2 a 4 mg", "2 to 4 mg",
        "250 mg/5 ml", "500 mg PO", "1-0-1", "si douleur"})
    void anythingElseStaysInTheText(String typed) throws ReflectiveOperationException {
        assertThat(dosage(typed).hasDoseAndRate()).isFalse();
    }
}
