package com.example.hms.service.signature;

import com.example.hms.mapper.DigitalSignatureMapper;
import com.example.hms.model.signature.DigitalSignature;
import com.example.hms.payload.dto.signature.SignatureResponseDTO;
import com.example.hms.repository.DigitalSignatureRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.AuthService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.Spy;
import org.springframework.context.MessageSource;
import com.example.hms.i18n.TestMessageSources;

/** Tenant isolation for the admin signature listing (GET /signatures/all). */
@ExtendWith(MockitoExtension.class)
@DisplayName("DigitalSignatureService — tenant isolation")
class DigitalSignatureServiceImplTenantTest {

    @Mock private DigitalSignatureRepository signatureRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DigitalSignatureMapper signatureMapper;
    @Mock private AuthService authService;
    @Mock private RoleValidator roleValidator;
    @Mock private SignableReportLocator reportLocator;
    @Mock private com.example.hms.repository.UserRepository userRepository;

    @Spy private MessageSource messageSource = TestMessageSources.bundles();

    @InjectMocks
    private DigitalSignatureServiceImpl service;

    @Test
    @DisplayName("hospital admin — listing scoped to own hospital, never findAll")
    void hospitalAdminListingIsScopedToOwnHospital() {
        UUID ownHospital = UUID.randomUUID();
        DigitalSignature signature = new DigitalSignature();
        when(roleValidator.requireActiveHospitalId()).thenReturn(ownHospital);
        when(signatureRepository.findByHospital_IdOrderBySignatureDateTimeDesc(ownHospital))
                .thenReturn(List.of(signature));
        when(signatureMapper.toResponseDTO(signature)).thenReturn(new SignatureResponseDTO());

        List<SignatureResponseDTO> result = service.getAllSignatures();

        assertThat(result).hasSize(1);
        verify(signatureRepository).findByHospital_IdOrderBySignatureDateTimeDesc(ownHospital);
        verify(signatureRepository, never()).findAll();
    }

    @Test
    @DisplayName("super-admin (null hospitalId) — listing is unscoped")
    void superAdminListingIsUnscoped() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(signatureRepository.findAll()).thenReturn(List.of());

