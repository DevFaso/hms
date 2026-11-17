package com.example.hms.service.allergy;

import com.example.hms.model.PatientAllergy;
import com.example.hms.model.medication.MedicationCatalogItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1183 / D1149 (round 23): the drug-class allergy check by active
 * ingredient (INN). Every brand the three QA areas typed is checked free-text
 * AND as a catalogue item, and a made-up name reaches the safety net.
 */
class AllergyDrugIdentityTest {

    @ParameterizedTest(name = "{1} typed free-text, allergy {0}: blocked")
    @CsvSource({
        "Sulfamides, Septrin",
        "Sulfamides, Septrin forte",
        "Sulfamides, Fansidar",
        "Sulfamides, Eusaprim",
        "Sulfamides, Bactrim",
        "Sulfamides, Bactrim forte",
        "Pénicilline, Totapen",
        "Pénicilline, Orbénine",
        "Pénicilline, Ospen",
        "Pénicilline, Oracilline",
        "Pénicilline, Augmentin",
        "Pénicilline, Clamoxyl",
        "Pénicilline, Extencilline",
        "penicillin, TOTAPEN 1 g",
        "Ampicilline, Totapen",
        "Cloxacilline, Orbenine",
        "Sulfadoxine, Fansidar",
    })
    void everyQaBrandTypedFreeTextIsBlocked(String allergen, String medication) {
        AllergyDrugIdentity identity = AllergyDrugIdentity.of(medication, List.of());
        assertThat(identity.resolved()).as("a cited brand resolves to its INN").isTrue();
        assertThat(identity.matchingAllergies(List.of(allergy(allergen)))).hasSize(1);
    }

    @ParameterizedTest(name = "{1} picked from the catalogue (generic {2}), allergy {0}: blocked")
    @CsvSource({
        "Sulfamides, Septrin 480 mg, Sulfaméthoxazole + Triméthoprime, J01EE01",
        "Sulfamides, Septrin forte 960 mg, Sulfaméthoxazole + Triméthoprime, J01EE01",
        "Sulfamides, Fansidar, Sulfadoxine + Pyriméthamine, P01BD51",
        "Sulfamides, Eusaprim, Cotrimoxazole, J01EE01",
        "Sulfamides, Bactrim, Sulfaméthoxazole + Triméthoprime, J01EE01",
        "Pénicilline, Totapen 500 mg, Ampicilline, J01CA01",
        "Pénicilline, Orbénine 500 mg, Cloxacilline, J01CF02",
        "Pénicilline, Ospen 1 MUI, Phénoxyméthylpénicilline, J01CE02",
        "Pénicilline, Augmentin 1 g, Amoxicilline + acide clavulanique, J01CR02",
    })
    void everyQaBrandPickedFromTheCatalogueIsBlocked(String allergen, String brand, String generic, String atc) {
        MedicationCatalogItem item = item(brand, generic, atc);
        AllergyDrugIdentity identity = AllergyDrugIdentity.of(brand, List.of(item));
        assertThat(identity.resolved()).isTrue();
        assertThat(identity.matchingAllergies(List.of(allergy(allergen)))).hasSize(1);
    }

    @Test
    void aCatalogueBrandNoMapKnowsIsPlacedByItsAtcGroupAlone() {
        // A local brand whose generic name the pharmacy typed as the brand:
        // the WHO ATC group still places it (J01C penicillins, J01EE sulfonamides).
        MedicationCatalogItem penicillin = item("Pentrexyl", "Pentrexyl", "J01CA01");
        assertThat(AllergyDrugIdentity.of("Pentrexyl", List.of(penicillin))
            .matchingAllergies(List.of(allergy("Pénicilline")))).hasSize(1);
        MedicationCatalogItem sulfa = item("Marque locale", "Marque locale", "J01EE01");
        assertThat(AllergyDrugIdentity.of("Marque locale", List.of(sulfa))
            .matchingAllergies(List.of(allergy("Sulfamides")))).hasSize(1);
    }

    @Test
    void anUnknownNameWithAClassAllergyReachesTheSafetyNet() {
        AllergyDrugIdentity made = AllergyDrugIdentity.of("Zorbalix 500 mg", List.of());
        assertThat(made.resolved()).isFalse();
        assertThat(made.matchingAllergies(List.of(allergy("Pénicilline")))).isEmpty();
        assertThat(made.unverifiableClassAllergy(List.of(allergy("Pénicilline"))))
            .map(PatientAllergy::getAllergenDisplay).contains("Pénicilline");
        assertThat(made.unverifiableClassAllergy(List.of(allergy("Sulfamides")))).isPresent();
        assertThat(made.unverifiableClassAllergy(List.of(allergy("Amoxicilline")))).as("a class member").isPresent();
        assertThat(made.unverifiableClassAllergy(List.of(allergy("Céphalosporines")))).isPresent();
    }

    @Test
    void theSafetyNetStaysQuietWhenItHasNothingToSay() {
        AllergyDrugIdentity made = AllergyDrugIdentity.of("Zorbalix 500 mg", List.of());
        // No class allergy: an allergy to aspirin is checked by name, as before.
        assertThat(made.unverifiableClassAllergy(List.of(allergy("Aspirine")))).isEmpty();
        // An inactive allergy is not on file.
        PatientAllergy resolvedAllergy = allergy("Pénicilline");
        resolvedAllergy.setActive(false);
        assertThat(made.unverifiableClassAllergy(List.of(resolvedAllergy))).isEmpty();
        // A drug the catalogue knows is resolved through its generic name.
        MedicationCatalogItem paracetamol = item("Paracétamol 500 mg", "Paracétamol", "N02BE01");
        AllergyDrugIdentity known = AllergyDrugIdentity.of("Paracétamol 500 mg", List.of(paracetamol));
        assertThat(known.resolved()).isTrue();
        assertThat(known.unverifiableClassAllergy(List.of(allergy("Pénicilline")))).isEmpty();
        assertThat(known.matchingAllergies(List.of(allergy("Pénicilline")))).isEmpty();
        // An INN stem is resolved: the class rule judges it.
        assertThat(AllergyDrugIdentity.of("Ceftriaxone 1 g", List.of()).resolved()).isTrue();
    }

    @Test
    void sulfateIsNotASulfonamideAndTrimethoprimAloneIsNot() {
        assertThat(AllergyDrugIdentity.of("Sulfate ferreux", List.of(item("Sulfate ferreux", "Sulfate ferreux", "B03AA07")))
            .matchingAllergies(List.of(allergy("Sulfamides")))).isEmpty();
        assertThat(AllergyDrugIdentity.of("Triméthoprime", List.of(item("Triméthoprime", "Triméthoprime", "J01EA01")))
            .matchingAllergies(List.of(allergy("Sulfamides")))).isEmpty();
    }

    private static MedicationCatalogItem item(String name, String generic, String atc) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setNameFr(name);
        item.setGenericName(generic);
        item.setAtcCode(atc);
        return item;
    }

    private static PatientAllergy allergy(String display) {
        PatientAllergy a = new PatientAllergy();
        a.setAllergenDisplay(display);
        a.setActive(true);
        return a;
    }
}
