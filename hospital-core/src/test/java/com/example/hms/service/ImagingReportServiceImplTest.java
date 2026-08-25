package com.example.hms.service;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImagingReportMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.model.Organization;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.impl.ImagingReportServiceImpl;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract for the radiology reading room (Tier 2 item 26).
 *
 * <p>The previous version of this file tested {@code createReport} and
 * {@code updateReport} — methods that at the time had no endpoint and could
 * never run in production — and asserted the very behaviours this PR removed:
 * that a caller could name the staff member on a status-history row, and that
 * a request body could set {@code latestVersion} and reach FINAL through the
 * status endpoint. The tests passed and the feature did not exist.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImagingReportServiceImplTest {

    @Mock
    private ImagingReportRepository imagingReportRepository;
    @Mock
    private ImagingOrderRepository imagingOrderRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private RoleValidator roleValidator;
    @Spy
    private ImagingReportMapper imagingReportMapper = new ImagingReportMapper();

    @InjectMocks
    private ImagingReportServiceImpl imagingReportService;

    private UUID hospitalId;
    private UUID orderId;
    private UUID reportId;
    private UUID callerUserId;
    private Hospital hospital;
    private ImagingOrder imagingOrder;
    private Staff caller;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();

        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setOrganization(organization);

        imagingOrder = new ImagingOrder();
        imagingOrder.setId(orderId);
        imagingOrder.setHospital(hospital);
        imagingOrder.setStatus(ImagingOrderStatus.COMPLETED);

        User user = new User();
        user.setFirstName("Dr.");
        user.setLastName("Radiologist");
        caller = new Staff();
        caller.setId(UUID.randomUUID());
        caller.setHospital(hospital);
        caller.setUser(user);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(callerUserId);
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.of(caller));
        when(imagingOrderRepository.findById(orderId)).thenReturn(Optional.of(imagingOrder));
        when(imagingReportRepository.save(any(ImagingReport.class))).thenAnswer(i -> i.getArgument(0));
        doReturn(ImagingReportResponseDTO.builder().build())
            .when(imagingReportMapper).toResponseDTO(any(ImagingReport.class));
    }

    private ImagingReport persistedReport() {
        ImagingReport report = new ImagingReport();
        report.setId(reportId);
        report.setImagingOrder(imagingOrder);
        report.setHospital(hospital);
        report.setReportStatus(ImagingReportStatus.PRELIMINARY);
        report.setStatusHistory(new ArrayList<>());
        when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        return report;
    }

    private ImagingReport capturedNewReport(ImagingReport... excluding) {
        ArgumentCaptor<ImagingReport> captor = ArgumentCaptor.forClass(ImagingReport.class);
        verify(imagingReportRepository, atLeastOnce()).save(captor.capture());
        List<ImagingReport> excluded = List.of(excluding);
        return captor.getAllValues().stream()
            .filter(report -> excluded.stream().noneMatch(e -> e == report))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    // ── Authoring ────────────────────────────────────────────────────────

    @Test
    void createReportDerivesVersionAndDemotesExistingLatest() {
        ImagingReport existingLatest = new ImagingReport();
        existingLatest.setId(UUID.randomUUID());
        existingLatest.setImagingOrder(imagingOrder);
        existingLatest.setHospital(hospital);
        existingLatest.setReportVersion(1);
        existingLatest.setLatestVersion(true);
        existingLatest.setSignedAt(LocalDateTime.now());

        when(imagingReportRepository.findByReportNumberAndHospital_Id("IR-100", hospitalId)).thenReturn(Optional.empty());
        when(imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(orderId))
            .thenReturn(Optional.of(existingLatest));
        when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId))
            .thenReturn(Optional.of(existingLatest));
        when(imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(orderId))
            .thenReturn(List.of(existingLatest));

        imagingReportService.createReport(ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId)
            .reportNumber("IR-100")
            .modality(ImagingModality.MRI)
            .reportStatus(ImagingReportStatus.ADDENDUM)
            .build());

        assertThat(existingLatest.getLatestVersion()).isFalse();
        ImagingReport created = capturedNewReport(existingLatest);
        assertThat(created.getReportVersion()).isEqualTo(2);
        assertThat(created.getLatestVersion()).isTrue();
        // Hospital comes from the ORDER; the request can no longer name one.
        assertThat(created.getHospital()).isSameAs(hospital);
        assertThat(created.getCreatedBy()).isEqualTo(callerUserId);
    }

    @Test
    void createReportDefaultsToPreliminaryAndAttributesTheAuthor() {
        when(imagingReportRepository.findTopByImagingOrder_IdOrderByReportVersionDesc(orderId)).thenReturn(Optional.empty());
        when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId)).thenReturn(Optional.empty());

        imagingReportService.createReport(ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId)
            .findings("No acute intracranial abnormality.")
            .build());

        ImagingReport created = capturedNewReport();
        assertThat(created.getReportStatus()).isEqualTo(ImagingReportStatus.PRELIMINARY);
        assertThat(created.getReportVersion()).isEqualTo(1);
        // Interpreting provider defaults to the authenticated author.
        assertThat(created.getInterpretingProvider()).isSameAs(caller);
    }

    @Test
    void createReportRefusesFinalBecauseSigningIsTheOnlyPathToIt() {
        assertThatThrownBy(() -> imagingReportService.createReport(ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId)
            .reportStatus(ImagingReportStatus.FINAL)
            .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("FINAL is reached only by signing");
    }

    @Test
    void createReportRefusesAFreshPreliminaryOverAnAlreadySignedRead() {
        ImagingReport signed = new ImagingReport();
        signed.setId(UUID.randomUUID());
        signed.setSignedAt(LocalDateTime.now());
        when(imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(orderId)).thenReturn(List.of(signed));

        assertThatThrownBy(() -> imagingReportService.createReport(ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId)
            .reportStatus(ImagingReportStatus.PRELIMINARY)
            .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ADDENDUM, CORRECTED or AMENDED");
    }

    @Test
    void updateReportRefusesOnceSigned() {
        ImagingReport report = persistedReport();
        report.setSignedAt(LocalDateTime.now());

        assertThatThrownBy(() -> imagingReportService.updateReport(reportId,
            ImagingReportUpsertRequestDTO.builder().findings("revised").build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("can no longer be edited");
    }

    @Test
    void updateReportRefusesToMoveAReportToAnotherOrder() {
        persistedReport();

        assertThatThrownBy(() -> imagingReportService.updateReport(reportId,
            ImagingReportUpsertRequestDTO.builder().imagingOrderId(UUID.randomUUID()).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be moved to a different imaging order");
    }

    @Test
    void criticalFlagIsSetOnlyAndCannotBeWithdrawn() {
        ImagingReport report = persistedReport();
        LocalDateTime flaggedAt = LocalDateTime.now().minusHours(2);
        report.setCriticalResultFlaggedAt(flaggedAt);

        imagingReportService.updateReport(reportId,
            ImagingReportUpsertRequestDTO.builder().criticalFinding(Boolean.FALSE).build());

        assertThat(report.getCriticalResultFlaggedAt()).isEqualTo(flaggedAt);
    }

    // ── Signing ──────────────────────────────────────────────────────────

    @Test
    void signStampsServerIdentityDigestAndFinalStatus() {
        ImagingReport report = persistedReport();
        report.setImpression("Acute appendicitis.");

        imagingReportService.signReport(reportId);

        assertThat(report.getReportStatus()).isEqualTo(ImagingReportStatus.FINAL);
        assertThat(report.getSignedBy()).isSameAs(caller);
        assertThat(report.getSignedAt()).isNotNull();
        assertThat(report.getLockedForEditing()).isTrue();
        assertThat(report.getSignatureAlgorithm()).isEqualTo("SHA-256");
        // 64 hex characters — a real SHA-256, not a placeholder.
        assertThat(report.getSignatureValue()).hasSize(64).matches("[0-9a-f]+");
        // The ordering clinician's signal that the read landed.
        assertThat(imagingOrder.getStatus()).isEqualTo(ImagingOrderStatus.RESULTS_AVAILABLE);
    }

    @Test
    void signRefusesWithoutAnImpression() {
        ImagingReport report = persistedReport();
        report.setFindings("Some findings but no conclusion.");

        assertThatThrownBy(() -> imagingReportService.signReport(reportId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("without an impression");
    }

    @Test
    void signRefusesToReissueASignature() {
        ImagingReport report = persistedReport();
        report.setImpression("Acute appendicitis.");
        report.setSignedAt(LocalDateTime.now());

        assertThatThrownBy(() -> imagingReportService.signReport(reportId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been signed");
    }

    @Test
    void signRefusesWhenTheCallerHasNoStaffProfileAtThisHospital() {
        ImagingReport report = persistedReport();
        report.setImpression("Acute appendicitis.");
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imagingReportService.signReport(reportId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void signDoesNotWalkACancelledOrderBackIntoAnActiveState() {
        ImagingReport report = persistedReport();
        report.setImpression("Study aborted, patient could not tolerate.");
        imagingOrder.setStatus(ImagingOrderStatus.CANCELLED);

        imagingReportService.signReport(reportId);

        assertThat(imagingOrder.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
        verify(imagingOrderRepository, never()).save(any(ImagingOrder.class));
    }

    // ── Critical findings ────────────────────────────────────────────────

    @Test
    void acknowledgeStampsTheAuthenticatedCallerNotARequestParameter() {
        ImagingReport report = persistedReport();
        report.setCriticalResultFlaggedAt(LocalDateTime.now().minusMinutes(5));

        imagingReportService.acknowledgeCriticalResult(reportId);

        assertThat(report.getCriticalResultAcknowledgedBy()).isSameAs(caller);
        assertThat(report.getCriticalResultAcknowledgedAt()).isNotNull();
        ImagingReportStatusHistory history = report.getStatusHistory().get(0);
        assertThat(history.getChangedBy()).isSameAs(caller);
        assertThat(history.getChangedByName()).isEqualTo("Dr. Radiologist");
    }

    @Test
    void acknowledgeRefusesWhenNoCriticalFindingWasEverRaised() {
        persistedReport();

        assertThatThrownBy(() -> imagingReportService.acknowledgeCriticalResult(reportId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("no critical finding to acknowledge");
    }

    @Test
    void acknowledgeRefusesASecondReceipt() {
        ImagingReport report = persistedReport();
        report.setCriticalResultFlaggedAt(LocalDateTime.now().minusMinutes(10));
        report.setCriticalResultAcknowledgedAt(LocalDateTime.now().minusMinutes(1));
        report.setCriticalResultAcknowledgedBy(caller);

        assertThatThrownBy(() -> imagingReportService.acknowledgeCriticalResult(reportId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been acknowledged");
    }

    // ── Administrative status ────────────────────────────────────────────

    @Test
    void updateReportStatusRefusesContentStatesAndFinal() {
        ImagingReportStatusUpdateRequestDTO request = ImagingReportStatusUpdateRequestDTO.builder()
            .status(ImagingReportStatus.FINAL)
            .statusReason("Reviewed and signed")
            .build();

        assertThatThrownBy(() -> imagingReportService.updateReportStatus(reportId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not an administrative outcome");
    }

    @Test
    void updateReportStatusRequiresAReason() {
        ImagingReportStatusUpdateRequestDTO request = ImagingReportStatusUpdateRequestDTO.builder()
            .status(ImagingReportStatus.CANCELLED)
            .build();

        assertThatThrownBy(() -> imagingReportService.updateReportStatus(reportId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason is required");
    }

    @Test
    void updateReportStatusVoidsAnUnsignedReportAndRecordsWho() {
        ImagingReport report = persistedReport();

        imagingReportService.updateReportStatus(reportId, ImagingReportStatusUpdateRequestDTO.builder()
            .status(ImagingReportStatus.CANCELLED)
            .statusReason("Study repeated, this acquisition is unusable")
            .notes("Motion artefact throughout")
            .build());

        assertThat(report.getReportStatus()).isEqualTo(ImagingReportStatus.CANCELLED);
        ImagingReportStatusHistory history = report.getStatusHistory().get(0);
        assertThat(history.getChangedBy()).isSameAs(caller);
        assertThat(history.getStatusReason()).isEqualTo("Study repeated, this acquisition is unusable");
    }

    @Test
    void updateReportStatusRefusesToVoidASignedReport() {
        ImagingReport report = persistedReport();
        report.setSignedAt(LocalDateTime.now());

        assertThatThrownBy(() -> imagingReportService.updateReportStatus(reportId,
            ImagingReportStatusUpdateRequestDTO.builder()
                .status(ImagingReportStatus.CANCELLED)
                .statusReason("Wrong patient")
                .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Supersede it with a corrected report");
    }

    // ── Tenancy ──────────────────────────────────────────────────────────

    @Test
    void aReportAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        ImagingReport foreign = new ImagingReport();
        foreign.setId(reportId);
        foreign.setHospital(other);
        when(imagingReportRepository.findById(reportId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> imagingReportService.getReport(reportId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listingRefusesAHospitalIdThatIsNotTheCallersActiveHospital() {
        UUID foreignHospitalId = UUID.randomUUID();

        assertThatThrownBy(() ->
            imagingReportService.getReportsByHospitalAndStatus(foreignHospitalId, ImagingReportStatus.FINAL))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void superAdminWithNoActiveHospitalMayNameOne() {
        UUID anyHospitalId = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(imagingReportRepository.findByHospital_IdAndReportStatusOrderByPerformedAtDesc(
            anyHospitalId, ImagingReportStatus.FINAL)).thenReturn(List.of());

        assertThat(imagingReportService.getReportsByHospitalAndStatus(anyHospitalId, ImagingReportStatus.FINAL))
            .isEmpty();
    }
}
