package com.example.hms.testsupport;

import com.example.hms.security.ClinicalActor;
import com.example.hms.utility.RoleValidator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * QA D2915: the imaging services ask what the caller is AT the order's hospital
 * ({@code ImagingRoleScope}, through {@link RoleValidator}), never what their token names. A
 * unit test that is not about that rule says once, in its set-up, that its caller holds at every
 * hospital the roles its token names; a test with no token at all, or a token naming no role, is
 * a physician everywhere.
 * The tests of the rule itself stub the hospital they mean.
 */
public final class ImagingRolesHeld {

    /** The session user of a test that names none. */
    public static final java.util.UUID CALLER = java.util.UUID.randomUUID();

    /** Where a test that pins no hospital acts. */
    public static final java.util.UUID HOSPITAL = java.util.UUID.randomUUID();

    private ImagingRolesHeld() {
    }

    /** Stubs the hospital-scoped imaging lookups of a mocked {@link RoleValidator} from the test's token. */
    public static void whereverTheTokenNamesThem(RoleValidator roleValidator) {
        // Nobody holds a role without a session user: a test that names none gets one (a later stub wins).
        if (roleValidator.getCurrentUserId() == null) {
            lenient().when(roleValidator.getCurrentUserId()).thenReturn(CALLER);
        }
        // An order built with no hospital is judged where the request acts: somewhere.
        if (roleValidator.getCurrentHospitalId() == null) {
            lenient().when(roleValidator.getCurrentHospitalId()).thenReturn(HOSPITAL);
        }
        lenient().when(roleValidator.interpretsImaging(any(), any()))
            .thenAnswer(call -> tokenNames("DOCTOR", "RADIOLOGIST"));
        lenient().when(roleValidator.readsImagingClinically(any(), any()))
            .thenAnswer(call -> tokenNames("DOCTOR", "RADIOLOGIST", "NURSE", "MIDWIFE"));
        lenient().when(roleValidator.ordersImaging(any(), any()))
            .thenAnswer(call -> tokenNames("DOCTOR", "NURSE", "MIDWIFE"));
        lenient().when(roleValidator.isSigningPhysician(any(), any()))
            .thenAnswer(call -> tokenNames("DOCTOR"));
    }

    private static boolean tokenNames(String... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // A super-admin's inherited DOCTOR is not theirs (ClinicalActor), exactly as at the gates.
        // A session with no role at all (a principal built only to carry the user id) is that physician too.
        return authentication == null || authentication.getAuthorities().isEmpty()
            || ClinicalActor.holdsOwnRoleQuietly(authentication, roles);
    }
}
