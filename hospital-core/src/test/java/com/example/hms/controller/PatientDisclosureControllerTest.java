package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.disclosure.DisclosureAccountingService;
import com.example.hms.utility.RoleValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PatientDisclosureController} — the staff-facing side of Tier 2
 * item 39.
 *
 * <p>The role gate is declarative and enforced by Spring; what needs a test
 * is the tenant gate, because it is hand-written and because getting it
 * wrong hands one hospital's admin the complete access history of another
 * hospital's patient — which is itself a privacy breach of exactly the kind
 * this endpoint exists to report on.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PatientDisclosureController")
class PatientDisclosureControllerTest {

    @Mock private DisclosureAccountingService disclosureAccountingService;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private RoleValidator roleValidator;

    @InjectMocks private PatientDisclosureController controller;

    private UUID patientId;
    private UUID hospitalId;
    private Pageable pageable;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        pageable = PageRequest.of(0, 50);
    }

    @Test
    @DisplayName("returns the accounting for a patient registered at the caller's hospital")
    void registeredPatientIsServed() {
        DisclosureAccountingDTO expected = DisclosureAccountingDTO.builder().totalEvents(3).build();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(true);
        when(disclosureAccountingService.getAccounting(eq(patientId), any(), any(), eq(pageable)))
            .thenReturn(expected);

        var response = controller.getDisclosures(patientId, null, null, pageable);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isSameAs(expected);
    }

    @Test
    @DisplayName("a patient from another hospital reads as missing, not forbidden")
    void unregisteredPatientIs404NotForbidden() {
        // 404 rather than 403 on purpose. Distinguishing "exists but you may
        // not see it" from "does not exist" confirms to an unauthorised
        // caller that a given person is a patient somewhere in the system,
        // which is itself disclosure.
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(false);

        assertThatThrownBy(() -> controller.getDisclosures(patientId, null, null, pageable))
            .isInstanceOf(ResourceNotFoundException.class);

        // And nothing was read on the way to refusing.
        verify(disclosureAccountingService, never()).getAccounting(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a real super-admin is unscoped and needs no registration")
    void superAdminIsUnscoped() {
        // requireActiveHospitalId() returns null for a real super-admin in
        // global view. Treating that as "no hospital, therefore refuse"
        // would break cross-tenant support; treating it as "allow" is the
        // documented contract of that method.
        DisclosureAccountingDTO expected = DisclosureAccountingDTO.builder().build();
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(disclosureAccountingService.getAccounting(eq(patientId), any(), any(), eq(pageable)))
            .thenReturn(expected);

        var response = controller.getDisclosures(patientId, null, null, pageable);

        assertThat(response.getBody()).isNotNull();
        verify(registrationRepository, never()).existsByPatientIdAndHospitalId(any(), any());
    }
}
