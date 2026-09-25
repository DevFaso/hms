package com.example.hms.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * One definition of "the caller is the patient and nobody else" for the
 * prescription reads.
 *
 * <p>{@code GET /prescriptions/{id}} admits a handful of clinical roles plus
 * {@code ROLE_PATIENT}, so on that endpoint "holds ROLE_PATIENT and no
 * clinical reader role" is the same thing as "a pure patient principal" —
 * which is exactly why the set below must mirror the annotation, neither
 * exceeding nor falling short of it. Two decisions hang off it and they must
 * not drift apart:
 * <ol>
 *   <li>the patient's copy omits the pharmacist-to-prescriber clarification
 *       exchange ({@code PrescriptionController.getById}), and</li>
 *   <li>the patient may only read <b>their own</b> prescription
 *       ({@code PrescriptionServiceImpl.getPrescriptionById}).</li>
 * </ol>
 *
 * <p>A clinician who happens to be a patient at the hospital is not
 * patient-only: the clinical role wins, as it already did for the redaction.
 * A super-admin is not patient-only either on the password path, where
 * {@link com.example.hms.security.RoleExpansion#SUPER_ADMIN_INHERITS} grants
 * {@code ROLE_DOCTOR} alongside {@code ROLE_PATIENT}. Which roles count, and
 * why the set mirrors the annotation exactly rather than reaching wider, is on
 * the constant below.
 *
 * <p>Not to be confused with {@code RoleValidator.isPatientOnlyFromAuth()},
 * which answers a similar-sounding question with a different staff set: it
 * omits {@code ROLE_PHARMACIST}, so a pharmacist who is also a patient is
 * "patient only" there and a clinical reader here. For a prescription
 * decision, this class is the one to use.
 */
public final class PrescriptionReaderRoles {

    /**
     * The roles that read a prescription as a clinician rather than as its
     * subject.
     *
     * <p><b>The invariant, and it is the whole of it: this set is its
     * endpoint’s {@code @PreAuthorize} list minus {@code ROLE_PATIENT}, with
     * nothing added.</b> "Nothing added" is the operative half, because this
     * set does not GRANT access — the annotation already did that, before this
     * code runs — it REMOVES subject status, deciding who is not a patient and
     * therefore skips the ownership check. Roles named for role-expansion
     * parity are right in a set that opens a door and wrong in one that closes
     * one: {@code ROLE_PHYSICIAN} and {@code ROLE_SURGEON} were briefly named
     * here for that reason and it was backwards, because a principal holding
     * one of them plus {@code ROLE_PATIENT} enters through the PATIENT door on
     * the Keycloak path (no expansion there) and naming the role would then
     * have waived the very guard that principal needs.
     *
     * <p>{@code ROLE_SUPER_ADMIN} needs no exception either, and this is worth
     * checking rather than assuming: {@code SUPER_ADMIN_INHERITS} does include
     * {@code ROLE_PATIENT}, but it includes {@code ROLE_DOCTOR} in the same
     * breath, and that is already in this set — so an expanded super-admin is
     * a clinical reader without being named. Unexpanded, on the OIDC path, they
     * hold no {@code ROLE_PATIENT} either unless the realm grants it, and if it
     * does they are refused, like the surgeon, in the safe direction.
     *
     * <p>{@code PrescriptionAfterWriteCallerGuardTest.theRoleSetMirrorsTheAnnotation}
     * fails if the two ever disagree — in either direction, because each is a
     * different defect. Wider re-opens the door this guard closed, as above.
     * Narrower refuses a clinician their colleagues’ orders.
     *
     * <p>{@code ROLE_PHARMACY_VERIFIER} is in the set because #737 added it to
     * the annotation, and for the reason that PR gave: the role may RAISE a
     * clarification, so withholding the prescriber’s answer from it would
     * leave the question it asked unanswerable. A verifier who is also a
     * patient of the hospital reads as a clinician here, as a pharmacist
     * already did.
     *
     * <p>The cost of that, stated rather than hidden: a surgeon who is also a
     * patient reads a colleague’s prescription over a password login and is
     * refused it over SSO. The fix belongs on the OIDC path or in the
     * annotation — both of which change who the ANNOTATION admits, which this
     * set would then follow — never in an exemption here.
     */
    public static final Set<String> CLINICAL_READER_ROLES = Set.of(
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST",
        "ROLE_PHARMACY_VERIFIER");

    private PrescriptionReaderRoles() {
    }

    /**
     * True when {@code auth} holds {@code ROLE_PATIENT} and no clinical
     * reader role. A {@code null} authentication is not patient-only: the
     * handler sits behind {@code @PreAuthorize}, so an unauthenticated call
     * cannot reach it, and the service is also called from clinical paths
     * (clarification request/resolve) whose answer must not change.
     */
    public static boolean isPatientOnly(Authentication auth) {
        if (auth == null) {
            return false;
        }
        boolean patient = false;
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (CLINICAL_READER_ROLES.contains(name)) {
                return false;
            }
            if ("ROLE_PATIENT".equals(name)) {
                patient = true;
            }
        }
        return patient;
    }
}
