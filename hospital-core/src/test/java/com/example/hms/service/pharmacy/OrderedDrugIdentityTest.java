package com.example.hms.service.pharmacy;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.rules.CdsRuleContext;
import com.example.hms.cdshooks.rules.OrderedDrugIdentityRule;
import com.example.hms.cdshooks.service.MedicationAllergyCdsService;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.rules.DrugDrugInteractionRule;
import com.example.hms.enums.DispenseCheck;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.allergy.AllergyDrugIdentity;
import com.example.hms.utility.LotBarcode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageImpl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D1223 (round 24): the one rule deciding whether the name typed on an
 * order and the catalogue item it carries name the same drug, and each check
 * that reads both inputs through it.
 */
class OrderedDrugIdentityTest {

    private static MedicationCatalogItem item(String nameFr, String generic, String brand) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setId(UUID.randomUUID());
        item.setNameFr(nameFr);
        item.setGenericName(generic);
        item.setBrandName(brand);
        return item;
    }

    private static PatientAllergy allergy(String display) {
        PatientAllergy a = new PatientAllergy();
        a.setActive(true);
        a.setAllergenDisplay(display);
        return a;
    }

    private static final MedicationCatalogItem PARACETAMOL = item("Paracétamol", "Paracétamol", "Doliprane");

    @org.junit.jupiter.api.Test
    void everyCitedBrandHasAKnownDrugAtThePharmacy() {
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.brandsWithAKnownDrug())
            .containsExactlyInAnyOrderElementsOf(com.example.hms.service.allergy.DrugClassAllergy.citedBrandPatterns());
    }

    /** QA D1492: the pharmacy reads a cited brand as its generic; a lot leaves the shelf only for the same drug. */
    @ParameterizedTest(name = "\"{0}\" from a {1} lot: {2}")
    @CsvSource({
        "Efferalgan, Paracétamol, true",
        "Tylenol 500 mg, Paracétamol, true",
        "Clamoxyl 500 mg, Amoxicilline, true",
        "Efferalgan codéine, Paracétamol, false",
        "Efferalgan, Paracétamol + codéine, false",
        "Augmentin, Amoxicilline, false",
        "Clamoxyl, Amoxicilline + Acide clavulanique, false",
        "Augmentin, Amoxicilline + Acide clavulanique, true",
        "Bactrim, Sulfaméthoxazole, false",
        "Bactrim, Co-trimoxazole, true",
        "Dafalgan, Paracétamol, false",
        "Panadol, Paracétamol, false",
        "Efferalgan, Ibuprofène, false",
        "Efferalgan, Paracétamol 500 mg, true",
        "Clamoxyl, Amoxicilline 250 mg/5 ml, true",
        "Augmentin, Amoxicilline acide clavulanique, true",
        "Augmentin, Amoxicillin/clavulanic acid, true",
        "Augmentin, Amoxicilline 500 mg + Acide clavulanique 125 mg, true",
        "Clamoxyl, Amoxicilline acide clavulanique, false",
        "Clamoxyl, Amoxicilline 500 mg + Acide clavulanique 125 mg, false",
        "Totapen, Ampicilline cloxacilline, false",
        "Fansidar, Sulfadoxine-pyriméthamine, true",
        "Fansidar, Sulfadoxine, false",
        "Bactrim, Sulfaméthoxazole-triméthoprime, true",
        "Bactrim, Cotrimoxazole, true",
        "Extencilline, Benzylpénicilline, false",
        "Extencilline, Benzathine benzylpénicilline, true",
        "Ospen, Penicillin G, false",
        "Ospen, Pénicilline V, true",
        "Ospen, Phénoxyméthylpénicilline, true",
        "Extencilline, Penicilina V, false",
        "Clamoxyl + acide clavulanique, Amoxicilline, false",
    })
    void aCitedBrandIsThisDrugOnlyForItsOwnGeneric(String typed, String generic, boolean expected) {
        org.assertj.core.api.Assertions.assertThat(
                OrderedDrugIdentity.citedBrandIsThisDrug(typed, item(generic, generic, null)))
            .isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" names {1}")
    @CsvSource({
        // The portal's label: French name, strength, form.
        "Paracétamol 500 mg Comprimé, Paracétamol, Paracétamol",
        "PARACETAMOL, Paracétamol, Paracétamol",
        // The same INN, English / French / Spanish spellings.
        "Amoxicillin 500 mg, Amoxicilline, Amoxicilline",
        "Amoxicilina 500 mg, Amoxicilline, Amoxicilline",
        "amoxycillin, Amoxicilline, Amoxicilline",
        "Sulphamethoxazole 400 mg, Sulfaméthoxazole, Sulfaméthoxazole",
        "Ibuprofeno 400 mg, Ibuprofène, Ibuprofène",
        "Metronidazol 250 mg, Métronidazole, Métronidazole",
        // A cited brand whose INN is the item's generic.
        "Clamoxyl 1 g, Amoxicilline, Amoxicilline",
    })
    void aNameThatNamesTheItemAgrees(String typed, String nameFr, String generic) {
        assertThat(OrderedDrugIdentity.agrees(typed, item(nameFr, generic, null))).isTrue();
        assertThat(OrderedDrugIdentity.contradicts(typed, item(nameFr, generic, null))).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" contradicts {1}")
    @CsvSource({
        // The QA case: an unrecognised brand sent with paracetamol's id.
        "Bristopen 500 mg, Paracétamol, Paracétamol",
        "Kotrimazol-Faso 480, Paracétamol, Paracétamol",
        // A known drug sent with another drug's id.
        "Morphine 10 mg, Paracétamol, Paracétamol",
        "Amoxicilline 500 mg, Paracétamol, Paracétamol",
        // Look-alike, sound-alike INNs are different drugs, whatever the spelling rule.
        "Chlorpropamide 250 mg, Chlorpromazine, Chlorpromazine",
        "Prednisolone 5 mg, Prednisone, Prednisone",
        // A combination is not its first component.
        "Amoxicilline 500 mg, Augmentin, Amoxicilline + acide clavulanique",
    })
    void aNameThatNamesAnotherDrugContradicts(String typed, String nameFr, String generic) {
        assertThat(OrderedDrugIdentity.contradicts(typed, item(nameFr, generic, null))).isTrue();
    }

    @Test
    void theItemsRecordedBrandNamesIt() {
        assertThat(OrderedDrugIdentity.agrees("Doliprane 1 g", PARACETAMOL)).isTrue();
    }

    @Test
    void onlyOneInputContradictsNothing() {
        assertThat(OrderedDrugIdentity.contradicts(null, PARACETAMOL)).isFalse();
        assertThat(OrderedDrugIdentity.contradicts("  ", PARACETAMOL)).isFalse();
        assertThat(OrderedDrugIdentity.contradicts("Bristopen", null)).isFalse();
    }

    @Test
    void theAllergyIdentityChecksAMismatchedItemButNeverLetsItResolveTheName() {
        AllergyDrugIdentity identity = AllergyDrugIdentity.of("Bristopen 500 mg", List.of(PARACETAMOL));
        assertThat(identity.resolved()).as("paracetamol's id does not resolve Bristopen").isFalse();
        assertThat(identity.unverifiableClassAllergy(List.of(allergy("Pénicilline")))).isPresent();
        // The item is still checked: an allergy to it is met.
        assertThat(identity.matchingAllergies(List.of(allergy("Paracétamol")))).hasSize(1);
        // An agreeing pick resolves, as before.
        assertThat(AllergyDrugIdentity.of("Paracétamol 500 mg", List.of(PARACETAMOL)).resolved()).isTrue();
    }

    @Test
    void theCdsHookChecksTheNameWhenTheCodeNamesAnotherDrug() {
        UUID hospitalId = UUID.randomUUID();
        MedicationCatalogItemRepository catalog = mock(MedicationCatalogItemRepository.class);
        PARACETAMOL.setCode("PARA500");
        MedicationCatalogItem amoxicillin = item("Amoxicilline", "Amoxicilline", null);
        when(catalog.findByHospitalIdAndCode(hospitalId, "PARA500")).thenReturn(Optional.of(PARACETAMOL));
        when(catalog.findActivePage(any(), any())).thenReturn(new PageImpl<>(List.of(PARACETAMOL, amoxicillin)));
        MedicationAllergyCdsService service = new MedicationAllergyCdsService(mock(PatientAllergyRepository.class),
            mock(PatientRepository.class), mock(CdsRuleEngine.class), mock(DrugDrugInteractionRule.class));
        service.setCatalogRepository(catalog);

        // An unknown name with paracetamol's code: the safety net, not silence.
        assertThat(service.allergyCards("Bristopen 500 mg", "PARA500", hospitalId, List.of(allergy("Pénicilline"))))
            .singleElement().satisfies(card -> assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL));
        // A penicillin typed with paracetamol's code: the allergy match, not silence.
        assertThat(service.allergyCards("Amoxicilline 1 g", "PARA500", hospitalId, List.of(allergy("Pénicilline"))))
            .singleElement().satisfies(card -> assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL));
        // Paracetamol typed with its own code: nothing to say.
        assertThat(service.allergyCards("Paracétamol 500 mg", "PARA500", hospitalId,
            List.of(allergy("Pénicilline")))).isEmpty();
    }

    @Test
    void theEngineRuleFlagsANameThatContradictsTheResolvedItem() {
        OrderedDrugIdentityRule rule = new OrderedDrugIdentityRule();
        assertThat(rule.evaluate(context("Bristopen 500 mg", PARACETAMOL)))
            .singleElement().satisfies(card -> {
                assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
                assertThat(card.summary()).contains("Bristopen 500 mg").contains("Paracétamol");
            });
        assertThat(rule.evaluate(context("Paracétamol 500 mg", PARACETAMOL))).isEmpty();
        assertThat(rule.evaluate(context("Bristopen 500 mg", null))).isEmpty();
    }

    @Test
    void theDispenseDrugCheckFailsALinkedOrderWhoseNameIsAnotherDrug() {
        DispenseVerificationService service = new DispenseVerificationService(
            Clock.fixed(java.time.Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("UTC")));
        Prescription rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(new Patient());
        rx.setMedicationName("Bristopen 500 mg");
        rx.setMedicationCatalogItemId(PARACETAMOL.getId());
        assertThat(service.matchesPrescribedDrug(rx, PARACETAMOL)).as("the link no longer hides the name").isFalse();
        assertThat(service.verify(rx, lotOf(PARACETAMOL), null, null).failedChecks()).contains(DispenseCheck.DRUG);

        rx.setMedicationName("Paracétamol 500 mg");
        assertThat(service.matchesPrescribedDrug(rx, PARACETAMOL)).isTrue();
    }

    private static CdsRuleContext context(String name, MedicationCatalogItem item) {
        return new CdsRuleContext(null, UUID.randomUUID(), name, null, null, null, item, null,
            List.of(), List.of(), null, null, null);
    }

    private static StockLot lotOf(MedicationCatalogItem item) {
        InventoryItem inventoryItem = new InventoryItem();
        inventoryItem.setMedicationCatalogItem(item);
        StockLot lot = StockLot.builder()
            .inventoryItem(inventoryItem)
            .lotNumber("LOT-1")
            .expiryDate(LocalDate.of(2027, 6, 1))
            .barcodeValue(LotBarcode.mint())
            .build();
        lot.setId(UUID.randomUUID());
        return lot;
    }

    /** QA D1462: a cited paracetamol brand on the Paracétamol item. It closes that half of D1264 for Efferalgan. */
    @org.junit.jupiter.api.Test
    void aCitedParacetamolBrandAgreesWithTheItemAndGivesItNoStrength() {
        MedicationCatalogItem plain = item("Paracétamol", "Paracétamol", null);
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.agrees("Efferalgan 1 g", plain)).isTrue();
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.namesOnlyThroughCitedBrand("Efferalgan 1 g", plain)).isTrue();
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.namesOnlyThroughCitedBrand("Paracétamol 1 g", plain)).isFalse();
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.namesOnlyThroughCitedBrand("Doliprane 1 g", PARACETAMOL)).isFalse();
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.namesOnlyThroughCitedBrand(null, plain)).isFalse();
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.namesOnlyThroughCitedBrand("Efferalgan", null)).isFalse();
    }

    /** Review: a slash strength is not a second drug, and a brand keeps its strength reading off paracetamol. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"Augmentin 500/62.5, Amoxicilline", "Bactrim 400/80, Sulfamethoxazole", "Fansidar 500/25, Sulfadoxine"})
    void aCitedBrandWithASlashStrengthStillAgrees(String typed, String generic) {
        org.assertj.core.api.Assertions.assertThat(OrderedDrugIdentity.agrees(typed, item(generic, generic, null))).isTrue();
    }

    @org.junit.jupiter.api.Test
    void aParacetamolBrandDoesNotNameACombinationItem() {
        org.assertj.core.api.Assertions.assertThat(
            OrderedDrugIdentity.contradicts("Efferalgan", item("Paracétamol + codéine", "Paracétamol + codéine", null))).isTrue();
    }

    @org.junit.jupiter.api.Test
    void aCitedBrandWithASecondDrugDoesNotNameTheItemAlone() {
        org.assertj.core.api.Assertions.assertThat(
            OrderedDrugIdentity.contradicts("Efferalgan codéine", item("Paracétamol", "Paracétamol", null))).isTrue();
    }
}
