package com.example.hms.service.orderset;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.ImagingModality;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.PrescriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderSetItemDispatcherTest {

    private final PrescriptionService prescriptionService = mock(PrescriptionService.class);
    private final LabOrderService labOrderService = mock(LabOrderService.class);
    private final ImagingOrderService imagingOrderService = mock(ImagingOrderService.class);

    private OrderSetItemDispatcher dispatcher;

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID ENCOUNTER_ID = UUID.randomUUID();
    private static final UUID STAFF_ID = UUID.randomUUID();
    private static final UUID ORDER_SET_ID = UUID.randomUUID();
    private static final UUID ADMISSION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new OrderSetItemDispatcher(prescriptionService, labOrderService, imagingOrderService);
    }

    private OrderSetApplicationContext ctx() {
        return new OrderSetApplicationContext(
            ORDER_SET_ID, "Sepsis bundle", "Sepsis Hour-1",
            ADMISSION_ID, PATIENT_ID, HOSPITAL_ID, ENCOUNTER_ID, STAFF_ID,
            "A41.9", false
        );
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
    }

    @Test
    void labFallsBackToPlaceholderDiagnosisWhenNoneOnAdmission() {
        UUID labId = UUID.randomUUID();
        LabOrderResponseDTO resp = new LabOrderResponseDTO();
        resp.setId(labId.toString());
        when(labOrderService.createLabOrder(any(), any())).thenReturn(resp);

        OrderSetApplicationContext noDiag = new OrderSetApplicationContext(
            ORDER_SET_ID, "Set", null, ADMISSION_ID, PATIENT_ID,
            HOSPITAL_ID, ENCOUNTER_ID, STAFF_ID, null, false
        );
        dispatcher.dispatch(Map.of("orderType", "LAB", "orderName", "CBC"), noDiag, Locale.ENGLISH);

        ArgumentCaptor<LabOrderRequestDTO> captor = ArgumentCaptor.forClass(LabOrderRequestDTO.class);
        verify(labOrderService).createLabOrder(captor.capture(), any());
        assertThat(captor.getValue().getPrimaryDiagnosisCode()).isEqualTo("Z00.00");
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
}
