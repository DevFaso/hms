package com.example.hms.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTest {

    // ───────────── helpers ─────────────

    private User buildUser(UUID id) {
        User u = new User();
        u.setId(id);
        u.setUsername("user-" + id.toString().substring(0, 4));
        return u;
    }

    private UserRoleHospitalAssignment buildAssignment(User user) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setUser(user);
        return a;
    }

    /** Reflectively invokes the private validate() callback. */
    private void invokeValidate(ChatMessage msg) {
        try {
            Method m = ChatMessage.class.getDeclaredMethod("validate");
            m.setAccessible(true);
            m.invoke(msg);
        } catch (Exception e) {
            if (e.getCause() instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new RuntimeException(e);
        }
    }

    // ═══════════════ NoArgsConstructor ═══════════════

    @Nested
    @DisplayName("NoArgsConstructor")
    class NoArgsCtor {

        @Test
        @DisplayName("all fields null/default")
        void defaults() {
            ChatMessage msg = new ChatMessage();
            assertAll(
                () -> assertNull(msg.getSender()),
                () -> assertNull(msg.getRecipient()),
                () -> assertNull(msg.getContent()),
                () -> assertNull(msg.getSentAt()),
                () -> assertFalse(msg.isRead()),
                () -> assertNull(msg.getTimestamp()),
                () -> assertNull(msg.getAssignment()),
                () -> assertNull(msg.getId())
            );
        }
    }

    // ═══════════════ AllArgsConstructor ═══════════════

    @Nested
    @DisplayName("AllArgsConstructor")
    class AllArgsCtor {

        @Test
        @DisplayName("all fields populated")
        void allFields() {
            User sender = buildUser(UUID.randomUUID());
            User recipient = buildUser(UUID.randomUUID());
            LocalDateTime sentAt = LocalDateTime.of(2026, 2, 11, 10, 0);
            LocalDateTime ts = LocalDateTime.of(2026, 2, 11, 10, 1);
            UserRoleHospitalAssignment assignment = buildAssignment(sender);

            ChatMessage msg = new ChatMessage(sender, recipient, "Hello", sentAt, true, ts, assignment);

            assertAll(
                () -> assertSame(sender, msg.getSender()),
                () -> assertSame(recipient, msg.getRecipient()),
                () -> assertEquals("Hello", msg.getContent()),
                () -> assertEquals(sentAt, msg.getSentAt()),
                () -> assertTrue(msg.isRead()),
                () -> assertEquals(ts, msg.getTimestamp()),
                () -> assertSame(assignment, msg.getAssignment())
            );
        }
    }

    // ═══════════════ Builder ═══════════════

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds with all fields")
        void builderAll() {
            User sender = buildUser(UUID.randomUUID());
            User recipient = buildUser(UUID.randomUUID());
            LocalDateTime sentAt = LocalDateTime.now();
            UserRoleHospitalAssignment assignment = buildAssignment(sender);

            ChatMessage msg = ChatMessage.builder()
                .sender(sender)
                .recipient(recipient)
                .content("Hi there")
                .sentAt(sentAt)
                .read(true)
                .Timestamp(LocalDateTime.now())
                .assignment(assignment)
                .build();

            assertAll(
                () -> assertSame(sender, msg.getSender()),
                () -> assertSame(recipient, msg.getRecipient()),
                () -> assertEquals("Hi there", msg.getContent()),
                () -> assertEquals(sentAt, msg.getSentAt()),
                () -> assertTrue(msg.isRead()),
                () -> assertNotNull(msg.getTimestamp()),
                () -> assertSame(assignment, msg.getAssignment())
            );
        }

        @Test
        @DisplayName("@Builder.Default read defaults to false")
        void builderDefaultRead() {
            ChatMessage msg = ChatMessage.builder().build();
            assertFalse(msg.isRead());
        }

        @Test
        @DisplayName("minimal builder – nulls")
        void builderMinimal() {
            ChatMessage msg = ChatMessage.builder().build();
            assertAll(
                () -> assertNull(msg.getSender()),
                () -> assertNull(msg.getRecipient()),
                () -> assertNull(msg.getContent()),
                () -> assertNull(msg.getSentAt()),
                () -> assertNull(msg.getTimestamp()),
                () -> assertNull(msg.getAssignment())
            );
        }
    }

    // ═══════════════ Getters / Setters ═══════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSetters {

        @Test @DisplayName("sender") void sender() {
            ChatMessage msg = new ChatMessage();
            User u = new User();
            msg.setSender(u);
            assertSame(u, msg.getSender());
        }

        @Test @DisplayName("recipient") void recipient() {
            ChatMessage msg = new ChatMessage();
            User u = new User();
            msg.setRecipient(u);
            assertSame(u, msg.getRecipient());
        }

        @Test @DisplayName("content") void content() {
            ChatMessage msg = new ChatMessage();
            msg.setContent("Test content");
            assertEquals("Test content", msg.getContent());
        }

        @Test @DisplayName("sentAt") void sentAt() {
            ChatMessage msg = new ChatMessage();
            LocalDateTime t = LocalDateTime.now();
            msg.setSentAt(t);
            assertEquals(t, msg.getSentAt());
        }

        @Test @DisplayName("read") void read() {
            ChatMessage msg = new ChatMessage();
            msg.setRead(true);
            assertTrue(msg.isRead());
            msg.setRead(false);
            assertFalse(msg.isRead());
        }

        @Test @DisplayName("Timestamp") void timestamp() {
            ChatMessage msg = new ChatMessage();
            LocalDateTime t = LocalDateTime.now();
            msg.setTimestamp(t);
            assertEquals(t, msg.getTimestamp());
        }

        @Test @DisplayName("assignment") void assignment() {
            ChatMessage msg = new ChatMessage();
            UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
            msg.setAssignment(a);
            assertSame(a, msg.getAssignment());
        }
    }

    // ═══════════════ toString ═══════════════

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("excludes sender, recipient, assignment")
        void excludesRelations() {
            ChatMessage msg = ChatMessage.builder()
                .sender(buildUser(UUID.randomUUID()))
                .recipient(buildUser(UUID.randomUUID()))
                .content("msg")
                .assignment(new UserRoleHospitalAssignment())
                .build();

            String str = msg.toString();
            assertNotNull(str);
            assertTrue(str.contains("ChatMessage"));
            assertFalse(str.contains("sender=User("));
            assertFalse(str.contains("recipient=User("));
            assertFalse(str.contains("assignment=UserRoleHospitalAssignment("));
        }

        @Test
        @DisplayName("contains scalar fields")
        void containsScalarFields() {
            ChatMessage msg = ChatMessage.builder()
                .content("hello world")
                .read(true)
                .build();

            String str = msg.toString();
            assertTrue(str.contains("hello world"));
            assertTrue(str.contains("true"));
        }
    }

    // ═══════════════ BaseEntity inheritance ═══════════════

    @Nested
    @DisplayName("BaseEntity inheritance")
    class BaseEntityTests {

        @Test @DisplayName("id") void id() {
            ChatMessage msg = new ChatMessage();
            UUID id = UUID.randomUUID();
            msg.setId(id);
            assertEquals(id, msg.getId());
        }

        @Test @DisplayName("createdAt") void createdAt() {
            ChatMessage msg = new ChatMessage();
            LocalDateTime now = LocalDateTime.now();
            msg.setCreatedAt(now);
            assertEquals(now, msg.getCreatedAt());
        }

        @Test @DisplayName("updatedAt") void updatedAt() {
            ChatMessage msg = new ChatMessage();
            LocalDateTime now = LocalDateTime.now();
            msg.setUpdatedAt(now);
            assertEquals(now, msg.getUpdatedAt());
        }
    }

    // ═══════════════ equals / hashCode ═══════════════

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test @DisplayName("same id → equal") void sameId() {
            UUID id = UUID.randomUUID();
            ChatMessage a = new ChatMessage(); a.setId(id);
            ChatMessage b = new ChatMessage(); b.setId(id);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test @DisplayName("different id → not equal") void diffId() {
            ChatMessage a = new ChatMessage(); a.setId(UUID.randomUUID());
            ChatMessage b = new ChatMessage(); b.setId(UUID.randomUUID());
            assertNotEquals(a, b);
        }

        @Test @DisplayName("not equal to null") void notNull() {
            ChatMessage a = new ChatMessage(); a.setId(UUID.randomUUID());
            assertNotEquals(null, a);
        }
    }

    // ═══════════════ validate() (@PrePersist/@PreUpdate) ═══════════════

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("throws when sender is null")
        void senderNull() {
            ChatMessage msg = ChatMessage.builder()
                .recipient(buildUser(UUID.randomUUID()))
                .assignment(new UserRoleHospitalAssignment())
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(msg));
            assertEquals("sender, recipient and assignment are required", ex.getMessage());
        }

        @Test
        @DisplayName("throws when recipient is null")
        void recipientNull() {
            ChatMessage msg = ChatMessage.builder()
                .sender(buildUser(UUID.randomUUID()))
                .assignment(new UserRoleHospitalAssignment())
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(msg));
            assertEquals("sender, recipient and assignment are required", ex.getMessage());
        }

        @Test
        @DisplayName("throws when assignment is null")
        void assignmentNull() {
            ChatMessage msg = ChatMessage.builder()
                .sender(buildUser(UUID.randomUUID()))
                .recipient(buildUser(UUID.randomUUID()))
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(msg));
            assertEquals("sender, recipient and assignment are required", ex.getMessage());
        }

        @Test
        @DisplayName("throws when all three are null")
        void allNull() {
            ChatMessage msg = ChatMessage.builder().build();
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(msg));
            assertEquals("sender, recipient and assignment are required", ex.getMessage());
        }

        @Test
        @DisplayName("throws when assignment.user id differs from sender id")
        void assignmentUserMismatch() {
            UUID senderId = UUID.randomUUID();
            UUID otherId = UUID.randomUUID();
            User sender = buildUser(senderId);
            User otherUser = buildUser(otherId);
            UserRoleHospitalAssignment assignment = buildAssignment(otherUser);

            ChatMessage msg = ChatMessage.builder()
                .sender(sender)
                .recipient(buildUser(UUID.randomUUID()))
                .assignment(assignment)
                .build();

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> invokeValidate(msg));
            assertEquals("Chat assignment must belong to the sender", ex.getMessage());
        }

        @Test
        @DisplayName("passes when assignment.user matches sender")
        void assignmentUserMatches() {
            UUID senderId = UUID.randomUUID();
            User sender = buildUser(senderId);
            UserRoleHospitalAssignment assignment = buildAssignment(sender);

            ChatMessage msg = ChatMessage.builder()
                .sender(sender)
                .recipient(buildUser(UUID.randomUUID()))
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidate(msg));
        }

        @Test
        @DisplayName("passes when assignment.user is null (guard short-circuits)")
        void assignmentUserNull() {
            User sender = buildUser(UUID.randomUUID());
            UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
            assignment.setUser(null);

            ChatMessage msg = ChatMessage.builder()
                .sender(sender)
                .recipient(buildUser(UUID.randomUUID()))
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidate(msg));
        }

        @Test
        @DisplayName("passes when sender.id is null (guard short-circuits)")
        void senderIdNull() {
            User sender = new User(); // id is null
            User assignmentUser = buildUser(UUID.randomUUID());
            UserRoleHospitalAssignment assignment = buildAssignment(assignmentUser);

            ChatMessage msg = ChatMessage.builder()
                .sender(sender)
                .recipient(buildUser(UUID.randomUUID()))
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidate(msg));
        }

        @Test
        @DisplayName("passes when both assignment.user.id and sender.id are the same UUID")
        void sameUuidNoException() {
            UUID sharedId = UUID.randomUUID();
            User sender = buildUser(sharedId);
            User assignmentUser = buildUser(sharedId);
            UserRoleHospitalAssignment assignment = buildAssignment(assignmentUser);

            ChatMessage msg = ChatMessage.builder()
                .sender(sender)
                .recipient(buildUser(UUID.randomUUID()))
                .assignment(assignment)
                .build();

            assertDoesNotThrow(() -> invokeValidate(msg));
        }
    }
}
