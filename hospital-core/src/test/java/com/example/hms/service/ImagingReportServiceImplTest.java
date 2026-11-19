package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportHistoryEvent;
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
import com.example.hms.payload.dto.imaging.ImagingReportStatusDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.PatientRepository;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    // QA D1232 (round 24): the refusals are message keys now; the assertions
    // read them in English through the real bundles (set up in setUp).
    @org.junit.jupiter.api.AfterEach
    void resetRefusalLocale() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        com.example.hms.utility.MessageUtil.setMessageSource(null);
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

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
    /** Item 27's chain. Its own contract lives in ImagingCriticalNotificationServiceTest. */
    @Mock
    private ImagingCriticalNotificationService criticalNotificationService;
    @Spy
    private ImagingReportMapper imagingReportMapper = new ImagingReportMapper();

    @InjectMocks
    private ImagingReportServiceImpl imagingReportService;

    /**
     * The real subject guard. These tests set no authentication, so it waves
     * every read through, as it does for any caller that is not patient-only;
     * the patient cases are in PatientSubjectReadGuardTest and the
     * per-service ownership tests.
     */
    @Spy
    private PatientSubjectReadGuard subjectReadGuard =
        new PatientSubjectReadGuard(mock(ControllerAuthUtils.class), mock(PatientRepository.class));

    private UUID hospitalId;
    private UUID orderId;
    private UUID reportId;
    private UUID callerUserId;
    private Hospital hospital;
    private ImagingOrder imagingOrder;
    private Staff caller;

    @BeforeEach
    void setUp() {
        com.example.hms.utility.MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.ENGLISH);
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
            .findings("Pas de lésion") // QA D1150: a report says what the study showed
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
            .hasMessageContaining("A final report requires signing");
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> forbiddenAuthoringStatuses() {
        return java.util.stream.Stream.of("en", "fr", "es").flatMap(language ->
            java.util.stream.Stream.of(false, true).flatMap(update ->
                java.util.stream.Stream.of(ImagingReportStatus.FINAL, ImagingReportStatus.CANCELLED, ImagingReportStatus.ERROR)
                    .map(status -> org.junit.jupiter.params.provider.Arguments.of(language, update, status))));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("forbiddenAuthoringStatuses")
    void authoringRefusalsAreLocalizedWithoutChangingTheStatus(String language, boolean update, ImagingReportStatus status) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        ImagingReport existing = persistedReport();
        var request = ImagingReportUpsertRequestDTO.builder().imagingOrderId(orderId)
            .findings("User-authored text").reportStatus(status).build();
        String expected = switch (language) {
            case "fr" -> "Ce statut ne peut pas être défini pendant la rédaction";
            case "es" -> "Este estado no se puede establecer al redactar";
            default -> "This status cannot be set while authoring a report";
        };
        assertThatThrownBy(() -> {
            if (update) imagingReportService.updateReport(reportId, request);
            else imagingReportService.createReport(request);
        }).isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, ex -> {
            assertThat(ex.getMessage()).startsWith(expected);
            assertThat(ex.getFieldErrors()).containsEntry("reportStatus", ex.getMessage());
        });
        verify(imagingReportRepository, never()).save(any());
        assertThat(existing.getReportStatus()).isEqualTo(ImagingReportStatus.PRELIMINARY);
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
            .hasMessageContaining("an addendum, a corrected or an amended version");
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

        // QA D1182 (round 22, changed request): an edit carries the read; the
        // flag alone would blank every finding and is now refused.
        imagingReportService.updateReport(reportId,
            ImagingReportUpsertRequestDTO.builder().impression("Normal study.").criticalFinding(Boolean.FALSE).build());

        assertThat(report.getCriticalResultFlaggedAt()).isEqualTo(flaggedAt);
    }

    /** QA D1182 class (round 22): the edit of a read is held to the same findings rule as its create. */
    @Test
    void anEditThatBlanksTheReadIsRefusedAndNothingIsSaved_D1182() {
        ImagingReport report = persistedReport();
        report.setImpression("Normal study.");

        assertThatThrownBy(() -> imagingReportService.updateReport(reportId,
            ImagingReportUpsertRequestDTO.builder().contrastAdministered(Boolean.TRUE).build()))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("imaging.report.findingsRequired");
        verify(imagingReportRepository, never()).save(any(ImagingReport.class));

        imagingReportService.updateReport(reportId, ImagingReportUpsertRequestDTO.builder().findings("Small effusion").build());
        verify(imagingReportRepository).save(report);
    }

    /** QA D1098 (round 19): reports were written and signed FINAL on cancelled orders. */
    @Test
    void noReportIsWrittenRevisedOrSignedOnACancelledOrder_D1098() {
        imagingOrder.setStatus(ImagingOrderStatus.CANCELLED);
        ImagingReport report = persistedReport();
        report.setImpression("Normal study.");

        ImagingReportUpsertRequestDTO create = ImagingReportUpsertRequestDTO.builder()
            .imagingOrderId(orderId).findings("x").build();
        ImagingReportUpsertRequestDTO revise = ImagingReportUpsertRequestDTO.builder().findings("revised").build();
        assertThatThrownBy(() -> imagingReportService.createReport(create))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("imaging.order.cancelled.locked");
        assertThatThrownBy(() -> imagingReportService.updateReport(reportId, revise))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("imaging.order.cancelled.locked");
        assertThatThrownBy(() -> imagingReportService.signReport(reportId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("imaging.order.cancelled.locked");
        assertThat(report.getSignedAt()).isNull();
        assertThat(imagingOrder.getStatus()).isEqualTo(ImagingOrderStatus.CANCELLED);
    }

    // ── Signing ──────────────────────────────────────────────────────────

    /** QA D1930 (r54): signing a revision keeps its kind; only an original read becomes FINAL. */
    @Test
    void signingKeepsTheReportKind_D1930() {
        for (ImagingReportStatus kind : List.of(ImagingReportStatus.ADDENDUM, ImagingReportStatus.CORRECTED,
                ImagingReportStatus.AMENDED, ImagingReportStatus.PRELIMINARY, ImagingReportStatus.DRAFT)) {
            ImagingReport report = persistedReport();
            report.setReportStatus(kind);
            report.setImpression("Inchangé.");

            imagingReportService.signReport(reportId);

            ImagingReportStatus expected = kind == ImagingReportStatus.PRELIMINARY || kind == ImagingReportStatus.DRAFT
                ? ImagingReportStatus.FINAL : kind;
            assertThat(report.getReportStatus()).as("signed " + kind).isEqualTo(expected);
            assertThat(report.getStatusHistory()).as("history of " + kind)
                .anyMatch(h -> h.getStatus() == expected);
        }
    }

    /** QA D1930 class: an edit cannot turn an addendum into an original read (it would sign FINAL), nor back. */
    @Test
    void anEditKeepsTheReportKind_D1930() {
        ImagingReport report = persistedReport();
        report.setReportStatus(ImagingReportStatus.ADDENDUM);
        report.setFindings("f");
        report.setImpression("i");
        assertThatThrownBy(() -> imagingReportService.updateReport(reportId, ImagingReportUpsertRequestDTO.builder()
                .imagingOrderId(orderId).findings("f").impression("i")
                .reportStatus(ImagingReportStatus.PRELIMINARY).build()))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("imaging.report.kindFixed"));
        assertThat(report.getReportStatus()).isEqualTo(ImagingReportStatus.ADDENDUM);
    }

    /** QA D1930 class: a read written on a signed study with no status named is not a second original. */
    @Test
    void aReadWithNoStatusOnASignedStudyIsRefused_D1930() {
        ImagingReport signed = new ImagingReport();
        signed.setId(UUID.randomUUID());
        signed.setSignedAt(java.time.LocalDateTime.now());
        signed.setReportStatus(ImagingReportStatus.FINAL);
        when(imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(orderId)).thenReturn(List.of(signed));
        assertThatThrownBy(() -> imagingReportService.createReport(ImagingReportUpsertRequestDTO.builder()
                .imagingOrderId(orderId).findings("f").impression("i").build()))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("imaging.report.studyAlreadySigned"));
    }

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

    /**
     * r44: the order drawer read "Terminée le —" on a RESULTS_AVAILABLE order
     * whose report was signed at 08:07. Signing fills the order's completion
     * time: the study's own when the report records it, else the signature.
     */
    @Test
    void signFillsTheOrdersCompletionTime_r44() {
        ImagingReport report = persistedReport();
        report.setImpression("Normal chest.");
        imagingOrder.setStatus(ImagingOrderStatus.IN_PROGRESS);

        imagingReportService.signReport(reportId);

        assertThat(imagingOrder.getStatus()).isEqualTo(ImagingOrderStatus.RESULTS_AVAILABLE);
        assertThat(imagingOrder.getCompletedAt()).isNotNull().isEqualTo(report.getSignedAt());
    }

    @Test
    void signUsesTheStudysRecordedCompletionAndKeepsOneAlreadyRecorded_r44() {
        ImagingReport report = persistedReport();
        report.setImpression("Normal chest.");
        LocalDateTime performed = LocalDateTime.of(2026, 11, 13, 7, 40);
        report.setPerformedAt(performed);

        imagingReportService.signReport(reportId);
        assertThat(imagingOrder.getCompletedAt()).isEqualTo(performed);

        // An addendum signed later on the resulted order moves nothing.
        ImagingReport addendum = persistedReport();
        addendum.setImpression("Addendum.");
        imagingReportService.signReport(reportId);
        assertThat(imagingOrder.getCompletedAt()).isEqualTo(performed);
    }

    @Test
    void signRecordsAMissingCompletionOnAnAlreadyResultedOrder_r44() {
        ImagingReport report = persistedReport();
        report.setImpression("Normal chest.");
        imagingOrder.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);

        imagingReportService.signReport(reportId);

        assertThat(imagingOrder.getCompletedAt()).isEqualTo(report.getSignedAt());
        verify(imagingOrderRepository).save(imagingOrder);
    }

    /** r44 (D1574 class): the history carries a code the portal translates, not English. */
    @Test
    void historyRowsTheServerWritesCarryAnEventCode_r44() {
        ImagingReport report = persistedReport();
        report.setImpression("Normal chest.");

        imagingReportService.signReport(reportId);
        ImagingReportResponseDTO signed = new ImagingReportMapper().toResponseDTO(report);

        assertThat(signed.getStatusHistory())
            .extracting(ImagingReportStatusDTO::getEventCode)
            .contains(ImagingReportHistoryEvent.SIGNED);
        assertThat(ImagingReportHistoryEvent.fromStoredText("Report authored"))
            .isEqualTo(ImagingReportHistoryEvent.AUTHORED);
        assertThat(ImagingReportHistoryEvent.fromStoredText("Duplicate study")).isNull();
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

        // QA D1098 (round 19): the signature itself is now refused on a cancelled
        // order (it used to be accepted, the order merely staying cancelled).
        assertThatThrownBy(() -> imagingReportService.signReport(reportId))
            .isInstanceOf(BusinessException.class);

        assertThat(report.getSignedAt()).isNull();
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

    // ═══════════════ QA D930 class (round 16 siblings): the report's author ═══════════════

    private ImagingReport reportReadBy(UUID interpreterUserId, UUID filedBy) {
        ImagingReport report = persistedReport();
        report.setImpression("No acute finding");
        if (interpreterUserId != null) {
            User user = new User();
            user.setId(interpreterUserId);
            Staff interpreter = new Staff();
            interpreter.setId(UUID.randomUUID());
            interpreter.setHospital(hospital);
            interpreter.setUser(user);
            report.setInterpretingProvider(interpreter);
        }
        report.setCreatedBy(filedBy);
        return report;
    }

    @Test
    void d930_aColleagueCannotReviseSignOrVoidAnotherCliniciansDraftRead() {
        ImagingReport report = reportReadBy(UUID.randomUUID(), UUID.randomUUID());
        ImagingReportStatusUpdateRequestDTO voidIt = new ImagingReportStatusUpdateRequestDTO();
        voidIt.setStatus(ImagingReportStatus.CANCELLED);
        voidIt.setStatusReason("Wrong patient");

        assertThatThrownBy(() -> imagingReportService.updateReport(reportId, new ImagingReportUpsertRequestDTO()))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> imagingReportService.signReport(reportId))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> imagingReportService.updateReportStatus(reportId, voidIt))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(report.getSignedBy()).isNull();
        assertThat(report.getReportStatus()).isEqualTo(ImagingReportStatus.PRELIMINARY);
        verify(imagingReportRepository, never()).save(any(ImagingReport.class));
    }

    @Test
    void d930_theInterpretingProviderSignsHerRead() {
        ImagingReport report = reportReadBy(callerUserId, UUID.randomUUID());

        imagingReportService.signReport(reportId);

        assertThat(report.getReportStatus()).isEqualTo(ImagingReportStatus.FINAL);
        assertThat(report.getSignedBy()).isSameAs(caller);
    }

    /** Declared supervision path: the clinician who filed the draft may still revise it after naming the reader. */
    @Test
    void d930_theClinicianWhoFiledTheDraftRevisesIt() {
        ImagingReport report = reportReadBy(UUID.randomUUID(), callerUserId);
        ImagingReportUpsertRequestDTO edit = new ImagingReportUpsertRequestDTO();
        edit.setImpression("Small effusion");

        imagingReportService.updateReport(reportId, edit);

        verify(imagingReportRepository).save(report);
    }

    /** QA D1428 class: a voided read stays voided: no revision, and no status moves it back. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ImagingReportStatus.class, names = {"CANCELLED", "ERROR"})
    void qaD1428_aVoidedReportIsNotRevised(ImagingReportStatus voided) {
        ImagingReport report = persistedReport();
        report.setReportStatus(voided);
        ImagingReportUpsertRequestDTO revise = ImagingReportUpsertRequestDTO.builder().findings("revised").build();
        ImagingReportStatusUpdateRequestDTO again = ImagingReportStatusUpdateRequestDTO.builder()
            .status(ImagingReportStatus.ERROR).statusReason("again").build();

        assertThatThrownBy(() -> imagingReportService.updateReport(reportId, revise))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("imaging.report.voided.locked");
        assertThatThrownBy(() -> imagingReportService.updateReportStatus(reportId, again))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("imaging.report.voided.locked");
    }

    // ── User decision 2026-11-14: an administrator reads released reports only ──

    private static void signIn(String... authorities) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("u", "n/a",
                java.util.Arrays.stream(authorities)
                    .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
    }

    private ImagingReport version(int number, ImagingReportStatus status, boolean signed) {
        ImagingReport report = new ImagingReport();
        report.setId(UUID.randomUUID());
        report.setImagingOrder(imagingOrder);
        report.setHospital(hospital);
        report.setReportVersion(number);
        report.setReportStatus(status);
        if (signed) {
            report.setSignedAt(LocalDateTime.now());
        }
        return report;
    }

    /** The super-admin as the token carries them: every inherited role, DOCTOR and NURSE among them. */
    private static final String[] SUPER_ADMIN = {"ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_RECEPTIONIST",
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_LAB_SCIENTIST", "ROLE_STAFF", "ROLE_PATIENT"};

    @Test
    void adminDecision_everyAdminReadOfAnOrdersReportsLeavesOutWorkInProgress() {
        ImagingReport draft = version(4, ImagingReportStatus.DRAFT, false);
        ImagingReport addendum = version(3, ImagingReportStatus.ADDENDUM, true);
        ImagingReport preliminary = version(2, ImagingReportStatus.PRELIMINARY, false);
        ImagingReport finalRead = version(1, ImagingReportStatus.FINAL, true);
        when(imagingReportRepository.findByImagingOrder_IdOrderByReportVersionDesc(orderId))
            .thenReturn(List.of(draft, addendum, preliminary, finalRead));
        when(imagingReportRepository.findFirstByImagingOrder_IdAndLatestVersionIsTrue(orderId))
            .thenReturn(Optional.of(draft));
        when(imagingReportRepository.findByHospital_IdAndReportStatusOrderByPerformedAtDesc(hospitalId,
            ImagingReportStatus.PRELIMINARY)).thenReturn(List.of(preliminary));
        when(imagingReportRepository.findByHospital_IdAndModalityOrderByPerformedAtDesc(hospitalId,
            ImagingModality.CT)).thenReturn(List.of(draft, finalRead));
        when(imagingReportRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        when(imagingReportRepository.findById(finalRead.getId())).thenReturn(Optional.of(finalRead));
        doAnswer(inv -> ImagingReportResponseDTO.builder().id(((ImagingReport) inv.getArgument(0)).getId()).build())
            .when(imagingReportMapper).toResponseDTO(any(ImagingReport.class));

        for (String[] admin : List.of(SUPER_ADMIN, new String[] {"ROLE_HOSPITAL_ADMIN"}, new String[] {"ROLE_ADMIN"})) {
            signIn(admin);
            assertThat(imagingReportService.getReportsForOrder(orderId)).extracting(ImagingReportResponseDTO::getId)
                .containsExactly(addendum.getId(), finalRead.getId());
            assertThat(imagingReportService.getLatestReportForOrder(orderId).getId()).isEqualTo(addendum.getId());
            assertThat(imagingReportService.getReportsByHospitalAndStatus(hospitalId, ImagingReportStatus.PRELIMINARY))
                .isEmpty();
            assertThat(imagingReportService.getReportsByHospitalAndModality(hospitalId, ImagingModality.CT))
                .extracting(ImagingReportResponseDTO::getId).containsExactly(finalRead.getId());
            assertThatThrownBy(() -> imagingReportService.getReport(draft.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
            assertThat(imagingReportService.getReport(finalRead.getId()).getId()).isEqualTo(finalRead.getId());
        }

        // The care team reads the work in progress.
        signIn("ROLE_RADIOLOGIST");
        assertThat(imagingReportService.getReportsForOrder(orderId)).hasSize(4);
        assertThat(imagingReportService.getLatestReportForOrder(orderId).getId()).isEqualTo(draft.getId());
        assertThat(imagingReportService.getReport(draft.getId()).getId()).isEqualTo(draft.getId());
    }

    @Test
    void adminDecision_aHospitalAdminOfANeverListsBsReports() {
        UUID hospitalB = UUID.randomUUID();
        signIn("ROLE_HOSPITAL_ADMIN");
        assertThatThrownBy(() -> imagingReportService.getReportsByHospitalAndStatus(hospitalB, ImagingReportStatus.FINAL))
            .isInstanceOf(AccessDeniedException.class);
        verify(imagingReportRepository, never()).findByHospital_IdAndReportStatusOrderByPerformedAtDesc(eq(hospitalB), any());
    }

    // ── QA D2306 (r63): the « À signer » list ──────────────────────────

    @Test
    void toSign_readsTheCallersUnsignedLiveReadsAtTheActiveHospitalOnly() {
        ImagingReport waiting = persistedReport();
        when(imagingReportRepository.findAwaitingSignatureBy(eq(hospitalId), eq(callerUserId), any()))
            .thenReturn(List.of(waiting));

        assertThat(imagingReportService.getReportsAwaitingMySignature()).hasSize(1);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<ImagingReportStatus>> closed =
            org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(imagingReportRepository).findAwaitingSignatureBy(eq(hospitalId), eq(callerUserId), closed.capture());
        assertThat(closed.getValue()).containsExactlyInAnyOrder(ImagingReportStatus.CANCELLED, ImagingReportStatus.ERROR);
    }

    @Test
    void toSign_aGlobalViewWithNoHospitalHasNothingWaiting() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThat(imagingReportService.getReportsAwaitingMySignature()).isEmpty();
        verify(imagingReportRepository, never()).findAwaitingSignatureBy(any(), any(), any());
    }
}
