package com.example.hms.security.tenant;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA r5 (D262 class): the write-side tenant check, case by case. */
class TenantOwnershipTest {

    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private static Hospital hospital(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private static void pinTo(UUID hospitalId) {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).permittedHospitalIds(Set.of(hospitalId)).build());
    }

    @Test
    void aCallerPinnedAtBCannotWriteHospitalAsRow() {
        pinTo(HOSPITAL_B);
        Hospital owner = hospital(HOSPITAL_A);
        assertThatThrownBy(() -> TenantOwnership.requireOwnedByActingHospital(owner, "admission.notFound", "x"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aCallerPinnedAtAWritesHospitalAsRow() {
        pinTo(HOSPITAL_A);
        assertThatCode(() -> TenantOwnership.requireOwnedByActingHospital(hospital(HOSPITAL_A), "admission.notFound", "x"))
            .doesNotThrowAnyException();
    }

    @Test
    void aRowWithoutAHospitalIsRefusedOutsideGlobalView() {
        pinTo(HOSPITAL_A);
        assertThatThrownBy(() -> TenantOwnership.requireOwnedByActingHospital((Hospital) null, "k", "x"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuperAdminInGlobalViewReachesAnyHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        assertThatCode(() -> TenantOwnership.requireOwnedByActingHospital(hospital(HOSPITAL_A), "k", "x"))
            .doesNotThrowAnyException();
    }

    @Test
    void aPatientOnlyCallerIsRefused() {
        HospitalContextHolder.setContext(HospitalContext.builder().patientOwned(true).build());
        Hospital owner = hospital(HOSPITAL_A);
        assertThatThrownBy(() -> TenantOwnership.requireOwnedByActingHospital(owner, "k", "x"))
            .isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    void aRequestWithNoResolvableHospitalIsRefused() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        Hospital owner = hospital(HOSPITAL_A);
        assertThatThrownBy(() -> TenantOwnership.requireOwnedByActingHospital(owner, "k", "x"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void aJobWithNoRequestAndNoContextIsNotConfined() {
        assertThatCode(() -> TenantOwnership.requireOwnedByActingHospital(hospital(HOSPITAL_A), "k", "x"))
            .doesNotThrowAnyException();
    }
}
