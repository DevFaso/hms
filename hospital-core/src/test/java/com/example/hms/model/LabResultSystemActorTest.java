package com.example.hms.model;

import com.example.hms.enums.ActorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the @PrePersist guard on {@link LabResult} introduced in
 * P1 #2a. Focus is exclusively on the USER vs SYSTEM actor branches:
 * the existing clinical write path must keep its hospital-match
 * invariant, and the new MLLP / external-LIS path must persist with a
 * null assignment when {@code actorType=SYSTEM}.
 */
class LabResultSystemActorTest {

    private Hospital hospital(UUID id) {
        Hospital h = new Hospital();
        h.setId(id);
        return h;
    }

    private LabOrder labOrder(Hospital h) {
        LabOrder o = new LabOrder();
        o.setId(UUID.randomUUID());
        o.setHospital(h);
        return o;
    }

    private UserRoleHospitalAssignment assignment(Hospital h) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setHospital(h);
        return a;
    }

    private void invokeValidate(LabResult r) {
        try {
            Method m = LabResult.class.getDeclaredMethod("validate");
            m.setAccessible(true);
            m.invoke(r);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new RuntimeException(e);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("USER actor — existing clinical write path")
    class UserActor {

        @Test
        @DisplayName("valid assignment matching labOrder.hospital — passes")
        void validUserWritePasses() {
            UUID hospId = UUID.randomUUID();
            Hospital h = hospital(hospId);
            LabResult r = LabResult.builder()
                .labOrder(labOrder(h))
                .assignment(assignment(h))
                .resultValue("4.5")
                .build();

            assertDoesNotThrow(() -> invokeValidate(r));
            assertEquals(ActorType.USER, r.getActorType(), "USER derived from non-null assignment");
            assertNotNull(r.getResultDate(), "resultDate auto-defaulted");
        }

        @Test
        @DisplayName("null assignment under USER actor — throws")
        void userWithoutAssignmentFails() {
            Hospital h = hospital(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(labOrder(h))
                .assignment(null)
                .actorType(ActorType.USER)
                .resultValue("4.5")
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(r));
            assertTrue(ex.getMessage().contains("USER writes"),
                "message should call out USER-actor invariant: " + ex.getMessage());
        }

        @Test
        @DisplayName("assignment.hospital mismatching labOrder.hospital — throws")
        void hospitalMismatchFails() {
            Hospital orderHospital = hospital(UUID.randomUUID());
            Hospital otherHospital = hospital(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(labOrder(orderHospital))
                .assignment(assignment(otherHospital))
                .resultValue("4.5")
                .build();

            assertThrows(IllegalStateException.class, () -> invokeValidate(r));
        }
    }

    @Nested
    @DisplayName("SYSTEM actor — MLLP / external-LIS write path")
    class SystemActor {

        @Test
        @DisplayName("null assignment with explicit SYSTEM actor — passes")
        void systemWithoutAssignmentPasses() {
            Hospital h = hospital(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(labOrder(h))
                .assignment(null)
                .actorType(ActorType.SYSTEM)
                .actorLabel("MLLP:ROCHE_COBAS/LAB_A")
                .resultValue("4.5")
                .build();

            assertDoesNotThrow(() -> invokeValidate(r));
            assertEquals(ActorType.SYSTEM, r.getActorType());
            assertEquals("MLLP:ROCHE_COBAS/LAB_A", r.getActorLabel());
        }

        @Test
        @DisplayName("non-null assignment under SYSTEM actor — throws")
        void systemWithAssignmentFails() {
            Hospital h = hospital(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(labOrder(h))
                .assignment(assignment(h))
                .actorType(ActorType.SYSTEM)
                .resultValue("4.5")
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(r));
            assertTrue(ex.getMessage().contains("SYSTEM"),
                "message should call out the SYSTEM-actor invariant: " + ex.getMessage());
        }

        @Test
        @DisplayName("actorType null + assignment null — derived to SYSTEM, passes")
        void actorTypeDerivedToSystem() {
            Hospital h = hospital(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(labOrder(h))
                .assignment(null)
                .actorType(null)
                .resultValue("4.5")
                .build();

            assertDoesNotThrow(() -> invokeValidate(r));
            assertEquals(ActorType.SYSTEM, r.getActorType());
        }
    }

    @Nested
    @DisplayName("invariants common to both actors")
    class CommonInvariants {

        @Test
        @DisplayName("null labOrder — throws regardless of actor")
        void nullLabOrderFails() {
            LabResult r = LabResult.builder()
                .labOrder(null)
                .actorType(ActorType.SYSTEM)
                .resultValue("4.5")
                .build();

            assertThrows(IllegalStateException.class, () -> invokeValidate(r));
        }

        @Test
        @DisplayName("labOrder.hospital null — throws regardless of actor")
        void nullLabOrderHospitalFails() {
            LabOrder o = new LabOrder();
            o.setId(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(o)
                .actorType(ActorType.SYSTEM)
                .resultValue("4.5")
                .build();

            assertThrows(IllegalStateException.class, () -> invokeValidate(r));
        }

        @Test
        @DisplayName("resultDate null is back-filled to now")
        void resultDateAutoDefaulted() {
            Hospital h = hospital(UUID.randomUUID());
            LabResult r = LabResult.builder()
                .labOrder(labOrder(h))
                .assignment(null)
                .actorType(ActorType.SYSTEM)
                .resultValue("4.5")
                .build();

            assertNull(r.getResultDate());
            invokeValidate(r);
            assertNotNull(r.getResultDate());
        }
    }
}
