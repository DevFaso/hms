package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy 2026-11-20 (provisional): the curated mapping from a lab test to the
 * sensitive category it is recommended to carry.
 */
@DisplayName("Policy 2026-11-20: the recommended sensitive categories of the lab catalogue")
class RecommendedLabSensitivityTest {

    private static LabTestDefinition test(String code, String name) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setTestCode(code);
        definition.setName(name);
        return definition;
    }

    private static SensitivityCategory recommended(String code, String name) {
        return RecommendedLabSensitivity.recommend(test(code, name))
            .map(RecommendedLabSensitivity.Match::category).orElse(null);
    }

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "SERO-01 | Sérologie VIH 1/2 (Determine) | HIV",
        "HIV | Test rapide | HIV",
        "LAB-07 | Charge virale VIH | HIV",
        "CD4 | Numération lymphocytaire | HIV",
        "X1 | Numération des CD4 | HIV",
        "X2 | Antigène p24 | HIV",
        "TPHA | Sérologie | STI",
        "X3 | Sérologie syphilis (TPHA/VDRL) | STI",
        "X4 | RPR quantitatif | STI",
        "X5 | Recherche de Chlamydia trachomatis | STI",
        "X6 | Recherche de gonocoque | STI",
        "X7 | AgHBs | VIRAL_HEPATITIS",
        "X8 | Antigène HBs | VIRAL_HEPATITIS",
        "HBSAG | Dépistage | VIRAL_HEPATITIS",
        "X9 | Anticorps anti-HCV | VIRAL_HEPATITIS",
        "X10 | ADN VHB quantitatif | VIRAL_HEPATITIS",
        "X11 | ARN VHC | VIRAL_HEPATITIS",
        "X12 | β-hCG plasmatique | PREGNANCY",
        "X13 | Test de grossesse urinaire | PREGNANCY",
        "BHCG | Dosage | PREGNANCY",
        "X14 | Recherche de toxiques urinaires | SUBSTANCE_USE",
        "X15 | Alcoolémie | SUBSTANCE_USE",
        "X16 | Électrophorèse de l'hémoglobine | SICKLE_CELL",
        "X17 | Test d'Emmel | SICKLE_CELL",
        "X18 | Caryotype sanguin | GENETIC",
        "X19 | GeneXpert MTB/RIF | TUBERCULOSIS",
        "X20 | Recherche de BAAR (crachat) | TUBERCULOSIS",
    })
    @DisplayName("the tests of the policy's list are recommended their category, by code or by name")
    void theListedTests(String code, String name, SensitivityCategory expected) {
        assertThat(recommended(code, name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(delimiter = '|', value = {
        "NFS | Numération formule sanguine",
        "GLY | Glycémie à jeun",
        "CT | Cholestérol total",
        "TG | Triglycérides",
        "HB | Hémoglobine",
        "GE | Goutte épaisse (paludisme)",
        "CREAT | Créatinine",
        "GS | Groupe sanguin ABO Rhésus",
        "HBA1C | Hémoglobine glyquée",
        "ECBU | Examen cytobactériologique des urines",
        "VS | Vitesse de sédimentation",
        "CRP | Protéine C réactive",
    })
    @DisplayName("a test outside the list is never proposed")
    void testsOutsideTheList(String code, String name) {
        assertThat(RecommendedLabSensitivity.recommend(test(code, name))).isEmpty();
    }

    @Test
    @DisplayName("a match says what matched: the code, the name or a panel's component")
    void aMatchSaysWhatMatched() {
        Optional<RecommendedLabSensitivity.Match> byCode = RecommendedLabSensitivity.recommend(test("VDRL", "Sérologie"));
        assertThat(byCode).get().extracting(RecommendedLabSensitivity.Match::matchedOn)
            .isEqualTo(RecommendedLabSensitivity.MatchedOn.CODE);
        assertThat(byCode.orElseThrow().matchedText()).isEqualTo("VDRL");

        Optional<RecommendedLabSensitivity.Match> byName =
            RecommendedLabSensitivity.recommend(test("S-12", "Sérologie syphilis"));
        assertThat(byName).get().extracting(RecommendedLabSensitivity.Match::matchedOn)
            .isEqualTo(RecommendedLabSensitivity.MatchedOn.NAME);
    }

    @Test
    @DisplayName("an antenatal panel is recommended by its components, the most protected first: HIV")
    void aCombinedPanel() {
        LabTestDefinition panel = test("BPN", "Bilan prénatal");
        panel.setComponents(List.of(
            LabTestComponent.builder().code("GS").name("Groupe sanguin").build(),
            LabTestComponent.builder().code("AGHBS").name("Antigène HBs").build(),
            LabTestComponent.builder().code("TPHA").name("Syphilis").build(),
            LabTestComponent.builder().code("SRV").name("Sérologie VIH").build()));

        RecommendedLabSensitivity.Match match = RecommendedLabSensitivity.recommend(panel).orElseThrow();

        assertThat(match.category()).isEqualTo(SensitivityCategory.HIV);
        assertThat(match.matchedOn()).isEqualTo(RecommendedLabSensitivity.MatchedOn.COMPONENT);
        assertThat(match.matchedText()).isEqualTo("Sérologie VIH");
    }

    @Test
    @DisplayName("a panel with no listed component is not proposed")
    void anOrdinaryPanel() {
        LabTestDefinition panel = test("NFS", "Numération formule sanguine");
        panel.setComponents(List.of(
            LabTestComponent.builder().code("HB").name("Hémoglobine").build(),
            LabTestComponent.builder().code("PLT").name("Plaquettes").build()));

        assertThat(RecommendedLabSensitivity.recommend(panel)).isEmpty();
    }

    @Test
    @DisplayName("the mapping is marked provisional and names its version")
    void provisional() {
        assertThat(RecommendedLabSensitivity.PROVISIONAL).isTrue();
        assertThat(RecommendedLabSensitivity.VERSION).contains("provisional");
    }
}
