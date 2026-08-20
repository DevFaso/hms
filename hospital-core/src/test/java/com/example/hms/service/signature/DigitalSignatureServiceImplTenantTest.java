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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
