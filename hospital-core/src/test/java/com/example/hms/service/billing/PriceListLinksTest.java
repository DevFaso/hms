package com.example.hms.service.billing;

import com.example.hms.enums.PriceListLinkType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Treatment;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 2 (plan section 3.3, README A-1 and A-2): every link of the test clinic's grid
 * (docs/plan/test-hospital/tarifs-test.csv) is stored the one way a charge source will look it up, and a link to
 * something the hospital cannot use is refused naming {@code linkKey}.
 */
@DisplayName("Price list: links")
class PriceListLinksTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID OTHER_HOSPITAL = UUID.randomUUID();

    private final LabTestDefinitionRepository labTests = mock(LabTestDefinitionRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final PriceListLinks links = new PriceListLinks(labTests, treatments);

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        when(labTests.findLiveByCodeAtHospital(anyString(), any())).thenReturn(List.of());
        when(labTests.findLiveGlobalByCode(anyString())).thenReturn(List.of());
        when(labTests.findById(any())).thenReturn(Optional.empty());
        when(treatments.findById(any())).thenReturn(Optional.empty());
    }

    private static Hospital hospital(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private static LabTestDefinition labTest(String code, UUID hospitalId) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setTestCode(code);
        definition.setName(code);
        definition.setActive(true);
        definition.setHospital(hospitalId == null ? null : hospital(hospitalId));
        return definition;
    }

    /** The SYSTEM, imaging, procedure and transfusion links of tarifs-test.csv, as typed and as stored. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = ';', value = {
        "SYSTEM;CONSULTATION_DEFAULT;CONSULTATION_DEFAULT",
        "SYSTEM;DELIVERY_VAGINAL;DELIVERY_VAGINAL",
        "SYSTEM;DELIVERY_CAESAREAN;DELIVERY_CAESAREAN",
        "SYSTEM;bed_day;BED_DAY",
        "IMAGING_STUDY;XRAY:radiographie thorax face;XRAY:radiographie thorax face",
        "IMAGING_STUDY;XRAY:radiographie membre;XRAY:radiographie membre",
        "IMAGING_STUDY;XRAY:radiographie bassin;XRAY:radiographie bassin",
        "IMAGING_STUDY;ULTRASOUND:echographie obstetricale;ULTRASOUND:echographie obstetricale",
        "IMAGING_STUDY;ultrasound: Échographie   Abdominale ;ULTRASOUND:echographie abdominale",
        "IMAGING_STUDY;ULTRASOUND:échographie pelvienne;ULTRASOUND:echographie pelvienne",
        "PROCEDURE_CODE;ACT-PANS-S;ACT-PANS-S",
        "PROCEDURE_CODE; ACT-APPEND ;ACT-APPEND",
        "TRANSFUSION_PRODUCT;WHOLE_BLOOD;WHOLE_BLOOD",
        "TRANSFUSION_PRODUCT;packed_red_cells;PACKED_RED_CELLS"
    })
    void everyGridLinkIsStoredTheWayItIsLookedUp(PriceListLinkType type, String typed, String stored) {
        assertThat(links.normalise(HOSPITAL, type, typed)).isEqualTo(stored);
    }

    @Test
    void aLabTestGivenByItsCodeIsStoredAsItsId() {
        LabTestDefinition nfs = labTest("TWP-NFS", HOSPITAL);
        when(labTests.findLiveByCodeAtHospital("TWP-NFS", HOSPITAL)).thenReturn(List.of(nfs));
        assertThat(links.normalise(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, "TWP-NFS"))
            .isEqualTo(nfs.getId().toString());
    }

    @Test
    void qaD2700ALabTestIsStoredOnItsFirstVersionSoARevisionKeepsThePrice() {
        LabTestDefinition v1 = labTest("TWP-NFS", HOSPITAL);
        v1.setActive(false);
        LabTestDefinition v2 = labTest("TWP-NFS", HOSPITAL);
        v2.setSupersedesDefinitionId(v1.getId());
        when(labTests.findLiveByCodeAtHospital("TWP-NFS", HOSPITAL)).thenReturn(List.of(v2));
        when(labTests.findById(v1.getId())).thenReturn(Optional.of(v1));

        assertThat(links.normalise(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, "TWP-NFS"))
            .isEqualTo(v1.getId().toString());
    }

    @Test
    @DisplayName("PR 10 review: the stored first version of a revised test is accepted when the item is edited")
    void theStoredFirstVersionIsAcceptedOnEdit() {
        LabTestDefinition v1 = labTest("TWP-NFS", HOSPITAL);
        v1.setActive(false);
        LabTestDefinition v2 = labTest("TWP-NFS", HOSPITAL);
        v2.setSupersedesDefinitionId(v1.getId());
        when(labTests.findById(v1.getId())).thenReturn(Optional.of(v1));
        when(labTests.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(labTests.findBySupersedesDefinitionId(v1.getId())).thenReturn(List.of(v2));

        // The form sends back the id the link is stored on (v1, no longer active): the item still saves.
        assertThat(links.normalise(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, v1.getId().toString()))
            .isEqualTo(v1.getId().toString());
    }

    @Test
    @DisplayName("PR 10 review: a test whose every version is retired is still refused")
    void aLineageRetiredWholeIsRefused() {
        LabTestDefinition v1 = labTest("TWP-OLD", HOSPITAL);
        v1.setActive(false);
        LabTestDefinition v2 = labTest("TWP-OLD", HOSPITAL);
        v2.setActive(false);
        v2.setSupersedesDefinitionId(v1.getId());
        when(labTests.findById(v1.getId())).thenReturn(Optional.of(v1));
        when(labTests.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(labTests.findBySupersedesDefinitionId(v1.getId())).thenReturn(List.of(v2));

        assertThatThrownBy(() -> links.normalise(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION,
            v1.getId().toString()))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("linkKey"));
    }

    @Test
    void aPlatformLabTestIsLinkableAndSoIsOneGivenByItsId() {
        LabTestDefinition platform = labTest("GLOBAL-GLY", null);
        when(labTests.findLiveGlobalByCode("GLOBAL-GLY")).thenReturn(List.of(platform));
        assertThat(links.normalise(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, "GLOBAL-GLY"))
            .isEqualTo(platform.getId().toString());
        when(labTests.findById(platform.getId())).thenReturn(Optional.of(platform));
        assertThat(links.normalise(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, platform.getId().toString()))
            .isEqualTo(platform.getId().toString());
    }

    @Test
    void anotherHospitalsLabTestIsUnknownInTheSameWordsAsAMissingOne() {
        LabTestDefinition foreign = labTest("TWP-NFS", OTHER_HOSPITAL);
        when(labTests.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        String foreignRefusal = refusal(PriceListLinkType.LAB_TEST_DEFINITION, foreign.getId().toString());
        UUID missing = UUID.randomUUID();
        String missingRefusal = refusal(PriceListLinkType.LAB_TEST_DEFINITION, missing.toString());
        assertThat(foreignRefusal.replace(foreign.getId().toString(), "?"))
            .isEqualTo(missingRefusal.replace(missing.toString(), "?"));
        assertThat(refusal(PriceListLinkType.LAB_TEST_DEFINITION, "TWP-INCONNU")).contains("TWP-INCONNU");
    }

    @Test
    void anotherHospitalsServiceIsRefused() {
        Treatment foreign = new Treatment();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(hospital(OTHER_HOSPITAL));
        when(treatments.findById(eq(foreign.getId()))).thenReturn(Optional.of(foreign));
        refusal(PriceListLinkType.TREATMENT, foreign.getId().toString());

        Treatment own = new Treatment();
        own.setId(UUID.randomUUID());
        own.setHospital(hospital(HOSPITAL));
        when(treatments.findById(eq(own.getId()))).thenReturn(Optional.of(own));
        assertThat(links.normalise(HOSPITAL, PriceListLinkType.TREATMENT, own.getId().toString()))
            .isEqualTo(own.getId().toString());
    }

    @ParameterizedTest(name = "{0} {1} is refused")
    @CsvSource(delimiter = ';', value = {
        "SYSTEM;CONSULTATION_SPECIALE",
        "IMAGING_STUDY;radiographie thorax",
        "IMAGING_STUDY;RADIO:thorax",
        "IMAGING_STUDY;XRAY:",
        "TRANSFUSION_PRODUCT;PLASMA_RICHE"
    })
    void anUnknownLinkIsRefusedNamingTheKey(PriceListLinkType type, String typed) {
        refusal(type, typed);
    }

    @Test
    void aLinkIsBothTypeAndKeyOrNeither() {
        assertThat(links.normalise(HOSPITAL, null, null)).isNull();
        assertThat(links.normalise(HOSPITAL, null, "  ")).isNull();
        assertThatThrownBy(() -> links.normalise(HOSPITAL, null, "BED_DAY"))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("linkType"));
        assertThatThrownBy(() -> links.normalise(HOSPITAL, PriceListLinkType.SYSTEM, " "))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("linkKey"));
    }

    private String refusal(PriceListLinkType type, String key) {
        try {
            links.normalise(HOSPITAL, type, key);
        } catch (FieldBusinessException refused) {
            assertThat(refused.getField()).isEqualTo("linkKey");
            return refused.getMessage();
        }
        throw new AssertionError(type + " " + key + " was accepted");
    }

    @Test
    @DisplayName("QA D2070: a link is named as the form shows it, and a key that no longer resolves is shown as stored")
    void aLinkIsNamedReadably() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.FRENCH);
        try {
            assertThat(links.label(HOSPITAL, PriceListLinkType.SYSTEM, "BED_DAY")).isEqualTo("Journée d’hospitalisation");
            assertThat(links.label(HOSPITAL, PriceListLinkType.TRANSFUSION_PRODUCT, "PACKED_RED_CELLS"))
                .contains("globules rouges");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
        LabTestDefinition coded = labTest("NFS", null);
        coded.setName("Numération formule sanguine");
        LabTestDefinition uncoded = labTest(null, HOSPITAL);
        uncoded.setName("Glycémie");
        LabTestDefinition foreignTest = labTest("TP", OTHER_HOSPITAL);
        for (LabTestDefinition d : List.of(coded, uncoded, foreignTest)) {
            when(labTests.findById(d.getId())).thenReturn(Optional.of(d));
        }
        assertThat(links.label(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, coded.getId().toString()))
            .isEqualTo("NFS · Numération formule sanguine");
        assertThat(links.label(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, uncoded.getId().toString()))
            .isEqualTo("Glycémie");
        assertThat(links.label(HOSPITAL, PriceListLinkType.LAB_TEST_DEFINITION, foreignTest.getId().toString()))
            .isEqualTo(foreignTest.getId().toString());

        Treatment own = new Treatment();
        own.setId(UUID.randomUUID());
        own.setName("Pansement simple");
        own.setHospital(hospital(HOSPITAL));
        Treatment foreign = new Treatment();
        foreign.setId(UUID.randomUUID());
        foreign.setName("Ailleurs");
        foreign.setHospital(hospital(OTHER_HOSPITAL));
        when(treatments.findById(own.getId())).thenReturn(Optional.of(own));
        when(treatments.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        assertThat(links.label(HOSPITAL, PriceListLinkType.TREATMENT, own.getId().toString())).isEqualTo("Pansement simple");
        assertThat(links.label(HOSPITAL, PriceListLinkType.TREATMENT, foreign.getId().toString()))
            .isEqualTo(foreign.getId().toString());
        assertThat(links.label(HOSPITAL, PriceListLinkType.TREATMENT, "not-an-id")).isEqualTo("not-an-id");

        assertThat(links.label(HOSPITAL, PriceListLinkType.PROCEDURE_CODE, "CESARIENNE")).isEqualTo("CESARIENNE");
        assertThat(links.label(HOSPITAL, PriceListLinkType.IMAGING_STUDY, "XRAY:thorax")).isEqualTo("XRAY:thorax");
        assertThat(links.label(HOSPITAL, null, "X")).isEqualTo("X");
        assertThat(links.label(HOSPITAL, PriceListLinkType.SYSTEM, null)).isNull();
    }
}
