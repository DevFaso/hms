package com.example.hms.service.emar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hms.enums.FiveRightsCheck;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.emar.MedicationLabelMatch.Basis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D3302 (r70, regression): the ordered product is recognised by its catalogue item, its designation or its ATC
 * code, and by nothing looser. The order under test is the one of the report: paracetamol 500 mg tablets, stored
 * with the display text « Paracétamol 500 mg 500 mg Comprimé ».
 */
class MedicationLabelMatchTest {

    private static final UUID ITEM_ID = UUID.fromString("0f7a2c1e-6f0b-4a57-9d0e-0c7d4b1a2e33");

    @Test
    void theCatalogueItemIdIsTheProduct() {
        assertThat(match(ITEM_ID.toString())).isEqualTo(Basis.IDENTIFIER);
        assertThat(match(ITEM_ID.toString().toUpperCase())).isEqualTo(Basis.IDENTIFIER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARA-500-CP", "para-500-cp", "161"})
    void theFormularyAndRxNormCodesAreTheProduct(String scan) {
        assertThat(match(scan)).isEqualTo(Basis.IDENTIFIER);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Paracétamol 500 mg 500 mg Comprimé",   // the stored text, strength twice (D2558)
        "Paracétamol 500 mg Comprimé",
        "Paracétamol 500 mg",                   // the catalogue name
        "Paracétamol",                          // the generic name
        "paracetamol 500mg comprime",           // no accents, no space before the unit
        "PARACETAMOL  500 MG",
        "Paracétamol 0,5 g",                    // the same strength in grams
        "Doliprane 500 mg",                     // the brand name of the same catalogue item
        "Paracétamol comprimé"
    })
    void aDesignationOfTheProductIsTheProduct(String scan) {
        assertThat(match(scan)).isEqualTo(Basis.DESIGNATION);
    }

    @Test
    void theAtcCodeIsTheProduct() {
        assertThat(match("N02BE01")).isEqualTo(Basis.ATC);
        assertThat(match(" n02be01 ")).isEqualTo(Basis.ATC);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Paracétamol 1 g",                      // another strength of the same substance
        "Paracétamol 500 mg sirop",             // another form
        "Paracétamol 500 mg 1 g",               // one strength right, one wrong
        "Paracétamol codéine 500 mg",           // a combination that starts with the same word
        "Para",                                 // a prefix
        "Paracetamol 500 mg comprime effervescent",
        "Ibuprofène 400 mg",
        "N02BE51",                              // the ATC code of the combinations
        "N02BE",                                // an ATC prefix
        "500 mg",                               // a strength with no name
        "Comprimé",
        "81645d07-0000-0000-0000-000000000000"  // some other id (a prescription id)
    })
    void anythingElseIsNotTheProduct(String scan) {
        assertThat(match(scan)).isEqualTo(Basis.NONE);
    }

    @Test
    void anOrderInFreeTextMatchesItsOwnTextOnly() {
        Prescription rx = new Prescription();
        rx.setMedicationName("Amoxicilline acide clavulanique 500 mg/62,5 mg");

        assertThat(MedicationLabelMatch.match(rx, null, "amoxicilline acide clavulanique 500mg/62.5mg"))
            .isEqualTo(Basis.DESIGNATION);
        // Without a catalogue item nothing says where the name ends: the first word alone is another medicine.
        assertThat(MedicationLabelMatch.match(rx, null, "Amoxicilline")).isEqualTo(Basis.NONE);
        assertThat(MedicationLabelMatch.match(rx, null, "Amoxicilline 500 mg")).isEqualTo(Basis.NONE);
        assertThat(MedicationLabelMatch.match(rx, null, "J01CR02")).isEqualTo(Basis.NONE);
    }

    @Test
    void theBedsideCheckReadsTheCatalogueItemOfTheOrder() {
        MedicationCatalogItemRepository catalogue = mock(MedicationCatalogItemRepository.class);
        when(catalogue.findById(ITEM_ID)).thenReturn(Optional.of(item()));
        FiveRightsVerificationService service = new FiveRightsVerificationService(catalogue);

        for (String scan : new String[] {ITEM_ID.toString(), "Paracétamol 500 mg", "Paracétamol", "N02BE01"}) {
            assertThat(service.verify(mar(), patientId().toString(), scan, "1000 mg", "PO", LocalDateTime.now())
                .getOutcomes().get(FiveRightsCheck.DRUG)).as(scan).isTrue();
        }
        FiveRightsVerificationResult wrong =
            service.verify(mar(), patientId().toString(), "Paracétamol 1 g", "1000 mg", "PO", LocalDateTime.now());
        // A mismatch never passes: the dose then needs the documented reason of the override path.
        assertThat(wrong.failedChecks()).containsExactly(FiveRightsCheck.DRUG);
        assertThat(wrong.getFailureReasons().get(FiveRightsCheck.DRUG)).isNotBlank();
    }

