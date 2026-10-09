package com.example.hms.security.tenant;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.RoleExpansion;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The case matrix of docs/security/tenant-resolution.md §1.4, asserting the
 * TARGET answer of the one resolver (§3.3), plus the cases §4.3 adds. The
 * fixture letters are the design's:
 * <ul>
 *   <li>(a) staff with one active assignment at A;</li>
 *   <li>(b) staff at A and B, no header;</li>
 *   <li>(c) super-admin, no header, an incidental clinical assignment at C;</li>
 *   <li>(d) super-admin sending {@code X-Hospital-Id: H};</li>
 *   <li>(e) patient with only the global ROLE_PATIENT assignment;</li>
 *   <li>(f) staff whose only assignment (at A) was deactivated after sign-in;</li>
 *   <li>(g) Keycloak token with no local account link.</li>
 * </ul>
 * The producer side is the same on both auth paths ({@link ActingScopeResolver#liveContext}
 * is what the password filter and the Keycloak resolver both call), so one
 * matrix covers both; the path-specific wiring is exercised by
 * {@code HospitalHeaderEmptyScopeFilterTest} and {@code KeycloakHospitalContextFilterTest}.
 */
class ActingScopeResolverTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID H = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    private final TenantRoleAssignmentAccessor accessor = mock(TenantRoleAssignmentAccessor.class);
    private final UserRoleHospitalAssignmentRepository repository = mock(UserRoleHospitalAssignmentRepository.class);
    private final CrossTenantReadAudit audit = mock(CrossTenantReadAudit.class);
    private ActingScopeResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CrossTenantReadAudit> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(audit);
        resolver = new ActingScopeResolver(accessor, repository, provider);
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ── the producer: live assignments + header, identical on both paths ──

    private ActingScope produce(String header, TenantRoleAssignment... held) {
        when(accessor.findAssignmentsForUser(USER)).thenReturn(List.of(held));
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader("X-Hospital-Id", header);
        }
        HospitalContext context = resolver.withHeader(resolver.liveContext(USER, "someone"), request);
        HospitalContextHolder.setContext(context);
        return ActingScopeResolver.scopeOf(context);
    }

    private static TenantRoleAssignment at(UUID hospital, String role) {
        return new TenantRoleAssignment(hospital, ORG, role, role, true, com.example.hms.enums.FacilityType.HOSPITAL);
    }

    private static TenantRoleAssignment revokedAt(UUID hospital, String role) {
        return new TenantRoleAssignment(hospital, ORG, role, role, false, com.example.hms.enums.FacilityType.HOSPITAL);
    }

    private static TenantRoleAssignment superAdmin() {
        return new TenantRoleAssignment(null, null, "ROLE_SUPER_ADMIN", "ROLE_SUPER_ADMIN", true, null);
    }

    @Nested
    @DisplayName("a patient-only caller is bounded by ownership, never AMBIGUOUS (design Q1)")
    class PatientOwnership {

        private TenantRoleAssignment patientAt(UUID hospital) {
            return new TenantRoleAssignment(hospital, ORG, "ROLE_PATIENT", "ROLE_PATIENT", true, com.example.hms.enums.FacilityType.HOSPITAL);
        }

        @Test
        @DisplayName("registered at two hospitals, none named: PatientOwned, no pinned hospital, both readable")
        void twoHospitalsIsNotAmbiguous() {
            assertThat(produce(null, patientAt(A), patientAt(B))).isEqualTo(new ActingScope.PatientOwned(USER));
            HospitalContext context = HospitalContextHolder.getContextOrEmpty();
            assertThat(context.pinnedHospitalId()).isNull();
            assertThat(context.getScopeRefusal()).isNull();
            assertThat(ActingScopeResolver.readableHospitalIds(context)).containsExactlyInAnyOrder(A, B);
        }

        @Test
        @DisplayName("one hospital too: ownership, not a pin")
        void oneHospitalIsOwnershipToo() {
            assertThat(produce(null, patientAt(A))).isEqualTo(new ActingScope.PatientOwned(USER));
        }

        @Test
        @DisplayName("a hospital-needing adapter refuses 403 PATIENT_OWNED; it never answers null (unscoped)")
        void adaptersRefuseInsteadOfNull() {
            produce(null, patientAt(A), patientAt(B));
            assertThatThrownBy(() -> resolver.requirePinned())
                .isInstanceOf(com.example.hms.exception.HospitalScopeRefusedException.class)
                .extracting(e -> ((com.example.hms.exception.HospitalScopeRefusedException) e).getReason())
                .isEqualTo("PATIENT_OWNED");
            assertThat(ActingScopeResolver.pinnedHospitalIdOrNull()).isNull();
        }

        @Test
        @DisplayName("a patient who is also staff is not patient-only: several hospitals, none named, stays AMBIGUOUS")
        void staffWhoIsAlsoAPatient() {
            assertThat(produce(null, patientAt(A), at(B, "ROLE_NURSE"), at(C, "ROLE_NURSE")))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.AMBIGUOUS));
        }

        @Test
        @DisplayName("naming a registered hospital pins it; an unregistered one is refused")
        void namingAHospital() {
            assertThat(produce(A.toString(), patientAt(A), patientAt(B)))
                .isEqualTo(new ActingScope.Pinned(A, ActingScope.Source.HEADER));
            assertThat(produce(C.toString(), patientAt(A), patientAt(B)))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NOT_PERMITTED));
        }
    }

    @Nested
    @DisplayName("scope-establishing paths ignore a refused header instead of refusing the request")
    class ScopeEstablishing {

        private HospitalContext onPath(String uri, String header, TenantRoleAssignment... held) {
            when(accessor.findAssignmentsForUser(USER)).thenReturn(List.of(held));
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api" + uri);
            request.setContextPath("/api");
            request.addHeader("X-Hospital-Id", header);
            return resolver.withHeader(resolver.liveContext(USER, "someone"), request);
        }

        @Test
        @DisplayName("each named path answers on the live scope, and the refusal is still audited")
        void ignoredAndAudited() {
            when(repository.existsByUserIdAndHospitalIdAndActiveFalse(USER, B)).thenReturn(true);
            for (String path : ActingScopeResolver.SCOPE_ESTABLISHING_PATHS) {
                HospitalContext context = onPath(path, B.toString(), at(A, "ROLE_DOCTOR"));
                assertThat(ActingScopeResolver.isRefusedHeader(context)).as(path).isFalse();
                assertThat(context.pinnedHospitalId()).as(path).isEqualTo(A);
            }
            verify(audit, times(ActingScopeResolver.SCOPE_ESTABLISHING_PATHS.size()))
                .recordRefusal(USER, "someone", B, ActingScope.Reason.NO_LONGER_PERMITTED, ActingScope.Source.HEADER);
        }

        @Test
        @DisplayName("a data path, or a path that only starts like one, still carries the refusal")
        void dataPathsStillRefuse() {
            assertThat(ActingScopeResolver.isRefusedHeader(onPath("/me/hospital", B.toString(), at(A, "ROLE_DOCTOR"))))
                .isTrue();
            assertThat(ActingScopeResolver.isRefusedHeader(
                onPath("/me/assignments/other", B.toString(), at(A, "ROLE_DOCTOR")))).isTrue();
        }
    }

    @Nested
    @DisplayName("the active organisation follows the acting hospital (policies, plan gating; never a read scope)")
    class ActiveOrganization {

        private final UUID orgA = UUID.randomUUID();
        private final UUID orgB = UUID.randomUUID();

        private TenantRoleAssignment in(UUID organization, UUID hospital) {
            return new TenantRoleAssignment(hospital, organization, "ROLE_DOCTOR", "ROLE_DOCTOR", true, com.example.hms.enums.FacilityType.HOSPITAL);
        }

        @Test
        @DisplayName("several hospitals of two organisations: none until one is named; the header sets its organisation")
        void headerSetsTheNamedHospitalsOrganization() {
            produce(null, in(orgA, A), in(orgB, B));
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveOrganizationId()).isNull();

            produce(B.toString(), in(orgA, A), in(orgB, B));
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveOrganizationId()).isEqualTo(orgB);
        }

        @Test
        @DisplayName("a ?hospitalId= narrow sets the named hospital's organisation too")
        void narrowSetsTheNamedHospitalsOrganization() {
            produce(null, in(orgA, A), in(orgB, B));
            resolver.narrowTo(A);
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveOrganizationId()).isEqualTo(orgA);
        }

        @Test
        @DisplayName("several hospitals of one organisation, or an organisation-level assignment only: that organisation")
        void aSingleOrganizationIsActiveWithoutAPick() {
            produce(null, in(orgA, A), in(orgA, B));
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveOrganizationId()).isEqualTo(orgA);

            produce(null, in(orgA, null));
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveOrganizationId()).isEqualTo(orgA);
        }

        @Test
        @DisplayName("a super-admin naming a hospital they hold no assignment at has no organisation, not a stale one")
        void superAdminNamingAnotherTenantHasNoOrganization() {
            produce(C.toString(), superAdmin(), in(orgA, A));
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveOrganizationId()).isNull();
        }
    }

    @Nested
    @DisplayName("the §1.4 matrix, target answers")
    class Matrix {

        @Test
        @DisplayName("(a) one hospital: pinned to it, SOLE_ASSIGNMENT")
        void a() {
            assertThat(produce(null, at(A, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Pinned(A, ActingScope.Source.SOLE_ASSIGNMENT));
        }

        @Test
        @DisplayName("(b) two hospitals, no header: AMBIGUOUS, never the newest (Q2 A); the header settles it")
        void b() {
            assertThat(produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.AMBIGUOUS));
            assertThat(HospitalContextHolder.getContextOrEmpty().getActiveHospitalId()).isNull();
            assertThat(produce(B.toString(), at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Pinned(B, ActingScope.Source.HEADER));
        }

        @Test
        @DisplayName("(c) super-admin, no header: GLOBAL — an incidental assignment pins nothing (D2, D3)")
        void c() {
            assertThat(produce(null, superAdmin(), at(C, "ROLE_DOCTOR"))).isEqualTo(new ActingScope.Global(USER));
            HospitalContext context = HospitalContextHolder.getContextOrEmpty();
            assertThat(context.getActiveHospitalId()).as("no raw active hospital to leak").isNull();
            assertThat(context.isGlobalView()).isTrue();
        }

        @Test
        @DisplayName("(d) super-admin naming H: pinned to H, whether or not H is held")
        void d() {
            assertThat(produce(H.toString(), superAdmin()))
                .isEqualTo(new ActingScope.Pinned(H, ActingScope.Source.HEADER));
        }

        @Test
        @DisplayName("(e) patient: bounded by ownership, not by a hospital (Q1); naming one they do not hold is refused NOT_PERMITTED")
        void e() {
            TenantRoleAssignment patient = new TenantRoleAssignment(null, null, "ROLE_PATIENT", "ROLE_PATIENT", true, null);
            assertThat(produce(null, patient)).isEqualTo(new ActingScope.PatientOwned(USER));
            assertThat(produce(A.toString(), patient))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NOT_PERMITTED));
        }

        @Test
        @DisplayName("(f) revoked after sign-in: NO_HOSPITAL on the next request; naming it is NO_LONGER_PERMITTED")
        void f() {
            assertThat(produce(null, revokedAt(A, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NO_HOSPITAL));
            when(repository.existsByUserIdAndHospitalIdAndActiveFalse(USER, A)).thenReturn(true);
            assertThat(produce(A.toString(), revokedAt(A, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NO_LONGER_PERMITTED));
        }

        @Test
        @DisplayName("(g) Keycloak principal with no local account: NO_LOCAL_USER, not a super-admin")
        void g() {
            HospitalContext context = ActingScopeResolver.unlinkedContext("kc.user");
            assertThat(ActingScopeResolver.scopeOf(context))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NO_LOCAL_USER));
            assertThat(context.isSuperAdmin()).isFalse();
            assertThat(context.getPermittedHospitalIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the §4.3 additions")
    class Additions {

        @Test
        @DisplayName("a demoted super-admin (the assignment deactivated) loses global view on the next request")
        void demotedSuperAdmin() {
            TenantRoleAssignment demoted = new TenantRoleAssignment(null, null, "ROLE_SUPER_ADMIN", "ROLE_SUPER_ADMIN", false, null);
            assertThat(produce(null, demoted, at(C, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Pinned(C, ActingScope.Source.SOLE_ASSIGNMENT));
            assertThat(HospitalContextHolder.getContextOrEmpty().isSuperAdmin()).isFalse();
        }

        @Test
        @DisplayName("a super-admin who picked DOCTOR at login is a super-admin from the first request (Q4 B)")
        void superAdminWhoPickedDoctor() {
            // The token carries ROLE_DOCTOR only; the live table still grants SUPER_ADMIN.
            assertThat(produce(null, superAdmin(), at(C, "ROLE_DOCTOR"))).isEqualTo(new ActingScope.Global(USER));
            assertThat(RoleExpansion.reconcile(List.of("ROLE_DOCTOR"), true, Set.of("ROLE_SUPER_ADMIN")))
                .as("nothing to strip: the token asserts no super-admin authority")
                .containsExactly("ROLE_DOCTOR");
        }

        @Test
        @DisplayName("a SUPER_ADMIN role the table does not back grants no global view, and is stripped (Q10 A)")
        void unbackedSuperAdminRole() {
            assertThat(produce(null, at(A, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Pinned(A, ActingScope.Source.SOLE_ASSIGNMENT));
            Set<String> tokenAuthorities = RoleExpansion.expand(List.of("ROLE_SUPER_ADMIN"));
            assertThat(RoleExpansion.reconcile(tokenAuthorities, false, Set.of("ROLE_DOCTOR")))
                .containsExactly("ROLE_DOCTOR");
        }

        @Test
        @DisplayName("an out-of-set header: NO_LONGER_PERMITTED with an inactive assignment there, NOT_PERMITTED with none")
        void outOfSetHeaders() {
            when(repository.existsByUserIdAndHospitalIdAndActiveFalse(USER, B)).thenReturn(true);
            assertThat(produce(B.toString(), at(A, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NO_LONGER_PERMITTED));
            assertThat(produce(C.toString(), at(A, "ROLE_DOCTOR")))
                .isEqualTo(new ActingScope.Refused(ActingScope.Reason.NOT_PERMITTED));
        }

        @Test
        @DisplayName("a receptionist naming an unassigned hospital is refused and audited, not given the context hospital (D12)")
        void receptionistNamingAnUnassignedHospital() {
            produce(null, at(A, "ROLE_RECEPTIONIST"));
            ControllerAuthUtils utils = new ControllerAuthUtils(resolver);
            var receptionist = UsernamePasswordAuthenticationToken.authenticated("r", null,
                List.of(new SimpleGrantedAuthority("ROLE_RECEPTIONIST")));

            assertThatThrownBy(() -> utils.resolveHospitalScope(receptionist, B, true))
                .isInstanceOf(HospitalScopeRefusedException.class);
            verify(audit).recordRefusal(USER, "someone", B, ActingScope.Reason.NOT_PERMITTED,
                ActingScope.Source.REQUESTED);
            assertThat(utils.resolveHospitalScope(receptionist, null, true)).isEqualTo(A);
        }

        @Test
        @DisplayName("narrowTo after the seal, or a second narrow elsewhere, is a programming error; the same hospital is a no-op")
        void narrowAfterSeal() {
            produce(A.toString(), at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            resolver.current();
            assertThat(resolver.narrowTo(A)).isEqualTo(new ActingScope.Pinned(A, ActingScope.Source.HEADER));
            assertThatThrownBy(() -> resolver.narrowTo(B)).isInstanceOf(IllegalStateException.class);

            produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            assertThat(resolver.narrowTo(B)).isEqualTo(new ActingScope.Pinned(B, ActingScope.Source.REQUESTED));
            assertThatThrownBy(() -> resolver.narrowTo(A)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("the adapters")
    class Adapters {

        @Test
        @DisplayName("requireActiveHospitalId: pinned → id, global → null, refused → the same BusinessException")
        void requireActiveHospitalId() {
            RoleValidator validator = new RoleValidator(repository, resolver);
            produce(null, at(A, "ROLE_DOCTOR"));
            assertThat(validator.requireActiveHospitalId()).isEqualTo(A);
            produce(null, superAdmin());
            assertThat(validator.requireActiveHospitalId()).isNull();
            produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            assertThatThrownBy(validator::requireActiveHospitalId)
                .isInstanceOf(BusinessException.class)
                .hasMessage(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }

        @Test
        @DisplayName("step 4 is gone: SUPER_ADMIN authorities with no verified context never reach an unscoped read")
        void stepFourIsGone() {
            RoleValidator validator = new RoleValidator(repository, resolver);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "inflated", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

            assertThatThrownBy(validator::requireActiveHospitalId).isInstanceOf(BusinessException.class);
            assertThat(validator.isSuperAdminFromAuth()).as("the deprecated adapter follows the verified signal").isFalse();
            assertThat(validator.isSuperAdminFromJwtClaim()).isFalse();
        }

        @Test
        @DisplayName("resolveHospitalScope honours a super-admin's header (D1): it used to ignore it and answer global")
        void resolveHospitalScopeHonoursTheHeader() {
            produce(H.toString(), superAdmin());
            ControllerAuthUtils utils = new ControllerAuthUtils(resolver);
            assertThat(utils.resolveHospitalScope(null, null, false)).isEqualTo(H);
        }

        @Test
        @DisplayName("resolveHospitalScope: several hospitals and none named is refused, never the newest")
        void resolveHospitalScopeRefusesAmbiguity() {
            produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            ControllerAuthUtils utils = new ControllerAuthUtils(resolver);
            assertThatThrownBy(() -> utils.resolveHospitalScope(null, null, false))
                .isInstanceOf(BusinessException.class);

            // A new request naming B is served at B (§3.4).
            produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            assertThat(utils.resolveHospitalScope(null, B, false)).isEqualTo(B);
        }

        @Test
        @DisplayName("requirePinned: global view and every refusal answer 403")
        void requirePinned() {
            produce(null, superAdmin());
            assertThatThrownBy(resolver::requirePinned)
                .isInstanceOf(HospitalScopeRefusedException.class)
                .extracting(e -> ((HospitalScopeRefusedException) e).getReason())
                .isEqualTo(HospitalScopeRefusedException.GLOBAL_VIEW);
            produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            assertThatThrownBy(resolver::requirePinned)
                .extracting(e -> ((HospitalScopeRefusedException) e).getReason())
                .isEqualTo("AMBIGUOUS");
            produce(null, at(A, "ROLE_DOCTOR"));
            assertThat(resolver.requirePinned()).isEqualTo(A);
        }

        @Test
        @DisplayName("the repository scope: a pinned super-admin reads the pin, staff their hospitals, never an organisation")
        void readableHospitalIds() {
            produce(H.toString(), superAdmin());
            assertThat(ActingScopeResolver.readableHospitalIds(HospitalContextHolder.getContextOrEmpty()))
                .containsExactly(H);
            produce(null, at(A, "ROLE_DOCTOR"), at(B, "ROLE_DOCTOR"));
            HospitalContext staff = HospitalContextHolder.getContextOrEmpty();
            assertThat(ActingScopeResolver.readableHospitalIds(staff)).containsExactlyInAnyOrder(A, B);
            assertThat(staff.getPermittedOrganizationIds()).as("kept for the lifecycle gate only").containsExactly(ORG);
        }

        @Test
        @DisplayName("no local user id: the NO_LOCAL_USER context, and no assignment read")
        void noLocalUserId() {
            HospitalContext context = resolver.liveContext(null, "kc.user");
            assertThat(context.getScopeRefusal()).isEqualTo(ActingScope.Reason.NO_LOCAL_USER);
            verify(accessor, never()).findAssignmentsForUser(any());
        }
    }
}
