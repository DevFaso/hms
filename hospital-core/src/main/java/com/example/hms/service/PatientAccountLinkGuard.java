package com.example.hms.service;

import com.example.hms.exception.ConflictException;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * QA D819: the account a patient record logs in with must be a patient-only
 * account. {@code POST /patients} took any {@code userId}, so a receptionist
 * linked a new patient to her own staff account, and that staff login became
 * the patient's portal login. Admin-register already refused a staff e-mail or
 * phone (QA D24), but the raw-{@code userId} paths never asked.
 *
 * <p>Every path that writes {@code Patient.user} (the create paths and an
 * update that changes the linked account) goes through here:
 * <ul>
 *   <li>no staff record, no role assignment other than PATIENT and no global
 *       role other than PATIENT on the account;</li>
 *   <li>no other patient record already linked to the account (one person,
 *       one record: a second hospital links the existing record through
 *       {@code POST /registrations}).</li>
 * </ul>
 * Both refusals are a translated 409 on the {@code userId} field.
 */
@Component
@RequiredArgsConstructor
public class PatientAccountLinkGuard {

    static final String STAFF_ACCOUNT_KEY = "userId:patient.account.staffAccount";
    static final String ALREADY_LINKED_KEY = "userId:patient.account.alreadyLinked";
    private static final String PATIENT = "PATIENT";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;

    /**
     * The account may be linked to the patient record {@code patientId}
     * ({@code null} for a record not created yet).
     *
     * @throws ConflictException when the account is a staff account, or is
     *                           already the login of another patient record
     */
    public void requireLinkable(User user, UUID patientId) {
        if (user == null || user.getId() == null) {
            return;
        }
        if (isStaffAccount(user)) {
            throw new ConflictException(STAFF_ACCOUNT_KEY);
        }
        boolean linkedElsewhere = patientId == null
            ? patientRepository.existsByUserId(user.getId())
            : patientRepository.existsByUser_IdAndIdNot(user.getId(), patientId);
        if (linkedElsewhere) {
            throw new ConflictException(ALREADY_LINKED_KEY);
        }
    }

    /**
     * A staff record, a role assignment other than PATIENT (active or not),
     * or a global role other than PATIENT (a super-admin holds no assignment).
     */
    public boolean isStaffAccount(User user) {
        UUID userId = user.getId();
        if (staffRepository.existsByUserId(userId)
            || assignmentRepository.existsByUserIdAndRole_CodeNot(userId, ROLE_PATIENT)) {
            return true;
        }
        Set<UserRole> globalRoles = user.getUserRoles();
        return globalRoles != null && globalRoles.stream()
            .map(UserRole::getRole)
            .filter(Objects::nonNull)
            .anyMatch(role -> !isPatientRole(role));
    }

    private static boolean isPatientRole(Role role) {
        String code = role.getCode() != null ? role.getCode() : role.getName();
        if (code == null) {
            return false;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return PATIENT.equals(normalized) || ROLE_PATIENT.equals(normalized);
    }
}
