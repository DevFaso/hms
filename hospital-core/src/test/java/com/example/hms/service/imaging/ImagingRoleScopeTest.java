package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.service.imaging.ImagingRoleScope.Reader;
import com.example.hms.utility.RoleValidator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import static org.mockito.Mockito.when;

/**
 * QA D2915 (r66): an imaging role counts at the hospital it is held at, whatever role the token
 * names. Policy rule 3 (2026-11-20, provisional) for the technician's own reach.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImagingRoleScopeTest {

    private static final String TECHNICIAN = "ROLE_IMAGING_TECHNICIAN";
    private static final String RADIOLOGIST = "ROLE_RADIOLOGIST";
    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();

    @Mock private RoleValidator roleValidator;

    private ImagingRoleScope scope;

    @BeforeEach
    void setUp() {
        scope = new ImagingRoleScope(roleValidator);
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

    /** A radiologist there: interprets, reads as the care team, decides for the department. */
    private void radiologistAt(UUID hospitalId) {
        when(roleValidator.interpretsImaging(CALLER, hospitalId)).thenReturn(true);
        when(roleValidator.readsImagingClinically(CALLER, hospitalId)).thenReturn(true);
        when(roleValidator.hasRole(CALLER, hospitalId, "RADIOLOGIST")).thenReturn(true);
    }

    private static ImagingOrder orderAt(UUID hospitalId) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        ImagingOrder order = new ImagingOrder();
        order.setHospital(hospital);
        return order;
    }

    private static void refused(ThrowingCallable call, String what) {
        assertThatThrownBy(call).as(what).isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    // ── D2915: the technician at A who is a radiologist at B ──

    @Test
    void aTechnicianAtAWhoIsARadiologistAtB_actingAtAUnderTheRadiologistRole_isATechnicianAtA() {
        // The token names the radiologist role only: it was picked at login (the D2915 steps).
        token(RADIOLOGIST);
        technicianAt(HOSPITAL_A);
        radiologistAt(HOSPITAL_B);

        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).as("exam steps at A: the technician's").isTrue();
        assertThat(scope.actsAsTechnician(orderAt(HOSPITAL_A))).isTrue();
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isTrue();
        assertThat(scope.readerAt(HOSPITAL_A)).as("reads at A").isEqualTo(Reader.TECHNICIAN);
        refused(() -> scope.requireInterpreterAt(HOSPITAL_A), "schedule / pre-authorisation / report at A");
        refused(() -> scope.requireInterpreter(orderAt(HOSPITAL_A)), "the same, from the order");
        assertThat(scope.interpretsAt(HOSPITAL_A)).isFalse();
        refused(() -> scope.requireClinicalReaderAt(HOSPITAL_A), "pixels / reading context / chart list at A");
        refused(() -> scope.requireOrdererAt(HOSPITAL_A), "ordering at A");
        refused(() -> scope.requireOrderer(orderAt(HOSPITAL_A)), "editing an order at A");
        refused(() -> scope.requireSigningPhysician(orderAt(HOSPITAL_A)), "signing an order at A");
        assertThat(scope.radiologistAt(orderAt(HOSPITAL_A))).as("not the department's radiologist at A").isFalse();
    }

    @Test
    void theSameCaller_actingAtB_isARadiologistAtB_whicheverRoleTheTokenNames() {
        for (String selected : new String[] {RADIOLOGIST, TECHNICIAN}) {
            token(selected);
            technicianAt(HOSPITAL_A);
            radiologistAt(HOSPITAL_B);

            assertThat(scope.actsAsTechnicianAt(HOSPITAL_B)).as(selected).isFalse();
            assertThat(scope.technicianWriterAt(HOSPITAL_B)).isFalse();
            assertThat(scope.readerAt(HOSPITAL_B)).isEqualTo(Reader.CLINICAL);
            assertThat(scope.reader(orderAt(HOSPITAL_B))).isEqualTo(Reader.CLINICAL);
            assertThat(scope.interpretsAt(HOSPITAL_B)).isTrue();
            assertThatCode(() -> scope.requireInterpreter(orderAt(HOSPITAL_B))).doesNotThrowAnyException();
            assertThatCode(() -> scope.requireClinicalReaderAt(HOSPITAL_B)).doesNotThrowAnyException();
            assertThat(scope.radiologistAt(orderAt(HOSPITAL_B))).isTrue();
        }
    }

    @Test
    void aClinicalRoleHeldOnlyAtAnotherHospitalAdmitsNobodyHere() {
        // A doctor, radiologist, nurse or technician at B who is a receptionist at A, acting at A.
        for (String selected : new String[] {"ROLE_DOCTOR", RADIOLOGIST, "ROLE_NURSE", TECHNICIAN}) {
            token(selected);
            radiologistAt(HOSPITAL_B);
            when(roleValidator.ordersImaging(CALLER, HOSPITAL_B)).thenReturn(true);
            when(roleValidator.isSigningPhysician(CALLER, HOSPITAL_B)).thenReturn(true);
            technicianAt(HOSPITAL_B);

            refused(() -> scope.actsAsTechnicianAt(HOSPITAL_A), selected + ": exam step");
            refused(() -> scope.readerAt(HOSPITAL_A), selected + ": read");
            refused(() -> scope.requireInterpreterAt(HOSPITAL_A), selected + ": report");
            refused(() -> scope.requireOrdererAt(HOSPITAL_A), selected + ": order");
            refused(() -> scope.requireSigningPhysician(orderAt(HOSPITAL_A)), selected + ": order signature");
            refused(() -> scope.requireClinicalReaderAt(HOSPITAL_A), selected + ": pixels");
            // The screen's flag never refuses: it only says no.
            assertThat(scope.technicianWriterAt(HOSPITAL_A)).isFalse();
            // ... and at B, where the roles are held, each act passes.
            assertThatCode(() -> scope.requireOrdererAt(HOSPITAL_B)).doesNotThrowAnyException();
            assertThatCode(() -> scope.requireSigningPhysician(orderAt(HOSPITAL_B))).doesNotThrowAnyException();
        }
    }

    @Test
    void nobodyIsAnythingWithoutASessionUserOrAHospital() {
        token(RADIOLOGIST);
        radiologistAt(HOSPITAL_A);
        refused(() -> scope.readerAt(null), "no hospital");
        refused(() -> scope.actsAsTechnicianAt(null), "no hospital");
        refused(() -> scope.requireInterpreterAt(null), "no hospital");
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        refused(() -> scope.readerAt(HOSPITAL_A), "no session user");
        refused(() -> scope.actsAsTechnicianAt(HOSPITAL_A), "no session user");
        refused(() -> scope.requireInterpreterAt(HOSPITAL_A), "no session user");
        refused(() -> scope.requireOrdererAt(HOSPITAL_A), "no session user");
        refused(() -> scope.requireClinicalReaderAt(HOSPITAL_A), "no session user");
        assertThat(scope.radiologistAt(orderAt(HOSPITAL_A))).isFalse();
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isFalse();
    }

    @Test
    void anOrderWithNoHospitalIsJudgedWhereTheRequestActs() {
        token(RADIOLOGIST);
        radiologistAt(HOSPITAL_B);
        when(roleValidator.getCurrentHospitalId()).thenReturn(HOSPITAL_B);
        assertThat(scope.reader(new ImagingOrder())).isEqualTo(Reader.CLINICAL);
        when(roleValidator.getCurrentHospitalId()).thenReturn(HOSPITAL_A);
        refused(() -> scope.reader(new ImagingOrder()), "acting at A, where they hold nothing");
    }

    // ── Policy rule 3: the technician ──

    @Test
    void aTechnicianOfTheHospitalIsNarrowedThere() {
        token(TECHNICIAN);
        technicianAt(HOSPITAL_A);
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isTrue();
        assertThat(scope.readerAt(HOSPITAL_A)).isEqualTo(Reader.TECHNICIAN);
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isTrue();
        assertThat(Reader.TECHNICIAN.technician()).isTrue();
        assertThat(Reader.TECHNICIAN.administrator()).isFalse();
        assertThat(Reader.CLINICAL.technician()).isFalse();
        assertThat(Reader.CLINICAL.administrator()).isFalse();
    }

    @Test
    void aNurseOrMidwifeWhoIsAlsoATechnicianHereReadsAsTheCareTeamAndActsAsTheTechnician() {
        token(TECHNICIAN, "ROLE_NURSE");
        technicianAt(HOSPITAL_A);
        when(roleValidator.readsImagingClinically(CALLER, HOSPITAL_A)).thenReturn(true);
        assertThat(scope.readerAt(HOSPITAL_A)).isEqualTo(Reader.CLINICAL);
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isTrue();
    }

    @Test
    void whoInterpretsHereKeepsTheWiderReachEvenAsATechnicianHere() {
        token(TECHNICIAN);
        technicianAt(HOSPITAL_A);
        radiologistAt(HOSPITAL_A);
        assertThat(scope.actsAsTechnicianAt(HOSPITAL_A)).isFalse();
        assertThat(scope.readerAt(HOSPITAL_A)).isEqualTo(Reader.CLINICAL);
        assertThat(scope.technicianWriterAt(HOSPITAL_A)).isFalse();
    }

    // ── The administrators (user decision 2026-11-14) ──

    /** A hospital admin (or an admin) of that hospital, by their assignment there. */
    private void administratorAt(UUID hospitalId) {
        when(roleValidator.administersImagingReads(CALLER, hospitalId)).thenReturn(true);
    }

    @Test
    void anAdministratorOfTheHospitalWithNoImagingRoleThereReadsAsOne_andNeverActs() {
        for (String admin : new String[] {"ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN"}) {
            token(admin);
            administratorAt(HOSPITAL_A);
            assertThat(scope.readerAt(HOSPITAL_A)).as(admin).isEqualTo(Reader.ADMINISTRATOR);
            refused(() -> scope.requireInterpreterAt(HOSPITAL_A), admin + ": report");
            refused(() -> scope.actsAsTechnicianAt(HOSPITAL_A), admin + ": exam step");
            refused(() -> scope.requireClinicalReaderAt(HOSPITAL_A), admin + ": pixels");
            // Only the verified super-admin has a global view.
            refused(() -> scope.readerAt(null), admin + ": no hospital");
        }
        assertThat(Reader.ADMINISTRATOR.administrator()).isTrue();
        assertThat(Reader.ADMINISTRATOR.technician()).isFalse();
    }

    @Test
    void review1251_aHospitalAdminOfBWhoIsAReceptionistAtA_actingAtAUnderTheAdminRole_readsNothingAtA() {
        // The token names the admin role (picked at login); it is held at B.
        for (String admin : new String[] {"ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN"}) {
            token(admin);
            administratorAt(HOSPITAL_B);

            refused(() -> scope.readerAt(HOSPITAL_A), admin + ": worklist, orders, released reports, images at A");
            refused(() -> scope.reader(orderAt(HOSPITAL_A)), admin + ": the same, from the order");
            // ... and at B, where the role is held, they read as the administrator they are.
            assertThat(scope.readerAt(HOSPITAL_B)).as(admin + " at B").isEqualTo(Reader.ADMINISTRATOR);
            assertThat(scope.reader(orderAt(HOSPITAL_B))).isEqualTo(Reader.ADMINISTRATOR);
        }
    }

    @Test
    void review1251_theVerifiedSuperAdminReadsEveryHospitalAndTheGlobalView_theTokensClaimAloneReadsNone() {
        // The token carries SUPER_ADMIN and its inherited DOCTOR and NURSE: none is held here.
        token("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR", "ROLE_NURSE");

        // A token still naming SUPER_ADMIN with no live assignment behind it (demoted, a stale token).
        refused(() -> scope.readerAt(HOSPITAL_A), "the claim alone");
        refused(() -> scope.readerAt(null), "the claim alone, global view");

        // The verified super-admin (a live SUPER_ADMIN assignment): any hospital they name, and the global view.
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        assertThat(scope.readerAt(HOSPITAL_A)).isEqualTo(Reader.ADMINISTRATOR);
        assertThat(scope.readerAt(HOSPITAL_B)).isEqualTo(Reader.ADMINISTRATOR);
        assertThat(scope.readerAt(null)).as("global view").isEqualTo(Reader.ADMINISTRATOR);
        // Reading is all: user decision 2026-11-14, a super-admin never acts on imaging alone.
        refused(() -> scope.requireInterpreterAt(HOSPITAL_A), "report");
        refused(() -> scope.actsAsTechnicianAt(HOSPITAL_A), "exam step");
        refused(() -> scope.requireClinicalReaderAt(HOSPITAL_A), "pixels");
        // With a clinical role held at a hospital, they read there as the care team.
        radiologistAt(HOSPITAL_B);
        assertThat(scope.readerAt(HOSPITAL_B)).isEqualTo(Reader.CLINICAL);
    }

    @Test
    void anAdministratorHereWhoIsADoctorElsewhereStillReadsAsAnAdministratorHere() {
        // Both roles in the token; the clinical one is held at B, the administrative one at A.
        token("ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR");
        radiologistAt(HOSPITAL_B);
        administratorAt(HOSPITAL_A);
        assertThat(scope.readerAt(HOSPITAL_A)).isEqualTo(Reader.ADMINISTRATOR);
        assertThat(scope.readerAt(HOSPITAL_B)).isEqualTo(Reader.CLINICAL);
    }

    @Test
    void aTechnicianHereWhoIsAlsoAnAdministratorHereReadsReportsAsTheAdministrator() {
        token(TECHNICIAN, "ROLE_HOSPITAL_ADMIN");
        technicianAt(HOSPITAL_A);
        // An administrator at another hospital only: a technician here, nothing more.
        administratorAt(HOSPITAL_B);
        assertThat(scope.readerAt(HOSPITAL_A)).isEqualTo(Reader.TECHNICIAN);

        administratorAt(HOSPITAL_A);
        Reader reader = scope.readerAt(HOSPITAL_A);
        assertThat(reader).isEqualTo(Reader.TECHNICIAN_ADMINISTRATOR);
        assertThat(reader.technician()).isTrue();
        assertThat(reader.administrator()).isTrue();
    }

    // ── Ultrasound (owner decision 2026-11-20, provisional) ──

    private static ImagingOrder order(ImagingModality modality, boolean ultrasoundByNonPhysician) {
        ImagingOrder order = orderAt(HOSPITAL_A);
        order.getHospital().setUltrasoundByNonPhysician(ultrasoundByNonPhysician);
        order.setModality(modality);
        return order;
    }

    @Test
    void anUltrasoundIsATechniciansOnlyWhereTheHospitalSaysSo() {
        // The default keeps ultrasound with physicians.
        assertThat(new Hospital().isUltrasoundByNonPhysician()).isFalse();
        assertThat(ImagingRoleScope.mayPerform(order(ImagingModality.ULTRASOUND, false))).isFalse();
        assertThat(ImagingRoleScope.mayPerform(order(ImagingModality.ULTRASOUND, true))).isTrue();
        ImagingOrder refused = order(ImagingModality.ULTRASOUND, false);
        assertThatThrownBy(() -> scope.requireMayPerform(refused)).isInstanceOf(ClientSafeAccessDeniedException.class);
        ImagingOrder allowed = order(ImagingModality.ULTRASOUND, true);
        assertThatCode(() -> scope.requireMayPerform(allowed)).doesNotThrowAnyException();
    }

    @Test
    void everyOtherModalityIsATechniciansWhateverTheSetting() {
        for (ImagingModality modality : ImagingModality.values()) {
            if (modality != ImagingModality.ULTRASOUND) {
                assertThat(ImagingRoleScope.mayPerform(order(modality, false))).as(modality.name()).isTrue();
            }
        }
        // An ultrasound order with no hospital on it is nobody's to perform as a technician.
        ImagingOrder orphan = new ImagingOrder();
        orphan.setModality(ImagingModality.ULTRASOUND);
        assertThat(ImagingRoleScope.mayPerform(orphan)).isFalse();
    }
}
