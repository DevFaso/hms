package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.ProxySensitiveRelease;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.MinorReleaseOverviewDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.ProxySensitiveReleaseRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.support.PatientChartAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy 2026-11-20 (provisional): a clinician releases a minor's sensitive
 * result to a verified parent or guardian (Loi 030-2008, art. 17 and 18).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Policy 2026-11-20: releasing a minor's sensitive result to a parent or guardian")
class MinorResultReleaseServiceImplTest {

    @Mock private PatientChartAccess patientChartAccess;
    @Mock private TreatmentRelationshipResolver treatmentRelationshipResolver;
    @Mock private PatientProxyRepository patientProxyRepository;
    @Mock private ProxySensitiveReleaseRepository releaseRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private ProxyRecordFilter proxyRecordFilter;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks private MinorResultReleaseServiceImpl service;

    private static final String REASON = "Annonce faite aux parents après conseil, à leur demande.";

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID doctorId = UUID.randomUUID();
    private final Hospital hospital = new Hospital();
    private Patient minor;
    private PatientProxy mother;
    private LabOrder hivOrder;

    @BeforeEach
    void setUp() {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).build());
        hospital.setId(hospitalId);
        minor = patient(15);
        mother = grant(minor, ProxyRelationship.PARENT, "Awa", "Sawadogo");
        hivOrder = order(minor, "Sérologie VIH");

        when(patientChartAccess.require(minor.getId(), hospitalId)).thenReturn(minor);
        when(treatmentRelationshipResolver.resolve(minor.getId(), hospitalId, doctorId)).thenReturn(Optional.of(
            new TreatmentRelationship(null, UUID.randomUUID(), hospitalId, minor.getId(), LocalDateTime.now(), null,
                true)));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientProxyRepository.findById(mother.getId())).thenReturn(Optional.of(mother));
        when(patientProxyRepository.findByGrantorPatient_IdAndStatus(minor.getId(), ProxyStatus.ACTIVE))
            .thenReturn(List.of(mother));
        when(labOrderRepository.findById(hivOrder.getId())).thenReturn(Optional.of(hivOrder));
        when(proxyRecordFilter.hidingCategoryOf(hivOrder)).thenReturn(SensitivityCategory.HIV);
        LabResult released = resultOf(hivOrder, true);
        when(labResultRepository.findByLabOrder_Id(hivOrder.getId())).thenReturn(List.of(released));
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(minor.getId(), hospitalId,
            Pageable.unpaged())).thenReturn(List.of(released));
        when(releaseRepository.save(any(ProxySensitiveRelease.class))).thenAnswer(inv -> {
            ProxySensitiveRelease saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    private static Patient patient(int age) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.now().minusYears(age).minusDays(1));
        return patient;
    }

    private static PatientProxy grant(Patient grantor, ProxyRelationship relationship, String first, String last) {
        User holder = new User();
        holder.setId(UUID.randomUUID());
        holder.setFirstName(first);
        holder.setLastName(last);
        PatientProxy grant = PatientProxy.builder()
            .grantorPatient(grantor).proxyUser(holder).relationship(relationship)
            .status(ProxyStatus.ACTIVE).permissions("ALL").build();
        grant.setId(UUID.randomUUID());
        return grant;
    }

    private LabOrder order(Patient patient, String testName) {
        LabTestDefinition test = new LabTestDefinition();
        test.setName(testName);
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setHospital(hospital);
        order.setLabTestDefinition(test);
        return order;
    }

    private static LabResult resultOf(LabOrder order, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setReleased(released);
        result.setReleasedAt(released ? LocalDateTime.now().minusDays(1) : null);
        return result;
    }

    private AuditEventRequestDTO auditOf(AuditEventType type) {
        ArgumentCaptor<AuditEventRequestDTO> events = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, org.mockito.Mockito.atLeastOnce()).logEvent(events.capture());
        return events.getAllValues().stream().filter(e -> e.getEventType() == type).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a doctor with care of the patient releases the HIV result to the verified mother, with a reason, and it is audited")
    void releasesToAVerifiedParent() {
        mother.setRelationshipVerifiedAt(LocalDateTime.now().minusDays(3));

        service.release(minor.getId(), hivOrder.getId(), mother.getId(), "  " + REASON + "  ", false, doctorId);

        ArgumentCaptor<ProxySensitiveRelease> saved = ArgumentCaptor.forClass(ProxySensitiveRelease.class);
        verify(releaseRepository).save(saved.capture());
        ProxySensitiveRelease release = saved.getValue();
        assertThat(release.getPatientId()).isEqualTo(minor.getId());
        assertThat(release.getHospitalId()).isEqualTo(hospitalId);
        assertThat(release.getProxyGrantId()).isEqualTo(mother.getId());
        assertThat(release.getProxyUserId()).isEqualTo(mother.getProxyUser().getId());
        assertThat(release.getLabOrderId()).isEqualTo(hivOrder.getId());
        assertThat(release.getSensitivityCategory()).isEqualTo(SensitivityCategory.HIV);
        assertThat(release.getReason()).isEqualTo(REASON);
        assertThat(release.getReleasedByUserId()).isEqualTo(doctorId);

        AuditEventRequestDTO audit = auditOf(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY);
        assertThat(audit.getPatientId()).isEqualTo(minor.getId());
        assertThat(audit.getUserId()).isEqualTo(doctorId);
        assertThat(audit.getEntityType()).isEqualTo("PROXY_SENSITIVE_RELEASE");
        assertThat(audit.getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("category", "HIV")
            .containsEntry("grantId", mother.getId().toString())
            .containsEntry("labOrderId", hivOrder.getId().toString());
        // Ids and the category only: the reason can say what the result is.
        assertThat(String.valueOf(audit.getDetails())).doesNotContain(REASON);
        assertThat(audit.getEventDescription()).doesNotContain(REASON);
    }

    @Test
    @DisplayName("an unverified parent needs the clinician's attestation; given, the grant is stamped verified and that is audited")
    void anUnverifiedParentNeedsTheAttestation() {
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, false,
            doctorId)).isInstanceOf(BusinessException.class);
        verify(releaseRepository, never()).save(any());

        service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, true, doctorId);

        assertThat(mother.getRelationshipVerifiedAt()).isNotNull();
        assertThat(mother.getRelationshipVerifiedByUserId()).isEqualTo(doctorId);
        verify(releaseRepository).save(any());
        assertThat(auditOf(AuditEventType.PROXY_RELATIONSHIP_VERIFIED).getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("grantId", mother.getId().toString());
    }

    @Test
    @DisplayName("an adult's result is never released to a relative")
    void notForAnAdult() {
        Patient adult = patient(18);
        PatientProxy parent = grant(adult, ProxyRelationship.PARENT, "Awa", "Sawadogo");
        parent.setRelationshipVerifiedAt(LocalDateTime.now());
        LabOrder order = order(adult, "Sérologie VIH");
        when(patientChartAccess.require(adult.getId(), hospitalId)).thenReturn(adult);
        when(treatmentRelationshipResolver.resolve(adult.getId(), hospitalId, doctorId)).thenReturn(Optional.of(
            new TreatmentRelationship(null, UUID.randomUUID(), hospitalId, adult.getId(), LocalDateTime.now(), null,
                true)));

        assertThatThrownBy(() -> service.release(adult.getId(), order.getId(), parent.getId(), REASON, true, doctorId))
            .isInstanceOf(BusinessException.class);
        verify(releaseRepository, never()).save(any());
        assertThat(service.overview(adult.getId(), doctorId).minor()).isFalse();
    }

    @Test
    @DisplayName("only a parent's or a legal guardian's grant can receive a release")
    void onlyAParentOrGuardian() {
        PatientProxy aunt = grant(minor, ProxyRelationship.CAREGIVER, "Fati", "Ouédraogo");
        aunt.setRelationshipVerifiedAt(LocalDateTime.now());
        when(patientProxyRepository.findById(aunt.getId())).thenReturn(Optional.of(aunt));

        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), aunt.getId(), REASON, true, doctorId))
            .isInstanceOf(BusinessException.class);
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("another patient's grant, a revoked grant and an expired grant answer as unknown")
    void theGrantMustBeThisPatientsAndLive() {
        PatientProxy others = grant(patient(10), ProxyRelationship.PARENT, "X", "Y");
        when(patientProxyRepository.findById(others.getId())).thenReturn(Optional.of(others));
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), others.getId(), REASON, true, doctorId))
            .isInstanceOf(ResourceNotFoundException.class);

        mother.setStatus(ProxyStatus.REVOKED);
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, true, doctorId))
            .isInstanceOf(ResourceNotFoundException.class);

        mother.setStatus(ProxyStatus.ACTIVE);
        mother.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, true, doctorId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("a clinician with no care relationship at the acting hospital is refused, and reads no overview")
    void hasCareOfThePatient() {
        when(treatmentRelationshipResolver.resolve(minor.getId(), hospitalId, doctorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, true, doctorId))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.overview(minor.getId(), doctorId)).isInstanceOf(AccessDeniedException.class);
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("a caller who cannot be named releases nothing")
    void needsANamedClinician() {
        mother.setRelationshipVerifiedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, false, null))
            .isInstanceOf(AccessDeniedException.class);
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("a global view has no hospital to act at: refused")
    void needsAnActingHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());

        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, true, doctorId))
            .isInstanceOf(RuntimeException.class);
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("another hospital's order, an ordinary result and a result the lab has not released are refused")
    void whatCanBeReleased() {
        mother.setRelationshipVerifiedAt(LocalDateTime.now());

        Hospital elsewhere = new Hospital();
        elsewhere.setId(UUID.randomUUID());
        hivOrder.setHospital(elsewhere);
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, false, doctorId))
            .isInstanceOf(ResourceNotFoundException.class);
        hivOrder.setHospital(hospital);

        when(proxyRecordFilter.hidingCategoryOf(hivOrder)).thenReturn(null);
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, false, doctorId))
            .isInstanceOf(BusinessException.class);
        when(proxyRecordFilter.hidingCategoryOf(hivOrder)).thenReturn(SensitivityCategory.HIV);

        when(labResultRepository.findByLabOrder_Id(hivOrder.getId())).thenReturn(List.of(resultOf(hivOrder, false)));
        assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, false, doctorId))
            .isInstanceOf(BusinessException.class);
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("a reason of spaces, or shorter than ten characters once trimmed, is refused on its field")
    void aRealReason() {
        mother.setRelationshipVerifiedAt(LocalDateTime.now());

        for (String reason : new String[] {null, "             ", "  court   ", "a         "}) {
            assertThatThrownBy(() -> service.release(minor.getId(), hivOrder.getId(), mother.getId(), reason, false,
                doctorId)).isInstanceOf(com.example.hms.exception.FieldBusinessException.class);
        }
        verify(releaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("releasing the same result to the same parent again changes nothing and audits nothing")
    void releasedOnce() {
        mother.setRelationshipVerifiedAt(LocalDateTime.now());
        when(releaseRepository.existsByProxyGrantIdAndLabOrderId(mother.getId(), hivOrder.getId())).thenReturn(true);

        service.release(minor.getId(), hivOrder.getId(), mother.getId(), REASON, false, doctorId);

        verify(releaseRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("the overview lists the parents' grants, the hidden results and their releases with the reason")
    void theOverview() {
        mother.setRelationshipVerifiedAt(LocalDateTime.now().minusDays(2));
        PatientProxy aunt = grant(minor, ProxyRelationship.CAREGIVER, "Fati", "Ouédraogo");
        when(patientProxyRepository.findByGrantorPatient_IdAndStatus(minor.getId(), ProxyStatus.ACTIVE))
            .thenReturn(List.of(mother, aunt));
        LabOrder count = order(minor, "NFS");
        when(proxyRecordFilter.hidingCategoryOf(count)).thenReturn(null);
        when(labResultRepository.findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(minor.getId(), hospitalId,
            Pageable.unpaged())).thenReturn(List.of(resultOf(hivOrder, true), resultOf(count, true)));
        when(releaseRepository.findByPatientIdAndHospitalIdOrderByReleasedAtDesc(minor.getId(), hospitalId))
            .thenReturn(List.of(ProxySensitiveRelease.builder().labOrderId(hivOrder.getId())
                .proxyGrantId(mother.getId()).reason(REASON).releasedAt(LocalDateTime.now()).build()));
        hospital.setAdolescentConfidentialityAge(14);

        MinorReleaseOverviewDTO overview = service.overview(minor.getId(), doctorId);

        assertThat(overview.minor()).isTrue();
        assertThat(overview.adolescentAge()).isEqualTo(14);
        assertThat(overview.adolescentAgeIsDefault()).isFalse();
        assertThat(overview.guardians()).singleElement().satisfies(guardian -> {
            assertThat(guardian.grantId()).isEqualTo(mother.getId());
            assertThat(guardian.holderName()).isEqualTo("Awa Sawadogo");
            assertThat(guardian.verified()).isTrue();
        });
        assertThat(overview.results()).singleElement().satisfies(result -> {
            assertThat(result.labOrderId()).isEqualTo(hivOrder.getId());
            assertThat(result.testName()).isEqualTo("Sérologie VIH");
            assertThat(result.category()).isEqualTo(SensitivityCategory.HIV);
            assertThat(result.releases()).singleElement().satisfies(release -> {
                assertThat(release.getHolderName()).isEqualTo("Awa Sawadogo");
                assertThat(release.getReason()).isEqualTo(REASON);
            });
        });
    }

    @Test
    @DisplayName("staff verify a parent's relationship on its own, once, audited")
    void verifyGuardian() {
        service.verifyGuardian(minor.getId(), mother.getId(), doctorId);
        service.verifyGuardian(minor.getId(), mother.getId(), doctorId);

        assertThat(mother.getRelationshipVerifiedByUserId()).isEqualTo(doctorId);
        verify(patientProxyRepository).save(mother);
        verify(auditEventLogService).logEvent(any());
    }
}