        assertThat(service.getAllSignatures()).isEmpty();
        verify(signatureRepository).findAll();
        verify(signatureRepository, never()).findByHospital_IdOrderBySignatureDateTimeDesc(null);
    }

    // ── QA D71: signer, hospital and report are claims ──

    private com.example.hms.payload.dto.signature.SignatureRequestDTO request(UUID staffId, UUID hospitalId, UUID reportId) {
        return com.example.hms.payload.dto.signature.SignatureRequestDTO.builder()
            .reportType(com.example.hms.enums.SignatureType.DISCHARGE_SUMMARY)
            .reportId(reportId).signedByStaffId(staffId).hospitalId(hospitalId)
            .signatureValue("Ada Lovelace").build();
    }

    private com.example.hms.model.Staff me(UUID id) {
        com.example.hms.model.Staff staff = mock(com.example.hms.model.Staff.class);
        org.mockito.Mockito.lenient().when(staff.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(staff.getFullName()).thenReturn("Ada Lovelace");
        return staff;
    }

    @Test
    void signingInAnotherClinicianNameIsRefused() {
        UUID hospital = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        com.example.hms.model.Staff mine = me(UUID.randomUUID());
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(authService.getCurrentUserId()).thenReturn(user);
        when(staffRepository.findByUserIdAndHospitalId(user, hospital)).thenReturn(java.util.Optional.of(mine));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.signReport(request(UUID.randomUUID(), hospital, UUID.randomUUID())))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(signatureRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void signingAtAnotherHospitalIsRefused() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.signReport(request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(signatureRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void signingANonExistentReportIsRefused() {
        UUID hospital = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        com.example.hms.model.Staff mine = me(staffId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(authService.getCurrentUserId()).thenReturn(user);
        when(staffRepository.findByUserIdAndHospitalId(user, hospital)).thenReturn(java.util.Optional.of(mine));
        when(reportLocator.owningHospitalId(com.example.hms.enums.SignatureType.DISCHARGE_SUMMARY, reportId))
            .thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.signReport(request(staffId, hospital, reportId)))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        verify(signatureRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void signatureValueMustBeTheSignerName() {
        UUID hospital = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        com.example.hms.model.Staff mine = me(staffId);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospital);
        when(authService.getCurrentUserId()).thenReturn(user);
        when(staffRepository.findByUserIdAndHospitalId(user, hospital)).thenReturn(java.util.Optional.of(mine));
        when(reportLocator.owningHospitalId(com.example.hms.enums.SignatureType.DISCHARGE_SUMMARY, reportId))
            .thenReturn(java.util.Optional.of(hospital));
        var req = request(staffId, hospital, reportId);
        req.setSignatureValue("Someone Else");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.signReport(req))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);
        verify(signatureRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // ── QA D502: only the signer, or an admin, revokes; the revoker is named ──

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        com.example.hms.security.context.HospitalContextHolder.clear();
    }

    private static void pinTo(UUID hospitalId) {
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .activeHospitalId(hospitalId).permittedHospitalIds(java.util.Set.of(hospitalId)).build());
    }

    private DigitalSignature signedBy(UUID signerUserId, UUID hospitalId) {
        com.example.hms.model.User user = new com.example.hms.model.User();
        user.setId(signerUserId);
        com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
        staff.setUser(user);
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(hospitalId);
        DigitalSignature signature = new DigitalSignature();
        signature.setId(UUID.randomUUID());
        signature.setSignedBy(staff);
        signature.setHospital(hospital);
        signature.setStatus(com.example.hms.enums.SignatureStatus.SIGNED);
        return signature;
    }

    private static com.example.hms.payload.dto.signature.SignatureRevocationRequestDTO reason() {
        return com.example.hms.payload.dto.signature.SignatureRevocationRequestDTO.builder()
            .revocationReason("Signed on the wrong report").build();
    }

    @Test
    void anotherDoctorCannotRevokeTheSignature() {
        UUID hospital = UUID.randomUUID();
        pinTo(hospital);
        DigitalSignature signature = signedBy(UUID.randomUUID(), hospital);
        when(signatureRepository.findById(signature.getId())).thenReturn(java.util.Optional.of(signature));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.revokeSignature(signature.getId(), reason()))
            .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(signature.getStatus()).isEqualTo(com.example.hms.enums.SignatureStatus.SIGNED);
        verify(signatureRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theSignerRevokesAndIsNamedInTheAudit() {
        UUID hospital = UUID.randomUUID();
        UUID signerId = UUID.randomUUID();
        pinTo(hospital);
        DigitalSignature signature = signedBy(signerId, hospital);
        com.example.hms.model.User me = new com.example.hms.model.User();
        me.setId(signerId);
        me.setFirstName("Ada");
        me.setLastName("Lovelace");
        when(signatureRepository.findById(signature.getId())).thenReturn(java.util.Optional.of(signature));
        when(authService.getCurrentUserId()).thenReturn(signerId);
        when(userRepository.findById(signerId)).thenReturn(java.util.Optional.of(me));
        when(signatureRepository.save(signature)).thenReturn(signature);

        service.revokeSignature(signature.getId(), reason());

        assertThat(signature.getStatus()).isEqualTo(com.example.hms.enums.SignatureStatus.REVOKED);
        assertThat(signature.getRevokedByUserId()).isEqualTo(signerId);
        assertThat(signature.getRevokedByDisplay()).isEqualTo("Ada Lovelace");
    }

    @Test
    void aHospitalAdminRevokesThroughTheAuditedPath() {
        UUID hospital = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        pinTo(hospital);
        DigitalSignature signature = signedBy(UUID.randomUUID(), hospital);
        when(signatureRepository.findById(signature.getId())).thenReturn(java.util.Optional.of(signature));
        when(authService.getCurrentUserId()).thenReturn(adminId);
        when(roleValidator.isHospitalAdminFromAuthGlobalOnly()).thenReturn(true);
        when(signatureRepository.save(signature)).thenReturn(signature);

        service.revokeSignature(signature.getId(), reason());

        assertThat(signature.getStatus()).isEqualTo(com.example.hms.enums.SignatureStatus.REVOKED);
        assertThat(signature.getRevocationReason()).isEqualTo("Signed on the wrong report");
    }

    // ── QA D507: hospital B does not read hospital A's signatures ──

    @Test
    void hospitalBReadsNoneOfHospitalAsSignatures() {
        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        DigitalSignature atA = signedBy(UUID.randomUUID(), hospitalA);
        UUID providerId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        pinTo(hospitalB);
        when(signatureRepository.findById(atA.getId())).thenReturn(java.util.Optional.of(atA));
        when(signatureRepository.findBySignedBy_IdOrderBySignatureDateTimeDesc(providerId)).thenReturn(List.of(atA));
        when(signatureRepository.findByReportIdAndReportTypeOrderBySignatureDateTimeDesc(
                reportId, com.example.hms.enums.SignatureType.LAB_RESULT)).thenReturn(List.of(atA));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getSignatureById(atA.getId()))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getSignatureAuditTrail(atA.getId()))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(service.getSignaturesByProvider(providerId)).isEmpty();
        assertThat(service.getSignaturesByReportId(com.example.hms.enums.SignatureType.LAB_RESULT, reportId)).isEmpty();
        assertThat(service.isReportSigned(com.example.hms.enums.SignatureType.LAB_RESULT, reportId)).isFalse();
    }

    @Test
    void hospitalAStillReadsItsOwnSignatures() {
        UUID hospitalA = UUID.randomUUID();
        DigitalSignature atA = signedBy(UUID.randomUUID(), hospitalA);
        UUID providerId = UUID.randomUUID();
        pinTo(hospitalA);
        when(signatureRepository.findById(atA.getId())).thenReturn(java.util.Optional.of(atA));
        when(signatureRepository.findBySignedBy_IdOrderBySignatureDateTimeDesc(providerId)).thenReturn(List.of(atA));
        when(signatureMapper.toResponseDTO(atA)).thenReturn(new SignatureResponseDTO());

        assertThat(service.getSignatureById(atA.getId())).isNotNull();
        assertThat(service.getSignaturesByProvider(providerId)).hasSize(1);
    }
}
