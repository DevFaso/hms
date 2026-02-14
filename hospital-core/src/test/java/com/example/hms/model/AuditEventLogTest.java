package com.example.hms.model;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;

class AuditEventLogTest {

    // ───────────── helpers ─────────────

    private User buildUser(UUID id, String first, String last, String email) {
        User u = new User();
        u.setId(id);
        u.setFirstName(first);
        u.setLastName(last);
        u.setEmail(email);
        return u;
    }

    private Hospital buildHospital(String name) {
        Hospital h = new Hospital();
        h.setName(name);
        return h;
    }

    private UserRoleHospitalAssignment buildAssignment(User user, Hospital hospital) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setUser(user);
        a.setHospital(hospital);
        return a;
    }

    /**
     * Reflectively invokes the private validateAndSnapshot() callback.
     */
    private void invokeValidateAndSnapshot(AuditEventLog log) {
        try {
            Method m = AuditEventLog.class.getDeclaredMethod("validateAndSnapshot");
            m.setAccessible(true);
            m.invoke(log);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new RuntimeException(e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ═══════════════ NoArgsConstructor ═══════════════

    @Nested
    @DisplayName("NoArgsConstructor")
    class NoArgsCtor {

        @Test
        @DisplayName("creates instance with all fields null/default")
        void defaultValues() {
            AuditEventLog log = new AuditEventLog();

            assertAll(
                () -> assertNull(log.getRoleName()),
                () -> assertNull(log.getUser()),
                () -> assertNull(log.getAssignment()),
                () -> assertNull(log.getEventType()),
                () -> assertNull(log.getEventDescription()),
                () -> assertNull(log.getEventTimestamp()),
                () -> assertNull(log.getIpAddress()),
                () -> assertNull(log.getStatus()),
                () -> assertNull(log.getUserName()),
                () -> assertNull(log.getHospitalName()),
                () -> assertNull(log.getDetails()),
                () -> assertNull(log.getResourceId()),
                () -> assertNull(log.getEntityType()),
                () -> assertNull(log.getResourceName()),
                () -> assertNull(log.getId())
            );
        }
    }

    // ═══════════════ AllArgsConstructor ═══════════════

    @Nested
    @DisplayName("AllArgsConstructor")
    class AllArgsCtor {

        @Test
        @DisplayName("creates instance with all fields populated")
        void allFieldsPopulated() {
            User user = buildUser(UUID.randomUUID(), "John", "Doe", "john@test.com");
            Hospital hospital = buildHospital("General Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(user, hospital);
            LocalDateTime ts = LocalDateTime.of(2026, 2, 11, 10, 0);

            AuditEventLog log = new AuditEventLog(
                "ADMIN",                       // roleName
                user,                          // user
                assignment,                    // assignment
                AuditEventType.LOGIN,          // eventType
                "User logged in",              // eventDescription
                ts,                            // eventTimestamp
                "192.168.1.1",                 // ipAddress
                AuditStatus.SUCCESS,           // status
                "John Doe",                    // userName
                "General Hospital",            // hospitalName
                "{\"key\":\"val\"}",           // details
                "res-001",                     // resourceId
                "Patient",                     // entityType
                "Resource X"                   // resourceName
            );

            assertAll(
                () -> assertEquals("ADMIN", log.getRoleName()),
                () -> assertSame(user, log.getUser()),
                () -> assertSame(assignment, log.getAssignment()),
                () -> assertEquals(AuditEventType.LOGIN, log.getEventType()),
                () -> assertEquals("User logged in", log.getEventDescription()),
                () -> assertEquals(ts, log.getEventTimestamp()),
                () -> assertEquals("192.168.1.1", log.getIpAddress()),
                () -> assertEquals(AuditStatus.SUCCESS, log.getStatus()),
                () -> assertEquals("John Doe", log.getUserName()),
                () -> assertEquals("General Hospital", log.getHospitalName()),
                () -> assertEquals("{\"key\":\"val\"}", log.getDetails()),
                () -> assertEquals("res-001", log.getResourceId()),
                () -> assertEquals("Patient", log.getEntityType()),
                () -> assertEquals("Resource X", log.getResourceName())
            );
        }
    }

    // ═══════════════ Builder ═══════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds with all fields populated")
        void builderAllFields() {
            User user = buildUser(UUID.randomUUID(), "Jane", "Smith", "jane@test.com");
            Hospital hospital = buildHospital("City Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(user, hospital);
            LocalDateTime ts = LocalDateTime.of(2026, 3, 1, 14, 30);

            AuditEventLog log = AuditEventLog.builder()
                .roleName("DOCTOR")
                .user(user)
                .assignment(assignment)
                .eventType(AuditEventType.PATIENT_CREATE)
                .eventDescription("Created patient record")
                .eventTimestamp(ts)
                .ipAddress("10.0.0.1")
                .status(AuditStatus.SUCCESS)
                .userName("Jane Smith")
                .hospitalName("City Hospital")
                .details("some-details")
                .resourceId("res-xyz")
                .entityType("Patient")
                .resourceName("Patient #123")
                .build();

            assertAll(
                () -> assertEquals("DOCTOR", log.getRoleName()),
                () -> assertSame(user, log.getUser()),
                () -> assertSame(assignment, log.getAssignment()),
                () -> assertEquals(AuditEventType.PATIENT_CREATE, log.getEventType()),
                () -> assertEquals("Created patient record", log.getEventDescription()),
                () -> assertEquals(ts, log.getEventTimestamp()),
                () -> assertEquals("10.0.0.1", log.getIpAddress()),
                () -> assertEquals(AuditStatus.SUCCESS, log.getStatus()),
                () -> assertEquals("Jane Smith", log.getUserName()),
                () -> assertEquals("City Hospital", log.getHospitalName()),
                () -> assertEquals("some-details", log.getDetails()),
                () -> assertEquals("res-xyz", log.getResourceId()),
                () -> assertEquals("Patient", log.getEntityType()),
                () -> assertEquals("Patient #123", log.getResourceName())
            );
        }

        @Test
        @DisplayName("builds with minimal fields (nulls)")
        void builderMinimal() {
            AuditEventLog log = AuditEventLog.builder().build();

            assertAll(
                () -> assertNull(log.getRoleName()),
                () -> assertNull(log.getUser()),
                () -> assertNull(log.getAssignment()),
                () -> assertNull(log.getEventType()),
                () -> assertNull(log.getEventDescription()),
                () -> assertNull(log.getEventTimestamp()),
                () -> assertNull(log.getIpAddress()),
                () -> assertNull(log.getStatus()),
                () -> assertNull(log.getUserName()),
                () -> assertNull(log.getHospitalName()),
                () -> assertNull(log.getDetails()),
                () -> assertNull(log.getResourceId()),
                () -> assertNull(log.getEntityType()),
                () -> assertNull(log.getResourceName())
            );
        }
    }

    // ═══════════════ Getters / Setters ═══════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSetters {

        @Test
        @DisplayName("roleName getter/setter")
        void roleName() {
            AuditEventLog log = new AuditEventLog();
            log.setRoleName("NURSE");
            assertEquals("NURSE", log.getRoleName());
        }

        @Test
        @DisplayName("user getter/setter")
        void user() {
            AuditEventLog log = new AuditEventLog();
            User u = new User();
            log.setUser(u);
            assertSame(u, log.getUser());
        }

        @Test
        @DisplayName("assignment getter/setter")
        void assignment() {
            AuditEventLog log = new AuditEventLog();
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            log.setAssignment(a);
            assertSame(a, log.getAssignment());
        }

        @Test
        @DisplayName("eventType getter/setter")
        void eventType() {
            AuditEventLog log = new AuditEventLog();
            log.setEventType(AuditEventType.LOGOUT);
            assertEquals(AuditEventType.LOGOUT, log.getEventType());
        }

        @Test
        @DisplayName("eventDescription getter/setter")
        void eventDescription() {
            AuditEventLog log = new AuditEventLog();
            log.setEventDescription("A description");
            assertEquals("A description", log.getEventDescription());
        }

        @Test
        @DisplayName("eventTimestamp getter/setter")
        void eventTimestamp() {
            AuditEventLog log = new AuditEventLog();
            LocalDateTime ts = LocalDateTime.now();
            log.setEventTimestamp(ts);
            assertEquals(ts, log.getEventTimestamp());
        }

        @Test
        @DisplayName("ipAddress getter/setter")
        void ipAddress() {
            AuditEventLog log = new AuditEventLog();
            log.setIpAddress("::1");
            assertEquals("::1", log.getIpAddress());
        }

        @Test
        @DisplayName("status getter/setter")
        void status() {
            AuditEventLog log = new AuditEventLog();
            log.setStatus(AuditStatus.FAILURE);
            assertEquals(AuditStatus.FAILURE, log.getStatus());
        }

        @Test
        @DisplayName("userName getter/setter")
        void userName() {
            AuditEventLog log = new AuditEventLog();
            log.setUserName("Test User");
            assertEquals("Test User", log.getUserName());
        }

        @Test
        @DisplayName("hospitalName getter/setter")
        void hospitalName() {
            AuditEventLog log = new AuditEventLog();
            log.setHospitalName("Test Hospital");
            assertEquals("Test Hospital", log.getHospitalName());
        }

        @Test
        @DisplayName("details getter/setter")
        void details() {
            AuditEventLog log = new AuditEventLog();
            log.setDetails("some detail info");
            assertEquals("some detail info", log.getDetails());
        }

        @Test
        @DisplayName("resourceId getter/setter")
        void resourceId() {
            AuditEventLog log = new AuditEventLog();
            log.setResourceId("abc-123");
            assertEquals("abc-123", log.getResourceId());
        }

        @Test
        @DisplayName("entityType getter/setter")
        void entityType() {
            AuditEventLog log = new AuditEventLog();
            log.setEntityType("Appointment");
            assertEquals("Appointment", log.getEntityType());
        }

        @Test
        @DisplayName("resourceName getter/setter")
        void resourceName() {
            AuditEventLog log = new AuditEventLog();
            log.setResourceName("My Resource");
            assertEquals("My Resource", log.getResourceName());
        }
    }

    // ═══════════════ toString ═══════════════

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString excludes user and assignment (Lombok @ToString(exclude))")
        void toStringExcludesRelations() {
            User user = buildUser(UUID.randomUUID(), "Ada", "Lovelace", "ada@test.com");
            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .eventType(AuditEventType.LOGIN)
                .eventDescription("logged in")
                .ipAddress("1.2.3.4")
                .status(AuditStatus.SUCCESS)
                .build();

            String str = log.toString();
            assertNotNull(str);
            assertTrue(str.contains("AuditEventLog"));
            // Should NOT contain the user or assignment objects' toString
            assertFalse(str.contains("user=User("));
            assertFalse(str.contains("assignment=UserRoleHospitalAssignment("));
        }

        @Test
        @DisplayName("toString contains scalar fields")
        void toStringContainsFields() {
            AuditEventLog log = AuditEventLog.builder()
                .roleName("ADMIN")
                .eventType(AuditEventType.USER_CREATE)
                .eventDescription("desc")
                .ipAddress("10.10.10.10")
                .status(AuditStatus.PENDING)
                .userName("Test")
                .hospitalName("HospX")
                .details("d")
                .resourceId("r1")
                .entityType("E")
                .resourceName("RN")
                .build();

            String str = log.toString();
            assertAll(
                () -> assertTrue(str.contains("ADMIN")),
                () -> assertTrue(str.contains("USER_CREATE")),
                () -> assertTrue(str.contains("desc")),
                () -> assertTrue(str.contains("10.10.10.10")),
                () -> assertTrue(str.contains("PENDING")),
                () -> assertTrue(str.contains("Test")),
                () -> assertTrue(str.contains("HospX")),
                () -> assertTrue(str.contains("r1")),
                () -> assertTrue(str.contains("RN"))
            );
        }
    }

    // ═══════════════ BaseEntity inheritance ═══════════════

    @Nested
    @DisplayName("BaseEntity inheritance")
    class BaseEntityInheritance {

        @Test
        @DisplayName("inherits id from BaseEntity")
        void inheritsId() {
            AuditEventLog log = new AuditEventLog();
            UUID id = UUID.randomUUID();
            log.setId(id);
            assertEquals(id, log.getId());
        }

        @Test
        @DisplayName("inherits createdAt from BaseEntity")
        void inheritsCreatedAt() {
            AuditEventLog log = new AuditEventLog();
            LocalDateTime now = LocalDateTime.now();
            log.setCreatedAt(now);
            assertEquals(now, log.getCreatedAt());
        }

        @Test
        @DisplayName("inherits updatedAt from BaseEntity")
        void inheritsUpdatedAt() {
            AuditEventLog log = new AuditEventLog();
            LocalDateTime now = LocalDateTime.now();
            log.setUpdatedAt(now);
            assertEquals(now, log.getUpdatedAt());
        }
    }

    // ═══════════════ validateAndSnapshot (@PrePersist/@PreUpdate) ═══════════════

    @Nested
    @DisplayName("validateAndSnapshot")
    class ValidateAndSnapshot {

        // ─── assignment == null path ───

        @Test
        @DisplayName("no-op when assignment is null and user is null")
        void noAssignmentNoUser() {
            AuditEventLog log = new AuditEventLog();
            // both null → method should not throw, no side effects
            invokeValidateAndSnapshot(log);
            assertNull(log.getUserName());
            assertNull(log.getHospitalName());
        }

        // ─── assignment != null but hospital == null path ───

        @Test
        @DisplayName("assignment present but hospital null – skips snapshot, snapshots userName from user")
        void assignmentPresentHospitalNull() {
            User user = buildUser(UUID.randomUUID(), "Bob", "Ross", "bob@test.com");
            UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
            assignment.setUser(null);
            assignment.setHospital(null);

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .assignment(assignment)
                .build();

            invokeValidateAndSnapshot(log);

            // Hospital snapshot not touched because assignment.hospital == null
            assertNull(log.getHospitalName());
            // userName should be snapshotted from user
            assertEquals("Bob Ross", log.getUserName());
        }

        // ─── assignment.hospital != null; assignment.user == null → no user-mismatch check ───

        @Test
        @DisplayName("assignment has hospital but no user – snapshots hospitalName")
        void assignmentHospitalNoAssignmentUser() {
            User user = buildUser(UUID.randomUUID(), "Alice", "Wonder", "alice@test.com");
            Hospital hospital = buildHospital("Central Clinic");
            UserRoleHospitalAssignment assignment = buildAssignment(null, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .assignment(assignment)
                .build();

            invokeValidateAndSnapshot(log);

            assertEquals("Central Clinic", log.getHospitalName());
            assertEquals("Alice Wonder", log.getUserName());
        }

        // ─── assignment.user matches audit user → no exception ───

        @Test
        @DisplayName("assignment.user matches audit user – succeeds")
        void assignmentUserMatchesAuditUser() {
            UUID userId = UUID.randomUUID();
            User user = buildUser(userId, "Match", "User", "match@test.com");
            Hospital hospital = buildHospital("Match Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(user, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidateAndSnapshot(log));
            assertEquals("Match Hospital", log.getHospitalName());
            assertEquals("Match User", log.getUserName());
        }

        // ─── assignment.user differs from audit user → IllegalStateException ───

        @Test
        @DisplayName("assignment.user differs from audit user – throws IllegalStateException")
        void assignmentUserMismatch() {
            UUID userId1 = UUID.randomUUID();
            UUID userId2 = UUID.randomUUID();
            User auditUser = buildUser(userId1, "Audit", "User", "audit@test.com");
            User assignmentUser = buildUser(userId2, "Other", "Person", "other@test.com");
            Hospital hospital = buildHospital("Mismatch Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(assignmentUser, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(auditUser)
                .assignment(assignment)
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invokeValidateAndSnapshot(log));
            assertEquals("Audit assignment.user must match audit user", ex.getMessage());
        }

        // ─── hospitalName already set → does NOT overwrite ───

        @Test
        @DisplayName("hospitalName already set – does not overwrite from assignment")
        void hospitalNamePreset() {
            User user = buildUser(UUID.randomUUID(), "Keep", "Name", "keep@test.com");
            Hospital hospital = buildHospital("Assignment Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(user, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .assignment(assignment)
                .hospitalName("Preset Hospital")
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("Preset Hospital", log.getHospitalName());
        }

        // ─── userName already set → does NOT overwrite ───

        @Test
        @DisplayName("userName already set – does not overwrite from user")
        void userNamePreset() {
            User user = buildUser(UUID.randomUUID(), "Jane", "Doe", "jane@test.com");

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .userName("Preset User Name")
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("Preset User Name", log.getUserName());
        }

        // ─── user.firstName null, user.lastName null → falls back to email ───

        @Test
        @DisplayName("user first/last both null – userName falls back to email")
        void userNameFallsBackToEmail() {
            User user = buildUser(UUID.randomUUID(), null, null, "fallback@test.com");

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("fallback@test.com", log.getUserName());
        }

        // ─── user.firstName blank, user.lastName blank → falls back to email ───

        @Test
        @DisplayName("user first/last blank – userName falls back to email")
        void userNameBlankFallsBackToEmail() {
            User user = buildUser(UUID.randomUUID(), "   ", "   ", "blank@test.com");

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("blank@test.com", log.getUserName());
        }

        // ─── user.firstName present, user.lastName null ───

        @Test
        @DisplayName("user firstName present but lastName null – userName uses first only")
        void firstNameOnlyNoLast() {
            User user = buildUser(UUID.randomUUID(), "Solo", null, "solo@test.com");

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("Solo", log.getUserName());
        }

        // ─── user.firstName null, user.lastName present ───

        @Test
        @DisplayName("user firstName null but lastName present – userName uses last only")
        void lastNameOnlyNoFirst() {
            User user = buildUser(UUID.randomUUID(), null, "Lonely", "lonely@test.com");

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("Lonely", log.getUserName());
        }

        // ─── user.firstName empty string, user.lastName empty string → email ───

        @Test
        @DisplayName("user first/last empty strings – userName falls back to email")
        void emptyStringsFallBackToEmail() {
            User user = buildUser(UUID.randomUUID(), "", "", "empty@test.com");

            AuditEventLog log = AuditEventLog.builder()
                .user(user)
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("empty@test.com", log.getUserName());
        }

        // ─── user is null → userName stays null ───

        @Test
        @DisplayName("user is null – userName stays null")
        void userNullUserNameStaysNull() {
            AuditEventLog log = AuditEventLog.builder().build();
            invokeValidateAndSnapshot(log);
            assertNull(log.getUserName());
        }

        // ─── assignment not null, hospital not null, assignment.user null, audit user null ───

        @Test
        @DisplayName("assignment.hospital set, both users null – snapshots hospital, userName stays null")
        void bothUsersNullSnapshotsHospital() {
            Hospital hospital = buildHospital("Orphan Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(null, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(null)
                .assignment(assignment)
                .build();

            invokeValidateAndSnapshot(log);
            assertEquals("Orphan Hospital", log.getHospitalName());
            assertNull(log.getUserName());
        }

        // ─── assignment.user set, audit user null → user-mismatch guard not triggered (user==null check) ───

        @Test
        @DisplayName("assignment.user set but audit user null – no exception (guard short-circuits)")
        void assignmentUserSetAuditUserNull() {
            User assignmentUser = buildUser(UUID.randomUUID(), "X", "Y", "xy@test.com");
            Hospital hospital = buildHospital("Guard Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(assignmentUser, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(null)
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidateAndSnapshot(log));
            assertEquals("Guard Hospital", log.getHospitalName());
            assertNull(log.getUserName());
        }

        // ─── assignment.user null, audit user set → no exception (assignmentUser==null check) ───

        @Test
        @DisplayName("assignment.user null but audit user set – no exception (guard short-circuits)")
        void assignmentUserNullAuditUserSet() {
            User auditUser = buildUser(UUID.randomUUID(), "Audit", "Only", "audit.only@test.com");
            Hospital hospital = buildHospital("One-sided Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(null, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(auditUser)
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidateAndSnapshot(log));
            assertEquals("One-sided Hospital", log.getHospitalName());
            assertEquals("Audit Only", log.getUserName());
        }

        // ─── same user IDs → Objects.equals returns true ───

        @Test
        @DisplayName("assignment.user and audit user share same UUID – no exception")
        void sameUserIdNoException() {
            UUID sharedId = UUID.randomUUID();
            User u1 = buildUser(sharedId, "One", "Same", "same1@test.com");
            User u2 = buildUser(sharedId, "Two", "Same", "same2@test.com");
            Hospital hospital = buildHospital("Same ID Hospital");
            UserRoleHospitalAssignment assignment = buildAssignment(u2, hospital);

            AuditEventLog log = AuditEventLog.builder()
                .user(u1)
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidateAndSnapshot(log));
        }
    }

    // ═══════════════ Equals / HashCode (inherited from BaseEntity) ═══════════════

    @Nested
    @DisplayName("equals and hashCode (via BaseEntity)")
    class EqualsHashCode {

        @Test
        @DisplayName("two logs with same UUID are equal")
        void sameIdEqual() {
            UUID id = UUID.randomUUID();
            AuditEventLog a = new AuditEventLog();
            a.setId(id);
            AuditEventLog b = new AuditEventLog();
            b.setId(id);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("two logs with different UUID are not equal")
        void differentIdNotEqual() {
            AuditEventLog a = new AuditEventLog();
            a.setId(UUID.randomUUID());
            AuditEventLog b = new AuditEventLog();
            b.setId(UUID.randomUUID());
            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("log is not equal to null")
        void notEqualToNull() {
            AuditEventLog a = new AuditEventLog();
            a.setId(UUID.randomUUID());
            assertNotEquals(null, a);
        }

        @Test
        @DisplayName("log is equal to itself")
        void equalToItself() {
            AuditEventLog a = new AuditEventLog();
            a.setId(UUID.randomUUID());
            assertEquals(a, a);
        }
    }

    // ═══════════════ All AuditStatus enum values ═══════════════

    @Nested
    @DisplayName("AuditStatus enum coverage")
    class AuditStatusCoverage {

        @Test
        @DisplayName("can set and get every AuditStatus value")
        void allAuditStatuses() {
            AuditEventLog log = new AuditEventLog();
            for (AuditStatus s : AuditStatus.values()) {
                log.setStatus(s);
                assertEquals(s, log.getStatus());
            }
        }
    }

    // ═══════════════ All AuditEventType enum values ═══════════════

    @Nested
    @DisplayName("AuditEventType enum coverage")
    class AuditEventTypeCoverage {

        @Test
        @DisplayName("can set and get every AuditEventType value")
        void allEventTypes() {
            AuditEventLog log = new AuditEventLog();
            for (AuditEventType t : AuditEventType.values()) {
                log.setEventType(t);
                assertEquals(t, log.getEventType());
            }
        }
    }
}
