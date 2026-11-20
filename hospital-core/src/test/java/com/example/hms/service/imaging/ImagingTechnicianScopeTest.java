package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Policy rule 3 (2026-11-20, provisional; review of #1243): the imaging technician role counts
 * at the hospital it is held at, never token-wide.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImagingTechnicianScopeTest {

    private static final String TECHNICIAN = "ROLE_IMAGING_TECHNICIAN";
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    @Mock private RoleValidator roleValidator;

    private ImagingTechnicianScope scope;

    @BeforeEach
    void setUp() {
        scope = new ImagingTechnicianScope(roleValidator);
        when(roleValidator.getCurrentUserId()).thenReturn(CALLER);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void token(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", "n/a",
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private void technicianAt(UUID hospitalId) {
        when(roleValidator.hasRole(CALLER, hospitalId, "IMAGING_TECHNICIAN")).thenReturn(true);
    }

    @Test
    void aTokenWithNoTechnicianRoleIsNotThisRulesConcern() {
        token("ROLE_RADIOLOGIST");
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isFalse();
        SecurityContextHolder.clearContext();
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).isFalse();
        // Nothing is looked up for somebody who is not a technician anywhere.
        verifyNoInteractions(roleValidator);
    }

    @Test
    void aTechnicianOfTheHospitalIsNarrowedThere() {
        token(TECHNICIAN);
        technicianAt(HOSPITAL_A);
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isTrue();
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).isTrue();
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isTrue();
    }

    @Test
    void aTechnicianAtAWhoIsARadiologistOrADoctorAtBIsStillATechnicianAtA() {
        // The token carries both roles; each counts where it is held.
        technicianAt(HOSPITAL_A);
        for (String wider : new String[] {"ROLE_RADIOLOGIST", "ROLE_DOCTOR"}) {
            token(TECHNICIAN, wider);
            when(roleValidator.hasRole(CALLER, HOSPITAL_B, "RADIOLOGIST")).thenReturn("ROLE_RADIOLOGIST".equals(wider));
            when(roleValidator.isDoctor(CALLER, HOSPITAL_B)).thenReturn("ROLE_DOCTOR".equals(wider));

            assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).as(wider + " elsewhere, exam at A").isTrue();
            assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).as(wider + " elsewhere, read at A").isTrue();
            assertThat(scope.technicianWriterAt(HOSPITAL_A)).isTrue();

            assertThat(scope.actsAsTechnicianAt(HOSPITAL_B)).as(wider + " at B").isFalse();
            assertThat(scope.readsAsTechnicianAt(HOSPITAL_B)).as(wider + " at B").isFalse();
            assertThat(scope.technicianWriterAt(HOSPITAL_B)).isFalse();
        }
    }

    @Test
    void aTechnicianRoleHeldAtAnotherHospitalAdmitsNobodyHere() {
        // A technician at A, a receptionist at B, acting at B: the only role the endpoints took is not theirs here.
        token(TECHNICIAN, "ROLE_RECEPTIONIST");
        technicianAt(HOSPITAL_A);
        assertThatThrownBy(() -> scope.actsAsTechnicianAt(HOSPITAL_B)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> scope.readsAsTechnicianAt(HOSPITAL_B)).isInstanceOf(ClientSafeAccessDeniedException.class);
        // The screen's flag never refuses: it only says no.
        assertThat(scope.technicianWriterAt(HOSPITAL_B)).isFalse();
        // No session user at all is nobody here either.
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        assertThatThrownBy(() -> scope.actsAsTechnicianAt(HOSPITAL_A)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> scope.readsAsTechnicianAt(HOSPITAL_A)).isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    void aCallerAdmittedOnAnotherRoleOfTheirTokenIsLeftAsBefore() {
        // Not a technician here; the token's nurse role reads imaging as it always did (standing
        // debt: that role is token-wide), but never passes an exam write, which no nurse does.
        token(TECHNICIAN, "ROLE_NURSE");
        technicianAt(HOSPITAL_A);
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_B)).isFalse();
        assertThatThrownBy(() -> scope.actsAsTechnicianAt(HOSPITAL_B)).isInstanceOf(ClientSafeAccessDeniedException.class);

        token(TECHNICIAN, "ROLE_HOSPITAL_ADMIN");
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_B)).isFalse();
    }

    @Test
    void aNurseOrMidwifeWhoIsAlsoATechnicianHereReadsAsTheCareTeamAndActsAsTheTechnician() {
        token(TECHNICIAN, "ROLE_NURSE");
        technicianAt(HOSPITAL_A);
        when(roleValidator.isNurse(CALLER, HOSPITAL_A)).thenReturn(true);
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isTrue();

        when(roleValidator.isNurse(CALLER, HOSPITAL_A)).thenReturn(false);
        when(roleValidator.isMidwife(CALLER, HOSPITAL_A)).thenReturn(true);
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).isFalse();
    }

    @Test
    void aDoctorOrRadiologistHereKeepsTheWiderReachEvenAsATechnicianHere() {
        token(TECHNICIAN, "ROLE_RADIOLOGIST");
        technicianAt(HOSPITAL_A);
        when(roleValidator.hasRole(CALLER, HOSPITAL_A, "RADIOLOGIST")).thenReturn(true);
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.readsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isFalse();

        when(roleValidator.hasRole(CALLER, HOSPITAL_A, "RADIOLOGIST")).thenReturn(false);
        when(roleValidator.isSurgeon(CALLER, HOSPITAL_A)).thenReturn(true);
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isFalse();
    }

    // ── Ultrasound (owner decision 2026-11-20, provisional) ──

    private static ImagingOrder order(ImagingModality modality, boolean ultrasoundByNonPhysician) {
        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_A);
        hospital.setUltrasoundByNonPhysician(ultrasoundByNonPhysician);
        ImagingOrder order = new ImagingOrder();
        order.setHospital(hospital);
        order.setModality(modality);
        return order;
    }

    @Test
    void anUltrasoundIsATechniciansOnlyWhereTheHospitalSaysSo() {
        // The default keeps ultrasound with physicians.
        assertThat(new Hospital().isUltrasoundByNonPhysician()).isFalse();
        assertThat(ImagingTechnicianScope.mayPerform(order(ImagingModality.ULTRASOUND, false))).isFalse();
        assertThat(ImagingTechnicianScope.mayPerform(order(ImagingModality.ULTRASOUND, true))).isTrue();
        ImagingOrder refused = order(ImagingModality.ULTRASOUND, false);
        assertThatThrownBy(() -> scope.requireMayPerform(refused)).isInstanceOf(ClientSafeAccessDeniedException.class);
        ImagingOrder allowed = order(ImagingModality.ULTRASOUND, true);
        assertThatCode(() -> scope.requireMayPerform(allowed)).doesNotThrowAnyException();
    }

    @Test
    void everyOtherModalityIsATechniciansWhateverTheSetting() {
        for (ImagingModality modality : ImagingModality.values()) {
            if (modality != ImagingModality.ULTRASOUND) {
                assertThat(ImagingTechnicianScope.mayPerform(order(modality, false))).as(modality.name()).isTrue();
            }
        }
        // An ultrasound order with no hospital on it is nobody's to perform as a technician.
        ImagingOrder orphan = new ImagingOrder();
        orphan.setModality(ImagingModality.ULTRASOUND);
        assertThat(ImagingTechnicianScope.mayPerform(orphan)).isFalse();
    }
}
