package com.example.hms.model.signature;

import com.example.hms.enums.SignatureStatus;
import com.example.hms.enums.SignatureType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DigitalSignatureTest {

    private Staff staff;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFirstName("John");
        user.setLastName("Doe");

        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);

        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
    }

    // ─── Constructors ────────────────────────────────────────────

    @Test
    void noArgConstructor() {
        DigitalSignature ds = new DigitalSignature();
        assertThat(ds.getId()).isNull();
        assertThat(ds.getReportType()).isNull();
        assertThat(ds.getReportId()).isNull();
        assertThat(ds.getSignedBy()).isNull();
        assertThat(ds.getHospital()).isNull();
        assertThat(ds.getSignatureValue()).isNull();
        assertThat(ds.getSignatureDateTime()).isNull();
        // @Builder.Default fields are initialized even with no-arg constructor
        // depending on Lombok version; just check they have acceptable defaults
        assertThat(ds.getStatus()).isIn(null, SignatureStatus.PENDING);
        assertThat(ds.getSignatureHash()).isNull();
        assertThat(ds.getIpAddress()).isNull();
        assertThat(ds.getDeviceInfo()).isNull();
        assertThat(ds.getSignatureNotes()).isNull();
        assertThat(ds.getRevocationReason()).isNull();
        assertThat(ds.getRevokedAt()).isNull();
        assertThat(ds.getRevokedByUserId()).isNull();
        assertThat(ds.getRevokedByDisplay()).isNull();
        assertThat(ds.getExpiresAt()).isNull();
        assertThat(ds.getVerificationCount()).isIn(null, 0);
        assertThat(ds.getLastVerifiedAt()).isNull();
        assertThat(ds.getAuditLog()).isIn(null, new ArrayList<>());
        assertThat(ds.getMetadata()).isNull();
        assertThat(ds.getVersion()).isNull();
    }

    @Test
    void allArgsConstructor() {
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UUID revokedBy = UUID.randomUUID();
        List<SignatureAuditEntry> auditLog = new ArrayList<>();

        DigitalSignature ds = new DigitalSignature(
                SignatureType.DISCHARGE_SUMMARY, reportId, staff, hospital,
                "sig-value", now, SignatureStatus.SIGNED, "hash64",
                "192.168.1.1", "Chrome", "notes", "revocation reason",
                now.minusDays(1), revokedBy, "Admin", now.plusDays(30),
                5, now.minusHours(1), auditLog, "{}", 1L
        );

        assertThat(ds.getReportType()).isEqualTo(SignatureType.DISCHARGE_SUMMARY);
        assertThat(ds.getReportId()).isEqualTo(reportId);
        assertThat(ds.getSignedBy()).isEqualTo(staff);
        assertThat(ds.getHospital()).isEqualTo(hospital);
        assertThat(ds.getSignatureValue()).isEqualTo("sig-value");
        assertThat(ds.getSignatureDateTime()).isEqualTo(now);
        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.SIGNED);
        assertThat(ds.getSignatureHash()).isEqualTo("hash64");
        assertThat(ds.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(ds.getDeviceInfo()).isEqualTo("Chrome");
        assertThat(ds.getSignatureNotes()).isEqualTo("notes");
        assertThat(ds.getRevocationReason()).isEqualTo("revocation reason");
        assertThat(ds.getRevokedAt()).isEqualTo(now.minusDays(1));
        assertThat(ds.getRevokedByUserId()).isEqualTo(revokedBy);
        assertThat(ds.getRevokedByDisplay()).isEqualTo("Admin");
        assertThat(ds.getExpiresAt()).isEqualTo(now.plusDays(30));
        assertThat(ds.getVerificationCount()).isEqualTo(5);
        assertThat(ds.getLastVerifiedAt()).isEqualTo(now.minusHours(1));
        assertThat(ds.getAuditLog()).isSameAs(auditLog);
        assertThat(ds.getMetadata()).isEqualTo("{}");
        assertThat(ds.getVersion()).isEqualTo(1L);
    }

    @Test
    void builder() {
        UUID reportId = UUID.randomUUID();
        DigitalSignature ds = DigitalSignature.builder()
                .reportType(SignatureType.LAB_RESULT)
                .reportId(reportId)
                .signedBy(staff)
                .hospital(hospital)
                .signatureValue("value")
                .signatureDateTime(LocalDateTime.now())
                .signatureHash("abc123")
                .ipAddress("10.0.0.1")
                .deviceInfo("Firefox")
                .signatureNotes("test note")
                .metadata("{\"key\":\"val\"}")
                .build();

        assertThat(ds.getReportType()).isEqualTo(SignatureType.LAB_RESULT);
        assertThat(ds.getReportId()).isEqualTo(reportId);
        // @Builder.Default fields
        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.PENDING);
        assertThat(ds.getVerificationCount()).isZero();
        assertThat(ds.getAuditLog()).isNotNull().isEmpty();
    }

    @Test
    void builderMinimal() {
        DigitalSignature ds = DigitalSignature.builder().build();
        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.PENDING);
        assertThat(ds.getVerificationCount()).isZero();
        assertThat(ds.getAuditLog()).isNotNull().isEmpty();
    }

    // ─── Getters / Setters ───────────────────────────────────────

    @Test
    void setAndGetAllFields() {
        DigitalSignature ds = new DigitalSignature();
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UUID revokedBy = UUID.randomUUID();

        ds.setReportType(SignatureType.OPERATIVE_NOTE);
        ds.setReportId(reportId);
        ds.setSignedBy(staff);
        ds.setHospital(hospital);
        ds.setSignatureValue("sig");
        ds.setSignatureDateTime(now);
        ds.setStatus(SignatureStatus.REVOKED);
        ds.setSignatureHash("hash");
        ds.setIpAddress("1.2.3.4");
        ds.setDeviceInfo("Safari");
        ds.setSignatureNotes("notes");
        ds.setRevocationReason("reason");
        ds.setRevokedAt(now);
        ds.setRevokedByUserId(revokedBy);
        ds.setRevokedByDisplay("Revoker");
        ds.setExpiresAt(now.plusDays(1));
        ds.setVerificationCount(3);
        ds.setLastVerifiedAt(now.minusMinutes(5));
        ds.setAuditLog(new ArrayList<>());
        ds.setMetadata("meta");
        ds.setVersion(2L);

        assertThat(ds.getReportType()).isEqualTo(SignatureType.OPERATIVE_NOTE);
        assertThat(ds.getReportId()).isEqualTo(reportId);
        assertThat(ds.getSignedBy()).isEqualTo(staff);
        assertThat(ds.getHospital()).isEqualTo(hospital);
        assertThat(ds.getSignatureValue()).isEqualTo("sig");
        assertThat(ds.getSignatureDateTime()).isEqualTo(now);
        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.REVOKED);
        assertThat(ds.getSignatureHash()).isEqualTo("hash");
        assertThat(ds.getIpAddress()).isEqualTo("1.2.3.4");
        assertThat(ds.getDeviceInfo()).isEqualTo("Safari");
        assertThat(ds.getSignatureNotes()).isEqualTo("notes");
        assertThat(ds.getRevocationReason()).isEqualTo("reason");
        assertThat(ds.getRevokedAt()).isEqualTo(now);
        assertThat(ds.getRevokedByUserId()).isEqualTo(revokedBy);
        assertThat(ds.getRevokedByDisplay()).isEqualTo("Revoker");
        assertThat(ds.getExpiresAt()).isEqualTo(now.plusDays(1));
        assertThat(ds.getVerificationCount()).isEqualTo(3);
        assertThat(ds.getLastVerifiedAt()).isEqualTo(now.minusMinutes(5));
        assertThat(ds.getAuditLog()).isNotNull();
        assertThat(ds.getMetadata()).isEqualTo("meta");
        assertThat(ds.getVersion()).isEqualTo(2L);
    }

    // ─── toString ────────────────────────────────────────────────

    @Test
    void toStringExcludesSignedByAndHospital() {
        DigitalSignature ds = DigitalSignature.builder()
                .reportType(SignatureType.LAB_RESULT)
                .signedBy(staff)
                .hospital(hospital)
                .signatureHash("h")
                .signatureValue("v")
                .build();
        String s = ds.toString();
        assertThat(s).contains("reportType")
            .contains("signatureHash")
            .doesNotContain("signedBy=")
            .doesNotContain("hospital=");
    }

    // ─── equals / hashCode (callSuper=true, from BaseEntity) ────

    @Test
    void equalsSameId() {
        UUID id = UUID.randomUUID();
        DigitalSignature a = DigitalSignature.builder().build();
        a.setId(id);
        DigitalSignature b = DigitalSignature.builder().build();
        b.setId(id);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void notEqualDifferentId() {
        DigitalSignature a = DigitalSignature.builder().build();
        a.setId(UUID.randomUUID());
        DigitalSignature b = DigitalSignature.builder().build();
        b.setId(UUID.randomUUID());
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualToNull() {
        DigitalSignature ds = DigitalSignature.builder().build();
        assertThat(ds).isNotEqualTo(null);
    }

    @Test
    void notEqualToDifferentType() {
        DigitalSignature ds = DigitalSignature.builder().build();
        assertThat(ds).isNotEqualTo("string");
    }

    // ─── markAsSigned ────────────────────────────────────────────

    @Test
    void markAsSigned() {
        DigitalSignature ds = DigitalSignature.builder()
                .signedBy(staff)
                .build();

        ds.markAsSigned();

        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.SIGNED);
        assertThat(ds.getAuditLog()).hasSize(1);
        SignatureAuditEntry entry = ds.getAuditLog().get(0);
        assertThat(entry.getAction()).isEqualTo("SIGNED");
        assertThat(entry.getPerformedByUserId()).isEqualTo(staff.getId());
        assertThat(entry.getPerformedByDisplay()).isEqualTo("John Doe");
    }

    // ─── revoke ──────────────────────────────────────────────────

    @Test
    void revoke() {
        DigitalSignature ds = DigitalSignature.builder()
                .signedBy(staff)
                .build();
        UUID revoker = UUID.randomUUID();

        ds.revoke(revoker, "Admin User", "Incorrect report", "10.0.0.1", "Chrome");

        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.REVOKED);
        assertThat(ds.getRevokedAt()).isNotNull();
        assertThat(ds.getRevokedByUserId()).isEqualTo(revoker);
        assertThat(ds.getRevokedByDisplay()).isEqualTo("Admin User");
        assertThat(ds.getRevocationReason()).isEqualTo("Incorrect report");
        assertThat(ds.getAuditLog()).hasSize(1);
        assertThat(ds.getAuditLog().get(0).getAction()).isEqualTo("REVOKED");
        assertThat(ds.getAuditLog().get(0).getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(ds.getAuditLog().get(0).getDeviceInfo()).isEqualTo("Chrome");
    }

    // ─── markAsExpired ───────────────────────────────────────────

    @Test
    void markAsExpired() {
        DigitalSignature ds = DigitalSignature.builder()
                .signedBy(staff)
                .build();

        ds.markAsExpired();

        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.EXPIRED);
        assertThat(ds.getAuditLog()).hasSize(1);
        assertThat(ds.getAuditLog().get(0).getAction()).isEqualTo("EXPIRED");
    }

    // ─── recordVerification ──────────────────────────────────────

    @Test
    void recordVerificationSuccess() {
        DigitalSignature ds = DigitalSignature.builder().build();
        UUID verifier = UUID.randomUUID();

        ds.recordVerification(true, verifier, "Verifier", "10.0.0.1", "Edge");

        assertThat(ds.getVerificationCount()).isEqualTo(1);
        assertThat(ds.getLastVerifiedAt()).isNotNull();
        assertThat(ds.getAuditLog()).hasSize(1);
        assertThat(ds.getAuditLog().get(0).getAction()).isEqualTo("VERIFIED_SUCCESS");
        assertThat(ds.getAuditLog().get(0).getDetails()).isEqualTo("Signature verified successfully");
    }

    @Test
    void recordVerificationFailure() {
        DigitalSignature ds = DigitalSignature.builder().build();
        UUID verifier = UUID.randomUUID();

        ds.recordVerification(false, verifier, "Verifier", "10.0.0.1", "Edge");

        assertThat(ds.getVerificationCount()).isEqualTo(1);
        assertThat(ds.getAuditLog()).hasSize(1);
        assertThat(ds.getAuditLog().get(0).getAction()).isEqualTo("VERIFIED_FAILURE");
        assertThat(ds.getAuditLog().get(0).getDetails()).isEqualTo("Signature verification failed");
    }

    @Test
    void recordVerificationIncrementsCount() {
        DigitalSignature ds = DigitalSignature.builder().build();
        UUID verifier = UUID.randomUUID();
        ds.recordVerification(true, verifier, "V", null, null);
        ds.recordVerification(false, verifier, "V", null, null);

        assertThat(ds.getVerificationCount()).isEqualTo(2);
        assertThat(ds.getAuditLog()).hasSize(2);
    }

    // ─── addAuditEntry ───────────────────────────────────────────

    @Test
    void addAuditEntryWhenAuditLogIsNull() {
        DigitalSignature ds = new DigitalSignature();
        ds.setAuditLog(null);

        ds.addAuditEntry("TEST", UUID.randomUUID(), "Tester", "detail", "1.2.3.4", "Chrome");

        assertThat(ds.getAuditLog()).isNotNull().hasSize(1);
        assertThat(ds.getAuditLog().get(0).getAction()).isEqualTo("TEST");
    }

    @Test
    void addAuditEntryAppendsToExistingList() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.addAuditEntry("A1", UUID.randomUUID(), "U1", "d1", null, null);
        ds.addAuditEntry("A2", UUID.randomUUID(), "U2", "d2", null, null);

        assertThat(ds.getAuditLog()).hasSize(2);
    }

    // ─── isValid ─────────────────────────────────────────────────

    @Test
    void isValidWhenSignedAndNotExpired() {
        DigitalSignature ds = DigitalSignature.builder()
                .build();
        ds.setStatus(SignatureStatus.SIGNED);
        ds.setExpiresAt(null);

        assertThat(ds.isValid()).isTrue();
    }

    @Test
    void isValidWhenSignedAndFutureExpiry() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.SIGNED);
        ds.setExpiresAt(LocalDateTime.now().plusDays(1));

        assertThat(ds.isValid()).isTrue();
    }

    @Test
    void isNotValidWhenPending() {
        DigitalSignature ds = DigitalSignature.builder().build();
        assertThat(ds.getStatus()).isEqualTo(SignatureStatus.PENDING);
        assertThat(ds.isValid()).isFalse();
    }

    @Test
    void isNotValidWhenRevoked() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.REVOKED);
        assertThat(ds.isValid()).isFalse();
    }

    @Test
    void isNotValidWhenExpired() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.EXPIRED);
        assertThat(ds.isValid()).isFalse();
    }

    @Test
    void isNotValidWhenSignedButPastExpiry() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.SIGNED);
        ds.setExpiresAt(LocalDateTime.now().minusDays(1));

        assertThat(ds.isValid()).isFalse();
    }

    // ─── canRevoke ───────────────────────────────────────────────

    @Test
    void canRevokeWhenSigned() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.SIGNED);
        assertThat(ds.canRevoke()).isTrue();
    }

    @Test
    void cannotRevokeWhenPending() {
        DigitalSignature ds = DigitalSignature.builder().build();
        assertThat(ds.canRevoke()).isFalse();
    }

    @Test
    void cannotRevokeWhenRevoked() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.REVOKED);
        assertThat(ds.canRevoke()).isFalse();
    }

    @Test
    void cannotRevokeWhenExpired() {
        DigitalSignature ds = DigitalSignature.builder().build();
        ds.setStatus(SignatureStatus.EXPIRED);
        assertThat(ds.canRevoke()).isFalse();
    }

    // ─── prePersist (private, via reflection) ────────────────────

    @Nested
    class PrePersistTests {

        private void invokePrePersist(DigitalSignature ds) throws Exception {
            Method m = DigitalSignature.class.getDeclaredMethod("prePersist");
            m.setAccessible(true);
            try {
                m.invoke(ds);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new RuntimeException(e.getCause());
            }
        }

        @Test
        void setsSignatureDateTimeWhenNull() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .build();
            ds.setSignatureDateTime(null);

            invokePrePersist(ds);

            assertThat(ds.getSignatureDateTime()).isNotNull();
        }

        @Test
        void doesNotOverrideExistingSignatureDateTime() throws Exception {
            LocalDateTime fixed = LocalDateTime.of(2025, 1, 1, 12, 0);
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .signatureDateTime(fixed)
                    .build();

            invokePrePersist(ds);

            assertThat(ds.getSignatureDateTime()).isEqualTo(fixed);
        }

        @Test
        void marksAsExpiredWhenExpiresAtInPast() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .signatureDateTime(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build();

            invokePrePersist(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.EXPIRED);
        }

        @Test
        void doesNotExpireWhenExpiresAtNull() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .signatureDateTime(LocalDateTime.now())
                    .expiresAt(null)
                    .build();

            invokePrePersist(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.PENDING);
        }

        @Test
        void doesNotExpireWhenExpiresAtInFuture() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .signatureDateTime(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusDays(30))
                    .build();

            invokePrePersist(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.PENDING);
        }
    }

    // ─── preUpdate (private, via reflection) ─────────────────────

    @Nested
    class PreUpdateTests {

        private void invokePreUpdate(DigitalSignature ds) throws Exception {
            Method m = DigitalSignature.class.getDeclaredMethod("preUpdate");
            m.setAccessible(true);
            try {
                m.invoke(ds);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new RuntimeException(e.getCause());
            }
        }

        @Test
        void marksAsExpiredWhenSignedAndPastExpiry() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build();
            ds.setStatus(SignatureStatus.SIGNED);

            invokePreUpdate(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.EXPIRED);
        }

        @Test
        void doesNotExpireWhenNotSigned() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build();
            // status is PENDING (default)

            invokePreUpdate(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.PENDING);
        }

        @Test
        void doesNotExpireWhenExpiresAtNull() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .expiresAt(null)
                    .build();
            ds.setStatus(SignatureStatus.SIGNED);

            invokePreUpdate(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.SIGNED);
        }

        @Test
        void doesNotExpireWhenExpiresAtInFuture() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .expiresAt(LocalDateTime.now().plusDays(30))
                    .build();
            ds.setStatus(SignatureStatus.SIGNED);

            invokePreUpdate(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.SIGNED);
        }

        @Test
        void doesNotExpireWhenRevokedEvenIfPastExpiry() throws Exception {
            DigitalSignature ds = DigitalSignature.builder()
                    .signedBy(staff)
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build();
            ds.setStatus(SignatureStatus.REVOKED);

            invokePreUpdate(ds);

            assertThat(ds.getStatus()).isEqualTo(SignatureStatus.REVOKED);
        }
    }

    // ─── BaseEntity id inheritance ───────────────────────────────

    @Test
    void idFromBaseEntity() {
        DigitalSignature ds = new DigitalSignature();
        UUID id = UUID.randomUUID();
        ds.setId(id);
        assertThat(ds.getId()).isEqualTo(id);
    }
}
