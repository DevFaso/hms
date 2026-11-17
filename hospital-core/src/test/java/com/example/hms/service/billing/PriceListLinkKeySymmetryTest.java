package com.example.hms.service.billing;

import com.example.hms.enums.PriceListLinkType;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Treatment;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 3 guard (deferred from the #1179 review, fix the class): a price-list link only prices a charge
 * when the key the price list STORED ({@link PriceListLinks#normalise}) is the key the charge source LOOKS UP
 * ({@link PriceListLinks#lookupKey}). PROCEDURE_CODE was trimmed on write only, so "sut-01" on the order never met
 * "SUT-01" in the list. This holds both sides together for every link type, with the untidy input a person types; a
 * new link type fails here until it has a sample.
 */
@DisplayName("Price list: a link key is written and looked up the same way, for every link type")
class PriceListLinkKeySymmetryTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID LAB_TEST_ID = UUID.randomUUID();
    private static final UUID TREATMENT_ID = UUID.randomUUID();

    /** Untidy input for each link type, as typed in the price list and as written on an order. */
    private static final Map<PriceListLinkType, List<String>> SAMPLES = new EnumMap<>(Map.of(
        PriceListLinkType.SYSTEM, List.of(" bed_day ", "Consultation_Default"),
        PriceListLinkType.LAB_TEST_DEFINITION, List.of(" " + LAB_TEST_ID.toString().toUpperCase(Locale.ROOT) + " "),
        PriceListLinkType.PROCEDURE_CODE, List.of("  sut-01 ", "Suture   de plaie", "ccam\tqzja002"),
        PriceListLinkType.IMAGING_STUDY, List.of(" xray : Radio  Thorax  FACE ", "ULTRASOUND:Échographie pelvienne"),
        PriceListLinkType.TRANSFUSION_PRODUCT, List.of(" packed_red_cells "),
        PriceListLinkType.TREATMENT, List.of(TREATMENT_ID.toString().toUpperCase(Locale.ROOT))));

    @Test
    @DisplayName("every link type has a sample, and its stored key is exactly its looked-up key")
    void writeAndLookupAgree() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LabTestDefinitionRepository labTests = mock(LabTestDefinitionRepository.class);
        TreatmentRepository treatments = mock(TreatmentRepository.class);
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL);
        LabTestDefinition test = new LabTestDefinition();
        test.setId(LAB_TEST_ID);
        test.setHospital(hospital);
        test.setActive(true);
        Treatment treatment = new Treatment();
        treatment.setId(TREATMENT_ID);
        treatment.setHospital(hospital);
        when(labTests.findById(any())).thenReturn(Optional.of(test));
        when(treatments.findById(any())).thenReturn(Optional.of(treatment));
        PriceListLinks links = new PriceListLinks(labTests, treatments);

        assertThat(SAMPLES.keySet()).as("a sample for every link type").containsExactlyInAnyOrder(
            PriceListLinkType.values());
        List<String> mismatches = new ArrayList<>();
        SAMPLES.forEach((type, raws) -> raws.forEach(raw -> {
            String stored = links.normalise(HOSPITAL, type, raw);
            Optional<String> looked = PriceListLinks.lookupKey(type, raw);
            if (looked.isEmpty() || !looked.get().equals(stored)) {
                mismatches.add(type + " '" + raw + "': stored '" + stored + "', looked up " + looked);
            }
        }));
        assertThat(mismatches).as("a link key stored one way and looked up another never prices a charge").isEmpty();
    }

    @Test
    @DisplayName("PROCEDURE_CODE: trimmed, single spaces, upper case on both sides")
    void theProcedureKey() {
        assertThat(PriceListLinks.lookupKey(PriceListLinkType.PROCEDURE_CODE, "  sut   01 ")).contains("SUT 01");
        assertThat(PriceListLinks.procedureKey("Suture de plaie")).isEqualTo("SUTURE DE PLAIE");
    }
}
