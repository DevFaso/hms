package com.example.hms.service.orderset;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.ImagingModality;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.PrescriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderSetItemDispatcherTest {

    private final PrescriptionService prescriptionService = mock(PrescriptionService.class);
    private final LabOrderService labOrderService = mock(LabOrderService.class);
    private final ImagingOrderService imagingOrderService = mock(ImagingOrderService.class);
    private final LabTestDefinitionRepository labTestDefinitionRepository =
        mock(LabTestDefinitionRepository.class);

    private OrderSetItemDispatcher dispatcher;

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID ENCOUNTER_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final UUID ASSIGNMENT_ID = UUID.randomUUID();
    private static final UUID ORDER_SET_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new OrderSetItemDispatcher(
            prescriptionService, labOrderService, imagingOrderService, labTestDefinitionRepository);
    }

    private OrderSetApplicationContext ctx() {
        return new OrderSetApplicationContext(
            ORDER_SET_ID, "Sepsis bundle", "Sepsis Hour-1",
            ADMISSION_ID, PATIENT_ID, HOSPITAL_ID, ENCOUNTER_ID, STAFF_ID, ASSIGNMENT_ID,
            "A41.9", false
        );
    }

    private static LabTestDefinition labDefinitionFor(String name) {
        LabTestDefinition def = new LabTestDefinition();
        def.setId(UUID.randomUUID());
        def.setName(name);
        return def;
    }

    @Test
    void dispatchesMedicationThroughPrescriptionService() {
        UUID createdId = UUID.randomUUID();
        PrescriptionResponseDTO resp = new PrescriptionResponseDTO();
        resp.setId(createdId);
        resp.setCdsAdvisories(List.of());
        when(prescriptionService.createPrescription(any(), any(Locale.class))).thenReturn(resp);

        Map<String, Object> item = Map.of(
            "orderType", "MEDICATION",
            "medicationName", "Ceftriaxone",
            "medicationCode", "RX2193",
            "dose", "2 g",
            "route", "IV",
            "frequency", "Q24H"
        );
        DispatchResult result = dispatcher.dispatch(item, ctx(), Locale.ENGLISH);

        assertThat(result.type()).isEqualTo(DispatchResult.Type.MEDICATION);
        assertThat(result.createdId()).isEqualTo(createdId);

        ArgumentCaptor<PrescriptionRequestDTO> captor = ArgumentCaptor.forClass(PrescriptionRequestDTO.class);
        verify(prescriptionService).createPrescription(captor.capture(), eq(Locale.ENGLISH));
        PrescriptionRequestDTO sent = captor.getValue();
        assertThat(sent.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(sent.getStaffId()).isEqualTo(STAFF_ID);
        assertThat(sent.getEncounterId()).isEqualTo(ENCOUNTER_ID);
        assertThat(sent.getMedicationName()).isEqualTo("Ceftriaxone");
        assertThat(sent.getDosage()).isEqualTo("2 g IV");
        assertThat(sent.getNotes()).contains("Sepsis bundle");
    }

    /**
     * QA D1396 (round 33): the item's quantity, unit and catalogue pick reach
     * the order, so a controlled drug can be applied; the editor's dose range is
     * the order's dose. Numbers come out of JSON as Integer, Double or String.
     */
    @Test
    void d1396_theQuantityUnitPickAndDoseRangeReachTheOrder() {
        PrescriptionResponseDTO resp = new PrescriptionResponseDTO();
        resp.setId(UUID.randomUUID());
        resp.setCdsAdvisories(List.of());
        when(prescriptionService.createPrescription(any(), any(Locale.class))).thenReturn(resp);
        UUID pick = UUID.randomUUID();
        ArgumentCaptor<PrescriptionRequestDTO> captor = ArgumentCaptor.forClass(PrescriptionRequestDTO.class);

        dispatcher.dispatch(Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "doseRangeMin", 2,
            "doseRangeMax", 2.0, "doseUnit", "mg", "route", "IV", "frequency", "Q4H", "quantity", 6,
            "quantityUnit", "ampoules", "medicationCatalogItemId", pick.toString()), ctx(), Locale.FRENCH);
        verify(prescriptionService).createPrescription(captor.capture(), eq(Locale.FRENCH));
        PrescriptionRequestDTO sent = captor.getValue();
        assertThat(sent.getQuantity()).isEqualByComparingTo("6");
        assertThat(sent.getQuantityUnit()).isEqualTo("ampoules");
        assertThat(sent.getMedicationCatalogItemId()).isEqualTo(pick);
        assertThat(sent.getDosage()).isEqualTo("2 mg IV");
        assertThat(sent.getRoute()).as("the route reaches the order itself").isEqualTo("IV");

        org.mockito.Mockito.clearInvocations(prescriptionService);
        dispatcher.dispatch(Map.of("orderType", "MEDICATION", "medicationName", "Morphine", "doseRangeMin", 2,
            "doseRangeMax", 4, "doseUnit", "mg", "quantity", "6.5", "medicationCatalogItemId", "not-a-uuid"),
            ctx(), Locale.FRENCH);
        verify(prescriptionService).createPrescription(captor.capture(), eq(Locale.FRENCH));
        assertThat(captor.getValue().getDosage()).isEqualTo("2-4 mg");
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("6.5");
        assertThat(captor.getValue().getMedicationCatalogItemId()).isNull();
        assertThat(captor.getValue().getRoute()).as("no route written").isNull();

        org.mockito.Mockito.clearInvocations(prescriptionService);
        dispatcher.dispatch(Map.of("orderType", "MEDICATION", "medicationName", "X", "route", "BOGUS",
            "notes", "Surveiller la FR"), ctx(), Locale.FRENCH);
        verify(prescriptionService).createPrescription(captor.capture(), eq(Locale.FRENCH));
        assertThat(captor.getValue().getRoute()).as("a route the reading does not know is not stored").isNull();
        assertThat(captor.getValue().getNotes()).contains("Sepsis bundle").contains("Surveiller la FR");
    }

    /**
     * QA round 30 / QA D1378 / D1382 (round 32): an item ticked as needed
     * carries its author's tick and minimum interval to the order (a number,
     * digits or a long); an item not ticked sends neither, whatever its
     * frequency says - the order rule (PrnMinInterval, in createPrescription)
     * and the apply's item check (OrderSetItemPrnRules) refuse what is wrong,
     * the dispatcher never skips a medication item for it any more.
     */
    @Test
    void prn_theItemsTickAndIntervalReachTheOrder() {
        PrescriptionResponseDTO resp = new PrescriptionResponseDTO();
        resp.setId(UUID.randomUUID());
        resp.setCdsAdvisories(List.of());
        when(prescriptionService.createPrescription(any(), any(Locale.class))).thenReturn(resp);

        for (Object hours : new Object[] {6, "6", 6L}) {
            DispatchResult applied = dispatcher.dispatch(Map.of(
                "orderType", "MEDICATION", "medicationName", "Paracetamol", "frequency", "SOS",
                "asNeeded", true, "prnMinIntervalHours", hours), ctx(), Locale.ENGLISH);
            assertThat(applied.type()).as(String.valueOf(hours)).isEqualTo(DispatchResult.Type.MEDICATION);
        }
        ArgumentCaptor<PrescriptionRequestDTO> captor = ArgumentCaptor.forClass(PrescriptionRequestDTO.class);
        verify(prescriptionService, times(3)).createPrescription(captor.capture(), eq(Locale.ENGLISH));
        assertThat(captor.getAllValues()).allSatisfy(r -> {
            assertThat(r.getAsNeeded()).isTrue();
            assertThat(r.getPrnMinIntervalHours()).isEqualTo(6);
        });

        dispatcher.dispatch(Map.of("orderType", "MEDICATION", "medicationName", "Amoxicillin", "frequency", "TID",
            "prnMinIntervalHours", 6), ctx(), Locale.ENGLISH);
        verify(prescriptionService, times(4)).createPrescription(captor.capture(), eq(Locale.ENGLISH));
        assertThat(captor.getValue().getAsNeeded()).isFalse();
        assertThat(captor.getValue().getPrnMinIntervalHours()).isNull();
    }

    @Test
    void medicationCdsAdvisoriesArePropagated() {
        CdsCard card = new CdsCard("Mild interaction", null, CdsCard.Indicator.INFO,
            new com.example.hms.cdshooks.dto.CdsHookDtos.Source("HMS", null, null),
            null, null, null, UUID.randomUUID().toString());
        PrescriptionResponseDTO resp = new PrescriptionResponseDTO();
        resp.setId(UUID.randomUUID());
        resp.setCdsAdvisories(List.of(card));
        when(prescriptionService.createPrescription(any(), any())).thenReturn(resp);

        DispatchResult result = dispatcher.dispatch(Map.of(
            "orderType", "MEDICATION",
            "medicationName", "Drug X"
        ), ctx(), Locale.ENGLISH);

        assertThat(result.cdsAdvisories()).containsExactly(card);
    }

    @Test
    void dispatchesLabWithDefaultedClinicalIndication() {
        UUID labId = UUID.randomUUID();
        LabOrderResponseDTO resp = new LabOrderResponseDTO();
        resp.setId(labId.toString());
        when(labOrderService.createLabOrder(any(), any(Locale.class))).thenReturn(resp);
        LabTestDefinition def = labDefinitionFor("Blood Culture");
        when(labTestDefinitionRepository.findByNameIgnoreCase("Blood Culture"))
            .thenReturn(Optional.of(def));

        Map<String, Object> item = Map.of(
            "orderType", "LAB",
            "orderName", "Blood Culture",
            "orderCode", "BC2",
            "priority", "STAT"
        );
        DispatchResult result = dispatcher.dispatch(item, ctx(), Locale.ENGLISH);

        assertThat(result.type()).isEqualTo(DispatchResult.Type.LAB);
        assertThat(result.createdId()).isEqualTo(labId);

        ArgumentCaptor<LabOrderRequestDTO> captor = ArgumentCaptor.forClass(LabOrderRequestDTO.class);
        verify(labOrderService).createLabOrder(captor.capture(), eq(Locale.ENGLISH));
        LabOrderRequestDTO sent = captor.getValue();
        assertThat(sent.getTestName()).isEqualTo("Blood Culture");
        assertThat(sent.getPriority()).isEqualTo("STAT");
        assertThat(sent.getClinicalIndication()).contains("Sepsis bundle");
        assertThat(sent.getPrimaryDiagnosisCode()).isEqualTo("A41.9");
        assertThat(sent.getOrderingStaffId()).isEqualTo(STAFF_ID);
        assertThat(sent.getLabTestDefinitionId()).isEqualTo(def.getId());
        assertThat(sent.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(sent.getOrderChannel()).isEqualTo("ELECTRONIC");
        assertThat(sent.getProviderSignature()).contains("Sepsis bundle");
        assertThat(sent.getOrderDatetime()).isNotNull();
    }

    @Test
    void labFallsBackToPlaceholderDiagnosisWhenNoneOnAdmission() {
        UUID labId = UUID.randomUUID();
        LabOrderResponseDTO resp = new LabOrderResponseDTO();
        resp.setId(labId.toString());
        when(labOrderService.createLabOrder(any(), any())).thenReturn(resp);
        when(labTestDefinitionRepository.findByNameIgnoreCase("CBC"))
            .thenReturn(Optional.of(labDefinitionFor("CBC")));

        OrderSetApplicationContext noDiag = new OrderSetApplicationContext(
            ORDER_SET_ID, "Set", null, ADMISSION_ID, PATIENT_ID,
            HOSPITAL_ID, ENCOUNTER_ID, STAFF_ID, ASSIGNMENT_ID, null, false
        );
        dispatcher.dispatch(Map.of("orderType", "LAB", "orderName", "CBC"), noDiag, Locale.ENGLISH);

        ArgumentCaptor<LabOrderRequestDTO> captor = ArgumentCaptor.forClass(LabOrderRequestDTO.class);
        verify(labOrderService).createLabOrder(captor.capture(), any());
        assertThat(captor.getValue().getPrimaryDiagnosisCode()).isEqualTo("Z00.00");
    }

    @Test
    void labSkippedWhenAssignmentMissing() {
        OrderSetApplicationContext noAssignment = new OrderSetApplicationContext(
            ORDER_SET_ID, "Set", null, ADMISSION_ID, PATIENT_ID,
            HOSPITAL_ID, ENCOUNTER_ID, STAFF_ID, null, "A41.9", false
        );
        DispatchResult result = dispatcher.dispatch(
            Map.of("orderType", "LAB", "orderName", "CBC"), noAssignment, Locale.ENGLISH);
        assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
        assertThat(result.skipReason()).contains("assignment");
        verifyNoInteractions(labOrderService);
    }

    @Test
    void labSkippedWhenTestDefinitionNotFound() {
        when(labTestDefinitionRepository.findByNameIgnoreCase("Unknown Panel"))
            .thenReturn(Optional.empty());

        DispatchResult result = dispatcher.dispatch(
            Map.of("orderType", "LAB", "orderName", "Unknown Panel"), ctx(), Locale.ENGLISH);

        assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
        assertThat(result.skipReason()).contains("Unknown Panel");
        verifyNoInteractions(labOrderService);
    }

    @Test
    void imagingSkippedWhenNoEncounterIdInContext() {
        OrderSetApplicationContext noEncounter = new OrderSetApplicationContext(
            ORDER_SET_ID, "Set", null, ADMISSION_ID, PATIENT_ID,
            HOSPITAL_ID, null, STAFF_ID, ASSIGNMENT_ID, "A41.9", false
        );
        DispatchResult result = dispatcher.dispatch(
            Map.of("orderType", "IMAGING", "modality", "CT", "studyType", "CT abdo"),
            noEncounter, Locale.ENGLISH);
        assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
        assertThat(result.skipReason()).contains("encounter");
        verifyNoInteractions(imagingOrderService);
    }

    @Test
    void dispatchesImagingThroughImagingService() {
        UUID imgId = UUID.randomUUID();
        ImagingOrderResponseDTO resp = new ImagingOrderResponseDTO();
        resp.setId(imgId);
        when(imagingOrderService.createOrder(any(), eq(STAFF_ID))).thenReturn(resp);

        Map<String, Object> item = Map.of(
            "orderType", "IMAGING",
            "modality", "CT",
            "studyType", "CT abdomen with contrast",
            "bodyRegion", "abdomen",
            "priority", "URGENT"
        );
        DispatchResult result = dispatcher.dispatch(item, ctx(), Locale.ENGLISH);

        assertThat(result.type()).isEqualTo(DispatchResult.Type.IMAGING);
        assertThat(result.createdId()).isEqualTo(imgId);

        ArgumentCaptor<ImagingOrderRequestDTO> captor = ArgumentCaptor.forClass(ImagingOrderRequestDTO.class);
        verify(imagingOrderService).createOrder(captor.capture(), eq(STAFF_ID));
        ImagingOrderRequestDTO sent = captor.getValue();
        assertThat(sent.getModality()).isEqualTo(ImagingModality.CT);
        assertThat(sent.getStudyType()).isEqualTo("CT abdomen with contrast");
        assertThat(sent.getClinicalQuestion()).contains("Sepsis bundle");
    }

    @Test
    void skipsDietActivityMonitoringWithoutCallingAnyService() {
        for (String type : List.of("DIET", "ACTIVITY", "MONITORING")) {
            DispatchResult result = dispatcher.dispatch(
                Map.of("orderType", type, "value", "x"),
                ctx(),
                Locale.ENGLISH
            );
            assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
            assertThat(result.skipReason()).contains(type);
        }
        verifyNoInteractions(prescriptionService, labOrderService, imagingOrderService);
    }

    @Test
    void skipsItemMissingOrderType() {
        DispatchResult result = dispatcher.dispatch(Map.of("foo", "bar"), ctx(), Locale.ENGLISH);
        assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
        assertThat(result.skipReason()).contains("missing orderType");
        verifyNoInteractions(prescriptionService, labOrderService, imagingOrderService);
    }

    @Test
    void skipsImagingItemWithoutModality() {
        DispatchResult result = dispatcher.dispatch(
            Map.of("orderType", "IMAGING", "studyType", "X-ray"),
            ctx(),
            Locale.ENGLISH
        );
        assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
        verifyNoInteractions(imagingOrderService);
    }

    @Test
    void skipsUnknownOrderType() {
        DispatchResult result = dispatcher.dispatch(
            Map.of("orderType", "MAGIC"),
            ctx(),
            Locale.ENGLISH
        );
        assertThat(result.type()).isEqualTo(DispatchResult.Type.SKIPPED);
        assertThat(result.skipReason()).contains("MAGIC");
    }

    // ═══════════════ QA D1436 (round 34): a dose keeps its unit ═══════════════

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("dosesAndUnits")
    void qaD1436_theDoseReachesTheOrderWithItsUnit(Map<String, Object> dose, String expected) {
        PrescriptionResponseDTO resp = new PrescriptionResponseDTO();
        resp.setId(UUID.randomUUID());
        when(prescriptionService.createPrescription(any(), any(Locale.class))).thenReturn(resp);
        Map<String, Object> item = new java.util.HashMap<>(dose);
        item.put("orderType", "MEDICATION");
        item.put("medicationName", "Morphine");
        item.put("route", "IV");

        dispatcher.dispatch(item, ctx(), Locale.ENGLISH);

        ArgumentCaptor<PrescriptionRequestDTO> captor = ArgumentCaptor.forClass(PrescriptionRequestDTO.class);
        verify(prescriptionService).createPrescription(captor.capture(), eq(Locale.ENGLISH));
        assertThat(captor.getValue().getDosage()).isEqualTo(expected);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> dosesAndUnits() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(Map.of("dose", "2", "doseUnit", "mg"), "2 mg IV"),
            org.junit.jupiter.params.provider.Arguments.of(Map.of("dose", 2, "doseUnit", "mg"), "2 mg IV"),
            org.junit.jupiter.params.provider.Arguments.of(Map.of("dose", "2-4", "doseUnit", "mg"), "2-4 mg IV"),
            org.junit.jupiter.params.provider.Arguments.of(Map.of("dose", "2 mg", "doseUnit", "mg"), "2 mg IV"),
            org.junit.jupiter.params.provider.Arguments.of(Map.of("dose", "2 g"), "2 g IV"),
            org.junit.jupiter.params.provider.Arguments.of(Map.of("doseRangeMin", 2, "doseRangeMax", 4, "doseUnit", "mg"), "2-4 mg IV"),
            org.junit.jupiter.params.provider.Arguments.of(Map.of(), "IV"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("dosesWithoutAUnit")
    void qaD1436_aNumberWithNoUnitIsRefusedByNameAtSaveAndAtApply(Map<String, Object> dose) {
        Map<String, Object> item = new java.util.HashMap<>(dose);
        item.put("orderType", "MEDICATION");
        item.put("medicationName", "Morphine");
        item.put("frequency", "Q6H");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OrderSetItemPrnRules.requireValid(List.of(item)))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .extracting("field").isEqualTo("orderItems[0].doseUnit");
    }

    static java.util.stream.Stream<Map<String, Object>> dosesWithoutAUnit() {
        return java.util.stream.Stream.of(Map.of("dose", "2"), Map.of("dose", 2), Map.of("dose", "2,5"),
            Map.of("doseRangeMin", 2, "doseRangeMax", 4));
    }

    @Test
    void qaD1436_aDoseThatSaysItsOwnUnitOrNoDoseAtAllPasses() {
        for (Map<String, Object> dose : List.<Map<String, Object>>of(Map.of("dose", "1 comprimé"), Map.of("dose", "2", "doseUnit", "mg"), Map.of())) {
            Map<String, Object> item = new java.util.HashMap<>(dose);
            item.put("orderType", "MEDICATION");
            item.put("medicationName", "Morphine");
            item.put("frequency", "Q6H");
            org.assertj.core.api.Assertions.assertThatCode(() -> OrderSetItemPrnRules.requireValid(List.of(item)))
                .doesNotThrowAnyException();
        }
    }
}
