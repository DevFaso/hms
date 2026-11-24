package com.example.hms.model;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

/** Security challenge only: never clinical content or plaintext codes. Reused per patient/channel. */
@Entity
@Table(name = "patient_recipient_challenges", schema = "security",
    uniqueConstraints = @UniqueConstraint(name = "uq_patient_recipient_channel", columnNames = {"patient_id", "channel"}))
@Getter @Setter @NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PatientRecipientChallenge extends BaseEntity {
    @Column(name = "patient_id", nullable = false) private UUID patientId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private NotificationChannel channel;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT") private String recipient;
    @Column(name = "code_hash", length = 255) private String codeHash;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(nullable = false) private int attempts;
    @Column(name = "requested_by", nullable = false) private UUID requestedBy;
    @Column(name = "last_requested_at") private LocalDateTime lastRequestedAt;
    @Column(name = "window_started_at") private LocalDateTime windowStartedAt;
    @Column(name = "request_count", nullable = false) private int requestCount;
}
