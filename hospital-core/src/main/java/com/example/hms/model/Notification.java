package com.example.hms.model;

import com.example.hms.model.converter.SealedJsonListConverter;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "notifications", schema = "security")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /**
     * The body in French, as written. Kept for clients that read it as is and
     * for the search filter; readers get it re-rendered from
     * {@link #messageKey} in their own language (QA D665).
     *
     * <p>Scorecard K4 (V387): a body can name a patient, a test and a value, so it is encrypted at rest with
     * the PHI key, like the clinical columns. It cannot be searched in SQL: the search filter reads the
     * recipient's rows and matches them in memory ({@code NotificationServiceImpl}).
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String message;

    /** Message-bundle key of the body; null on rows written before V225 and on free text. */
    @JsonIgnore
    @Column(name = "message_key", length = 150)
    private String messageKey;

    /**
     * Arguments of {@link #messageKey}: strings and numbers as given, and
     * {@code {"key": "..."}} for an argument that is itself a message key.
     * Encrypted at rest with the body (scorecard K4): the arguments are the same names and values.
     */
    @JsonIgnore
    @Convert(converter = SealedJsonListConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message_args", columnDefinition = "jsonb")
    private List<Object> messageArgs;

    /** Categorises the notification (nullable for backward compat with existing rows). */
    @Column(name = "type", length = 40)
    private String type;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(nullable = false)
    private String recipientUsername;

    /**
     * QA D726 (V228): the record the notification is about (a critical lab
     * result), so resolving that record can mark it read. Null on rows that
     * name none.
     */
    @JsonIgnore
    @Column(name = "subject_id")
    private UUID subjectId;

}
