package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingReadingContextDTO;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * r63 (imaging completion): the radiologist's reading context is read at the
 * order's hospital only, from what the record holds, with nothing graded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("r63: the order's reading context (age, sex, pregnancy, creatinine, prior studies)")
class ImagingReadingContextServiceTest {

    @Mock private ImagingOrderRepository orderRepository;
    @Mock private ImagingReportRepository reportRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private MaternalHistoryRepository maternalHistoryRepository;
    @Mock private RoleValidator roleValidator;
    @org.mockito.Spy private com.example.hms.mapper.LabResultMapper labResultMapper = new com.example.hms.mapper.LabResultMapper();

    @InjectMocks private ImagingReadingContextService service;

    private final UUID hospitalId = UUID.randomUUID();
    private Hospital hospital;
    private Patient patient;
    private ImagingOrder current;
    private final List<LabResult> labRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.now().minusYears(36).minusDays(3));
        patient.setGender("FEMALE");
        current = order(LocalDateTime.of(2026, 11, 19, 8, 0));
        when(orderRepository.findById(current.getId())).thenReturn(Optional.of(current));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        com.example.hms.testsupport.ImagingRolesHeld.whereverTheTokenNamesThem(roleValidator);
        when(labResultRepository.findReleasedCreatinineCandidates(eq(patient.getId()), eq(hospitalId), any(), any()))
            .thenAnswer(inv -> labRows);
        when(orderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(eq(patient.getId()), any()))
            .thenReturn(List.of(current));
        when(maternalHistoryRepository.findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(
            eq(patient.getId()), any())).thenReturn(Optional.empty());
    }

    private ImagingOrder order(LocalDateTime orderedAt) {
        ImagingOrder o = new ImagingOrder();
        o.setId(UUID.randomUUID());
        o.setPatient(patient);
        o.setHospital(hospital);
        o.setModality(ImagingModality.XRAY);
        o.setStudyType("Radiographie thorax");
        o.setOrderedAt(orderedAt);
        o.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);
        return o;
    }

    private LabResult creatinine(String name, String value, LocalDateTime at) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName(name);
        LabOrder labOrder = new LabOrder();
        labOrder.setId(UUID.randomUUID());
        labOrder.setLabTestDefinition(definition);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(labOrder);
        result.setResultValue(value);
        result.setResultUnit("mg/L");
        result.setResultDate(at);
        result.setReleased(true);
        labRows.add(result);
        return result;
    }

    @Test
    @DisplayName("QA D2915: a clinical role held at another hospital reads no patient data here")
    void d2915_aClinicalRoleHeldElsewhereReadsNoContextHere() {
        when(roleValidator.readsImagingClinically(any(), eq(hospitalId))).thenReturn(false);

        assertThatThrownBy(() -> service.contextOf(current.getId())).isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        verify(labResultRepository, never()).findReleasedCreatinineCandidates(any(), any(), any(), any());
    }

    @Test
    @DisplayName("another hospital's order answers as a missing id, before any patient data is read")
    void anotherHospitalsOrderIsMissing() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.contextOf(current.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(labResultRepository, never()).findReleasedCreatinineCandidates(any(), any(), any(), any());
        verify(maternalHistoryRepository, never())
            .findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(any(), anyCollection());
    }

    @Test
    @DisplayName("age in whole years and the recorded sex")
    void ageAndSex() {
        ImagingReadingContextDTO ctx = service.contextOf(current.getId());

        assertThat(ctx.ageYears()).isEqualTo(36);
        assertThat(ctx.sex()).isEqualTo("FEMALE");
        assertThat(ctx.pregnancy()).isNull();
        assertThat(ctx.creatinine()).isNull();
    }

    @Test
    @DisplayName("the latest serum creatinine, never a clearance or a urine value, read at the order's hospital")
    void latestSerumCreatinine() {
        creatinine("Clairance de la créatinine", "95", LocalDateTime.of(2026, 11, 18, 8, 0));
        creatinine("Créatinine urinaire", "1200", LocalDateTime.of(2026, 11, 17, 8, 0));
        creatinine("Créatinine", "9,5", LocalDateTime.of(2026, 11, 10, 8, 0));

        ImagingReadingContextDTO ctx = service.contextOf(current.getId());

        assertThat(ctx.creatinine().value()).isEqualTo("9,5");
        assertThat(ctx.creatinine().unit()).isEqualTo("mg/L");
        assertThat(ctx.creatinine().corrected()).isFalse();
        verify(labResultRepository).findReleasedCreatinineCandidates(eq(patient.getId()), eq(hospitalId),
            eq(ImagingReadingContextService.CREATININE_LOINC), any());
    }

    @Test
    @DisplayName("QA D2640: the creatinine reads as the laboratory graded it, with its range, when no flag was stored")
    void d2640_creatinineCarriesTheLabGradeAndRange() {
        LabResult row = creatinine("Créatinine", "118", LocalDateTime.of(2026, 11, 20, 0, 0));
        row.setResultUnit("µmol/L");
        row.getLabOrder().getLabTestDefinition().setReferenceRanges(new ArrayList<>(List.of(
            com.example.hms.model.LabTestReferenceRange.builder().minValue(62.0).maxValue(106.0).unit("µmol/L").build())));
        assertThat(row.getAbnormalFlag()).as("the release path stores no flag on a range-graded row").isNull();

        ImagingReadingContextDTO ctx = service.contextOf(current.getId());

        assertThat(ctx.creatinine().severityFlag()).isEqualTo("HIGH");
        assertThat(ctx.creatinine().referenceRange()).isEqualTo("62 - 106 µmol/L");
    }

    @Test
    @DisplayName("QA D2640: a creatinine no range grades says so (UNSPECIFIED), never a made-up grade")
    void d2640_ungradedCreatinineSaysSo() {
        creatinine("Créatinine", "118", LocalDateTime.of(2026, 11, 20, 0, 0));

        ImagingReadingContextDTO ctx = service.contextOf(current.getId());

        assertThat(ctx.creatinine().severityFlag()).isEqualTo("UNSPECIFIED");
        assertThat(ctx.creatinine().referenceRange()).isNull();
    }

    @Test
    @DisplayName("a pregnancy with a due date ahead is shown as recorded, with the history's date")
    void recordedPregnancy() {
        MaternalHistory history = new MaternalHistory();
        history.setEstimatedDueDate(LocalDate.now().plusMonths(3));
        history.setRecordedDate(LocalDateTime.of(2026, 10, 1, 10, 0));
        when(maternalHistoryRepository.findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(
            patient.getId(), List.of(hospitalId))).thenReturn(Optional.of(history));

        ImagingReadingContextDTO ctx = service.contextOf(current.getId());

        assertThat(ctx.pregnancy().ongoing()).isTrue();
        assertThat(ctx.pregnancy().recordedAt()).isEqualTo(history.getRecordedDate());
    }

    @Test
    @DisplayName("a due date already past is reported as recorded, not ongoing")
    void pastDueDateIsNotOngoing() {
        MaternalHistory history = new MaternalHistory();
        history.setEstimatedDueDate(LocalDate.now().minusMonths(5));
        when(maternalHistoryRepository.findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(
            patient.getId(), List.of(hospitalId))).thenReturn(Optional.of(history));

        assertThat(service.contextOf(current.getId()).pregnancy().ongoing()).isFalse();
    }

    @Test
    @DisplayName("every prior study here, newest first, without this one, with its signed conclusion (not the addendum)")
    void priorStudies() {
        ImagingOrder older = order(LocalDateTime.of(2026, 10, 1, 8, 0));
        ImagingOrder unread = order(LocalDateTime.of(2026, 11, 1, 8, 0));
        unread.setStatus(ImagingOrderStatus.COMPLETED);
        when(orderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patient.getId(), List.of(hospitalId)))
            .thenReturn(List.of(current, unread, older));
        ImagingReport original = new ImagingReport();
        original.setId(UUID.randomUUID());
        original.setImagingOrder(older);
        original.setReportVersion(1);
        original.setReportStatus(ImagingReportStatus.FINAL);
        original.setSignedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        original.setImpression("Ancien cliché normal.");
        ImagingReport addendum = new ImagingReport();
        addendum.setId(UUID.randomUUID());
        addendum.setImagingOrder(older);
        addendum.setReportVersion(2);
        addendum.setReportStatus(ImagingReportStatus.ADDENDUM);
        addendum.setSignedAt(LocalDateTime.of(2026, 10, 2, 12, 0));
        addendum.setImpression("Complément.");
        when(reportRepository.findByImagingOrder_IdIn(anyCollection())).thenReturn(List.of(addendum, original));

        List<ImagingReadingContextDTO.PriorStudy> prior = service.contextOf(current.getId()).priorStudies();

        assertThat(prior).extracting(ImagingReadingContextDTO.PriorStudy::imagingOrderId)
            .containsExactly(unread.getId(), older.getId());
        assertThat(prior.get(0).reportId()).isNull();
        assertThat(prior.get(1).reportId()).isEqualTo(original.getId());
        assertThat(prior.get(1).impression()).isEqualTo("Ancien cliché normal.");
    }
}
