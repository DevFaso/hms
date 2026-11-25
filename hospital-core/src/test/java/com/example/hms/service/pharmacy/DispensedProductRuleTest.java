package com.example.hms.service.pharmacy;

import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3940 (r77): the dispensed product against the prescribed one ({@link DispenseVerificationService#classify},
 * {@link ProductStrength}). A 250 mg amoxicillin lot was preselected and dispensed for a 500 mg order, and a
 * « substitution » let diclofenac, then amoxicillin for a penicillin-allergic patient, replace ibuprofen.
 */
@DisplayName("QA D3940: the dispensed product is the prescribed one, or a real substitute")
class DispensedProductRuleTest {

    private static MedicationCatalogItem item(String nameFr, String generic, String atc, String form,
                                              String strength, String unit) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setId(UUID.randomUUID());
        item.setNameFr(nameFr);
        item.setGenericName(generic);
        item.setAtcCode(atc);
        item.setForm(form);
        item.setStrength(strength);
        item.setStrengthUnit(unit);
        return item;
    }

    private static final MedicationCatalogItem AMOX_500 =
        item("P77 Amoxicilline 500 mg", "Amoxicilline", "J01CA04", "CAPSULE", "500", "mg");
    private static final MedicationCatalogItem AMOX_250 =
        item("P77 Amoxicilline 250 mg", "Amoxicilline", "J01CA04", "CAPSULE", "250", "mg");
    private static final MedicationCatalogItem AMOX_500_TABLET =
        item("Amoxicilline 500 mg comprimé", "Amoxicillin", "J01CA04", "TABLET", "500", "mg");
    private static final MedicationCatalogItem AMOX_500_TWIN =
        item("Amoxicilline Gé 500 mg", "Amoxicilline", "J01CA04", "capsule", "0.5", "g");
    private static final MedicationCatalogItem IBU_400 =
        item("P77 Ibuprofène 400 mg", "Ibuprofène", "M01AE01", "TABLET", "400", "mg");
    private static final MedicationCatalogItem IBU_200 =
        item("P77 Ibuprofène 200 mg", "Ibuprofène", "M01AE01", "TABLET", "200", "mg");
    private static final MedicationCatalogItem DICLO_50 =
        item("P77 Diclofénac 50 mg", "Diclofénac", "M01AB05", "TABLET", "50", "mg");

    private static Prescription linkedTo(MedicationCatalogItem prescribed) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationCatalogItemId(prescribed.getId());
        rx.setMedicationName(prescribed.getNameFr());
        return rx;
    }

    private static Prescription freeText(String name) {
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setMedicationName(name);
        return rx;
    }

    @Nested
    @DisplayName("an order written from a catalogue item")
    class Linked {

        @Test
        void itsOwnItemIsThePrescribedProduct() {
            assertThat(DispenseVerificationService.classify(linkedTo(AMOX_500), AMOX_500, AMOX_500))
                .isEqualTo(DispensedProductMatch.PRESCRIBED);
        }

        @Test
        @DisplayName("D3940: a 250 mg lot for a 500 mg order is another strength, refused even as a substitution")
        void anotherStrengthIsRefused() {
            DispensedProductMatch match = DispenseVerificationService.classify(linkedTo(AMOX_500), AMOX_500, AMOX_250);
            assertThat(match).isEqualTo(DispensedProductMatch.OTHER_STRENGTH);
            assertThat(match.mayBeDispensed(true)).isFalse();
            assertThat(DispenseVerificationService.classify(linkedTo(IBU_400), IBU_400, IBU_200))
                .isEqualTo(DispensedProductMatch.OTHER_STRENGTH);
        }

        @Test
        @DisplayName("D3940: another molecule is refused, a substitution included")
        void anotherMoleculeIsRefused() {
            DispensedProductMatch match = DispenseVerificationService.classify(linkedTo(IBU_400), IBU_400, DICLO_50);
            assertThat(match).isEqualTo(DispensedProductMatch.OTHER_DRUG);
            assertThat(match.mayBeDispensed(true)).isFalse();
            assertThat(DispenseVerificationService.classify(linkedTo(IBU_400), IBU_400, AMOX_250))
                .isEqualTo(DispensedProductMatch.OTHER_DRUG);
        }

        @Test
        @DisplayName("the same ingredient, strength and form under another item is an equivalent (1 g read as 1000 mg)")
        void theSameProductUnderAnotherItemIsAnEquivalent() {
            DispensedProductMatch match = DispenseVerificationService.classify(linkedTo(AMOX_500), AMOX_500, AMOX_500_TWIN);
            assertThat(match).isEqualTo(DispensedProductMatch.EQUIVALENT);
            assertThat(match.mayBeDispensed(false)).isTrue();
        }

        @Test
        @DisplayName("the same ingredient and strength in another form is a substitute: only with a recorded reason")
        void anotherFormIsASubstitute() {
            DispensedProductMatch match = DispenseVerificationService.classify(
                linkedTo(AMOX_500), AMOX_500, AMOX_500_TABLET);
            assertThat(match).isEqualTo(DispensedProductMatch.SUBSTITUTE);
            assertThat(match.mayBeDispensed(false)).isFalse();
            assertThat(match.mayBeDispensed(true)).isTrue();
        }

        @Test
        @DisplayName("a strength nobody recorded cannot be shown to be the same dose")
        void anUnrecordedStrengthIsNotASubstitute() {
            MedicationCatalogItem noStrength = item("Amoxicilline", "Amoxicilline", "J01CA04", "CAPSULE", null, null);
            assertThat(DispenseVerificationService.classify(linkedTo(AMOX_500), AMOX_500, noStrength))
                .isEqualTo(DispensedProductMatch.OTHER_STRENGTH);
        }

        @Test
        @DisplayName("without ATC codes the generic names are read as their ingredients, in any spelling")
        void genericNamesWithoutAtc() {
            MedicationCatalogItem fr = item("Paracétamol 500 mg", "Paracétamol", null, "TABLET", "500", "mg");
            MedicationCatalogItem en = item("Paracetamol 500 mg tab", "Paracetamol", null, "TABLET", "500", "mg");
            MedicationCatalogItem combo = item("Paracétamol codéine 500 mg", "Paracétamol + codéine", null,
                "TABLET", "500", "mg");
            assertThat(DispenseVerificationService.classify(linkedTo(fr), fr, en))
                .isEqualTo(DispensedProductMatch.EQUIVALENT);
            assertThat(DispenseVerificationService.classify(linkedTo(fr), fr, combo))
                .isEqualTo(DispensedProductMatch.OTHER_DRUG);
        }
    }

    @Nested
    @DisplayName("an order in free text (no catalogue item)")
    class FreeText {

        @Test
        @DisplayName("D3940: « Amoxicilline 500 mg » is not filled from a 250 mg lot")
        void theStrengthTheTextStatesIsCompared() {
            Prescription rx = freeText("Amoxicilline 500 mg");
            assertThat(DispenseVerificationService.classify(rx, null, AMOX_250))
                .isEqualTo(DispensedProductMatch.OTHER_STRENGTH);
            assertThat(DispenseVerificationService.classify(rx, null, AMOX_500))
                .isEqualTo(DispensedProductMatch.PRESCRIBED);
        }

        @Test
        @DisplayName("a text that states no strength compares the molecule, as before")
        void noStrengthComparesTheMolecule() {
            assertThat(DispenseVerificationService.classify(freeText("Amoxicilline"), null, AMOX_250))
                .isEqualTo(DispensedProductMatch.PRESCRIBED);
            assertThat(DispenseVerificationService.classify(freeText("Ibuprofène"), null, DICLO_50))
                .isEqualTo(DispensedProductMatch.OTHER_DRUG);
        }

        @Test
        @DisplayName("the strength is read from the display name when the name states none")
        void theDisplayNameStatesTheStrength() {
            Prescription rx = freeText("Amoxicilline");
            rx.setMedicationDisplayName("Amoxicilline 500 mg gélule");
            assertThat(DispenseVerificationService.classify(rx, null, AMOX_250))
                .isEqualTo(DispensedProductMatch.OTHER_STRENGTH);
        }
    }

    @Nested
    @DisplayName("strengths")
    class Strengths {

        @Test
        void readFromTheItemFieldsOrItsName() {
            assertThat(ProductStrength.labelOf(AMOX_250, java.util.Locale.ENGLISH)).contains("250 mg");
            assertThat(ProductStrength.labelOf(AMOX_500_TWIN, java.util.Locale.ENGLISH)).contains("500 mg");
            MedicationCatalogItem named = item("Morphine 10 mg/mL injectable", "Morphine", null, null, null, null);
            assertThat(ProductStrength.labelOf(named, java.util.Locale.ENGLISH)).contains("10 mg/ml");
        }

        @Test
        @DisplayName("a number that is part of a word (P77, B12) is not a strength")
        void numbersInsideWordsAreNotStrengths() {
            assertThat(ProductStrength.inText("P77 Amoxicilline")).isEmpty();
            assertThat(ProductStrength.inText("Vitamine B12 1000 mcg")).map(v -> v.label(java.util.Locale.ENGLISH))
                .contains("1 mg");
            assertThat(ProductStrength.inText("Paracétamol 5 gélules")).isEmpty();
        }

        @Test
        @DisplayName("a concentration is not an amount: 250 mg/5 ml is not 250 mg")
        void aConcentrationIsNotAnAmount() {
            MedicationCatalogItem syrup = item("Amoxicilline sirop", "Amoxicilline", "J01CA04", "SYRUP", "250", "mg/5 ml");
            MedicationCatalogItem caps = item("Amoxicilline 250", "Amoxicilline", "J01CA04", "CAPSULE", "250", "mg");
            assertThat(ProductStrength.compare(syrup, caps)).isEqualTo(ProductStrength.Relation.DIFFERENT);
            assertThat(ProductStrength.compare("Amoxicilline 250 mg/5 ml", syrup))
                .isEqualTo(ProductStrength.Relation.SAME);
        }

        @Test
        void describeProductAddsTheStrengthOnlyWhenTheNameLacksIt() {
            MedicationCatalogItem plain = item("Amoxicilline", "Amoxicilline", null, null, "250", "mg");
            assertThat(DispenseVerificationService.withStrength("Amoxicilline", plain, java.util.Locale.FRENCH))
                .isEqualTo("Amoxicilline 250 mg");
            assertThat(DispenseVerificationService.withStrength("P77 Amoxicilline 250 mg", AMOX_250))
                .isEqualTo("P77 Amoxicilline 250 mg");
        }
    }
}
