package com.example.hms.service.signature;

import com.example.hms.enums.SignatureType;
import com.example.hms.model.Consultation;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Prescription;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.model.encounter.EncounterNote;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.DischargeSummaryRepository;
import com.example.hms.repository.EncounterNoteRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignableReportLocatorTest {

    private final DischargeSummaryRepository dischargeSummaries = mock(DischargeSummaryRepository.class);
    private final LabResultRepository labResults = mock(LabResultRepository.class);
    private final ImagingReportRepository imagingReports = mock(ImagingReportRepository.class);
    private final ConsultationRepository consultations = mock(ConsultationRepository.class);
    private final EncounterNoteRepository encounterNotes = mock(EncounterNoteRepository.class);
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final ProcedureOrderRepository procedureOrders = mock(ProcedureOrderRepository.class);

    private final SignableReportLocator locator = new SignableReportLocator(
        dischargeSummaries, labResults, imagingReports, consultations,
        encounterNotes, prescriptions, procedureOrders);

    private final UUID reportId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospital = mock(Hospital.class);
        when(hospital.getId()).thenReturn(hospitalId);
    }

    @Test
    void nullTypeOrIdResolvesToEmptyWithoutAQuery() {
        assertThat(locator.owningHospitalId(null, reportId)).isEmpty();
        assertThat(locator.owningHospitalId(SignatureType.LAB_RESULT, null)).isEmpty();
        verifyNoInteractions(labResults);
    }

    @Test
    void dischargeSummaryResolvesItsHospital() {
        DischargeSummary entity = mock(DischargeSummary.class);
        when(entity.getHospital()).thenReturn(hospital);
        when(dischargeSummaries.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.DISCHARGE_SUMMARY, reportId)).contains(hospitalId);
    }

    @Test
    void labResultResolvesThroughItsOrder() {
        LabOrder order = mock(LabOrder.class);
        when(order.getHospital()).thenReturn(hospital);
        LabResult entity = mock(LabResult.class);
        when(entity.getLabOrder()).thenReturn(order);
        when(labResults.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.LAB_RESULT, reportId)).contains(hospitalId);
    }

    @Test
    void labResultWithoutAnOrderHasNoHospital() {
        LabResult entity = mock(LabResult.class);
        when(labResults.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.LAB_RESULT, reportId)).isEmpty();
    }

    @Test
    void imagingReportResolvesItsHospital() {
        ImagingReport entity = mock(ImagingReport.class);
        when(entity.getHospital()).thenReturn(hospital);
        when(imagingReports.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.IMAGING_REPORT, reportId)).contains(hospitalId);
    }

    @Test
    void consultationResolvesItsHospital() {
        Consultation entity = mock(Consultation.class);
        when(entity.getHospital()).thenReturn(hospital);
        when(consultations.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.CONSULTATION_NOTE, reportId)).contains(hospitalId);
    }

    @ParameterizedTest
    @EnumSource(value = SignatureType.class, names = {"PROGRESS_NOTE", "ED_NOTE"})
    void encounterNotesResolveTheirHospital(SignatureType type) {
        EncounterNote entity = mock(EncounterNote.class);
        when(entity.getHospital()).thenReturn(hospital);
        when(encounterNotes.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(type, reportId)).contains(hospitalId);
    }

    @Test
    void medicationOrderResolvesItsHospital() {
        Prescription entity = mock(Prescription.class);
        when(entity.getHospital()).thenReturn(hospital);
        when(prescriptions.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.MEDICATION_ORDER, reportId)).contains(hospitalId);
    }

    @Test
    void procedureReportResolvesItsHospital() {
        ProcedureOrder entity = mock(ProcedureOrder.class);
        when(entity.getHospital()).thenReturn(hospital);
        when(procedureOrders.findById(reportId)).thenReturn(Optional.of(entity));
        assertThat(locator.owningHospitalId(SignatureType.PROCEDURE_REPORT, reportId)).contains(hospitalId);
    }

    @Test
    void aMissingRecordResolvesToEmpty() {
        when(prescriptions.findById(any())).thenReturn(Optional.empty());
        assertThat(locator.owningHospitalId(SignatureType.MEDICATION_ORDER, reportId)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SignatureType.class,
        names = {"OPERATIVE_NOTE", "PATHOLOGY_REPORT", "CARE_PLAN", "GENERIC_REPORT"})
    void typesWithoutAStoreCannotBeLocated(SignatureType type) {
        assertThat(locator.owningHospitalId(type, reportId)).isEmpty();
    }
}
