package com.example.hms.model.platform;

import com.example.hms.enums.platform.MailOutboxStatus;
import com.example.hms.model.BaseEntity;
import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One outbound mail waiting for, or done with, the SMTP server (V173).
 *
 * <p>Mail used to be handed to SMTP on the request thread, from an after-commit
 * callback that still owns the request's JDBC connection: a stalled mail host
 * pinned a Hikari connection and a request thread for the whole send timeout.
 * The request now composes the message and parks it here; a sweep sends it
 * with no transaction open (the webhook-outbox mechanics, V152).
 *
 * <p>The body carries activation links, one-time codes, temporary passwords
 * and names, and the recipients are addresses, so every text column is
 * encrypted at rest. {@code subject} and {@code html_body} are also dropped as
 * soon as the row is terminal (SENT or FAILED): a delivered or abandoned secret
 * has no reason to stay on disk. The recipients stay, encrypted, until the
 * retention purge deletes the row.
 *
 * <p>{@code last_error} is the exception class and nothing else: a transport
 * exception's message can quote the recipient address.
 */
@Entity
@Table(
    name = "mail_outbox",
    schema = "platform",
    indexes = {
        @Index(name = "idx_mail_outbox_dispatch", columnList = "status, next_attempt_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class MailOutboxMessage extends BaseEntity {

    /** Newline-separated To addresses. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipients", nullable = false, columnDefinition = "TEXT")
    private String recipients;

    /** Newline-separated Cc addresses, or null. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "cc_recipients", columnDefinition = "TEXT")
    private String ccRecipients;

    /** Newline-separated Bcc addresses, or null. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "bcc_recipients", columnDefinition = "TEXT")
    private String bccRecipients;

    /** Null once the row is terminal. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    /** Null once the row is terminal. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "html_body", columnDefinition = "TEXT")
    private String htmlBody;

    @ToString.Include
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private MailOutboxStatus status = MailOutboxStatus.PENDING;

    @ToString.Include
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    /**
     * When the sweep may next pick the row up: the retry backoff after a
     * failure, and the claim lease while one instance is sending it, so a
     * crash mid-send is retried once the lease runs out.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** Exception class of the last failure; never its message. */
    @Column(name = "last_error", length = 200)
    private String lastError;
}