    /** QA D1223: an order linked to an item its own name contradicts is recognised by its text only. */
    @Test
    void anOrderWhoseNameContradictsItsItemIsNotRecognisedByThatItem() {
        MedicationCatalogItemRepository catalogue = mock(MedicationCatalogItemRepository.class);
        when(catalogue.findById(ITEM_ID)).thenReturn(Optional.of(item()));
        FiveRightsVerificationService service = new FiveRightsVerificationService(catalogue);
        MedicationAdministrationRecord mar = mar();
        mar.getPrescription().setMedicationName("Amoxicilline 500 mg");
        // Review L1: the code the order copied from the item, and the item's id, identify the paracetamol item,
        // not what was ordered: scanning the paracetamol box must not pass an amoxicillin order.
        mar.getPrescription().setMedicationCode("PARA-500-CP");

        for (String scan : new String[] {"Paracétamol 500 mg", "Paracétamol", "N02BE01", "PARA-500-CP", "161",
            ITEM_ID.toString()}) {
            assertThat(service.verify(mar, patientId().toString(), scan, "1000 mg", "PO", LocalDateTime.now())
                .failedChecks()).as(scan).contains(FiveRightsCheck.DRUG);
        }
        assertThat(service.verify(mar, patientId().toString(), "Amoxicilline 500 mg", "1000 mg", "PO",
            LocalDateTime.now()).getOutcomes().get(FiveRightsCheck.DRUG)).isTrue();
    }

    /**
     * Sonar S5998 (r70): a scanner can send anything. A text longer than any label is refused outright, in
     * bounded time and stack, and is never read as its first characters: a real label followed by anything
     * else is not that label.
     */
    @Test
    void aVeryLongScanIsNeverTheProductAndNeverOverflows() {
        String digits = "9".repeat(400_000);
        String slashes = "1/".repeat(200_000) + " mg";
        String padded = "Paracétamol 500 mg" + " ".repeat(400) + "Morphine 10 mg";
        String repeated = "Paracétamol 500 mg ".repeat(50_000);

        for (String scan : new String[] {digits, digits + " mg", slashes, padded, repeated}) {
            assertThat(match(scan)).isEqualTo(Basis.NONE);
        }
        // The longest text still read as a label, and the first one that is not.
        assertThat(match("Paracétamol 500 mg" + " ".repeat(282))).isEqualTo(Basis.DESIGNATION);
        assertThat(match("Paracétamol 500 mg" + " ".repeat(283))).isEqualTo(Basis.NONE);
    }

    /** The order's own text is bounded too: an order with an absurd name matches nothing and overflows nothing. */
    @Test
    void anOrderWithAVeryLongNameOverflowsNothing() {
        Prescription rx = new Prescription();
        rx.setMedicationName("1/".repeat(200_000) + " mg");

        assertThat(MedicationLabelMatch.match(rx, null, "Paracétamol 500 mg")).isEqualTo(Basis.NONE);
    }

    private static Basis match(String scan) {
        return MedicationLabelMatch.match(order(), item(), scan);
    }

    private static Prescription order() {
        Prescription rx = new Prescription();
        rx.setMedicationName("Paracétamol 500 mg 500 mg Comprimé");
        rx.setMedicationCatalogItemId(ITEM_ID);
        rx.setDosage("1000 mg");
        rx.setRoute("PO");
        return rx;
    }

    private static MedicationCatalogItem item() {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setId(ITEM_ID);
        item.setCode("PARA-500-CP");
        item.setNameFr("Paracétamol 500 mg");
        item.setGenericName("Paracétamol");
        item.setBrandName("Doliprane");
        item.setAtcCode("N02BE01");
        item.setRxnormCode("161");
        item.setStrength("500");
        item.setStrengthUnit("mg");
        item.setForm("Comprimé");
        return item;
    }

    private static UUID patientId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private static MedicationAdministrationRecord mar() {
        Patient patient = new Patient();
        patient.setId(patientId());
        MedicationAdministrationRecord mar = new MedicationAdministrationRecord();
        mar.setPatient(patient);
        mar.setPrescription(order());
        mar.setScheduledTime(LocalDateTime.now());
        return mar;
    }

    /** Review L1: an order in step with its item is still recognised by the item's id and by the code it copied. */
    @Test
    void anOrderInStepWithItsItemKeepsItsIdentifiers() {
        MedicationCatalogItemRepository catalogue = mock(MedicationCatalogItemRepository.class);
        when(catalogue.findById(ITEM_ID)).thenReturn(Optional.of(item()));
        FiveRightsVerificationService service = new FiveRightsVerificationService(catalogue);
        MedicationAdministrationRecord mar = mar();
        mar.getPrescription().setMedicationCode("ORD-PARA-1");

        for (String scan : new String[] {ITEM_ID.toString(), "ORD-PARA-1", "PARA-500-CP", "161"}) {
            assertThat(service.verify(mar, patientId().toString(), scan, "1000 mg", "PO", LocalDateTime.now())
                .getOutcomes().get(FiveRightsCheck.DRUG)).as(scan).isTrue();
        }
    }

    /** Review L1: read by its own text only, an order matches its designation and no identifier at all. */
    @Test
    void ownTextOnlyKnowsNoIdentifier() {
        Prescription rx = order();
        rx.setMedicationCode("PARA-500-CP");

        assertThat(MedicationLabelMatch.matchOwnTextOnly(rx, "Paracétamol 500 mg 500 mg Comprimé")).isEqualTo(Basis.DESIGNATION);
        assertThat(MedicationLabelMatch.matchOwnTextOnly(rx, ITEM_ID.toString())).isEqualTo(Basis.NONE);
        assertThat(MedicationLabelMatch.matchOwnTextOnly(rx, "PARA-500-CP")).isEqualTo(Basis.NONE);
        assertThat(MedicationLabelMatch.matchOwnTextOnly(rx, " ")).isEqualTo(Basis.NONE);
        assertThat(MedicationLabelMatch.matchOwnTextOnly(null, "Paracétamol")).isEqualTo(Basis.NONE);
        assertThat(MedicationLabelMatch.matchOwnTextOnly(rx, "Paracétamol 500 mg" + " ".repeat(300))).isEqualTo(Basis.NONE);
    }
}
