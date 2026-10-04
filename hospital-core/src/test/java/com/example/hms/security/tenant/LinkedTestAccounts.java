package com.example.hms.security.tenant;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real local accounts for integration tests that sign in through the real
 * filter chain. Since the one tenant resolver, a request's hospitals are the
 * account's LIVE assignments — on the Keycloak path too, keyed on the
 * {@code appUserId} claim — so a token alone (its {@code hospital_id} or
 * {@code role_assignments} claims) no longer places a caller anywhere.
 * {@link #cleanUp()} deletes what was created, in dependency order.
 */
public final class LinkedTestAccounts {

    /** Unique across the JVM: the users table carries a unique phone number. */
    private static final java.util.concurrent.atomic.AtomicInteger PHONES =
        new java.util.concurrent.atomic.AtomicInteger(new java.util.Random().nextInt(5_000_000));

    private final HospitalRepository hospitals;
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleHospitalAssignmentRepository assignments;
    private final AuditEventLogRepository audit;

    private final List<UUID> hospitalIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> assignmentIds = new ArrayList<>();

    public LinkedTestAccounts(HospitalRepository hospitals, UserRepository users, RoleRepository roles,
                              UserRoleHospitalAssignmentRepository assignments, AuditEventLogRepository audit) {
        this.hospitals = hospitals;
        this.users = users;
        this.roles = roles;
        this.assignments = assignments;
        this.audit = audit;
    }

    /** A hospital row with a unique code. */
    public Hospital hospital(String name) {
        String n = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Hospital saved = hospitals.save(Hospital.builder()
            .name(name + " " + n)
            .code("LTA" + n)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .email("lta" + n.toLowerCase() + "@hospital.test")
            .active(true)
            .build());
        hospitalIds.add(saved.getId());
        return saved;
    }

    /**
     * An active local account holding each role at {@code hospitalId} ({@code null}: a global
     * assignment). The username is {@code username} plus a unique suffix; read it back from the
     * returned user for the token's {@code preferred_username}.
     */
    public User userAt(String username, UUID hospitalId, String... roleCodes) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = users.save(User.builder()
            .username(username + "-" + suffix)
            .passwordHash("hashed-password")
            .email(username + "-" + suffix + "@linked.test")
            .firstName(username)
            .lastName("Linked")
            .phoneNumber(String.format("+2267%07d", PHONES.incrementAndGet()))
            .isActive(true)
            .build());
        userIds.add(user.getId());
        Hospital at = hospitalId == null ? null : hospitals.findById(hospitalId).orElseThrow();
        for (String code : roleCodes) {
            String roleCode = code.startsWith("ROLE_") ? code : "ROLE_" + code;
            Role role = roles.findByCode(roleCode).orElseGet(() -> roles.save(Role.builder()
                .name(roleCode).code(roleCode).description(roleCode + " role").build()));
            assignmentIds.add(assignments.save(UserRoleHospitalAssignment.builder()
                .assignmentCode("ASSIGN-LTA-" + UUID.randomUUID().toString().substring(0, 8))
                .description(roleCode + " assignment")
                .user(user)
                .hospital(at)
                .role(role)
                .startDate(LocalDate.now())
                .assignedAt(LocalDateTime.now())
                .active(true)
                .build()).getId());
        }
        return user;
    }

    public void cleanUp() {
        // Audit rows (a refused hospital, a global-view read) point at the
        // account and the assignment: they go first.
        audit.deleteAllInBatch(audit.findAll().stream()
            .filter(row -> row.getUser() != null && userIds.contains(row.getUser().getId())
                || row.getAssignment() != null && assignmentIds.contains(row.getAssignment().getId()))
            .toList());
        assignments.deleteAllByIdInBatch(assignmentIds);
        users.deleteAllByIdInBatch(userIds);
        hospitals.deleteAllByIdInBatch(hospitalIds);
        assignmentIds.clear();
        userIds.clear();
        hospitalIds.clear();
    }
}
