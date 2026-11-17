package com.example.hms.service.mail;

import com.example.hms.BaseIT;
import com.example.hms.enums.platform.MailOutboxStatus;
import com.example.hms.model.platform.MailOutboxMessage;
import com.example.hms.repository.platform.MailOutboxRepository;
import com.example.hms.service.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox against a real database (V173): what reaches the disk, the
 * claim's exclusivity, the enqueue's own transaction, and one full
 * enqueue-then-sweep round trip through the real {@link EmailService}
 * transport (the test context's mail sender swallows the message).
 */
@DisplayName("Mail outbox (database)")
class MailOutboxIT extends BaseIT {

    private static final String SECRET_BODY = "<p>Your temporary password is Tz-94kQ</p>";
    private static final String ADDRESS = "awa.traore@example.com";

    @Autowired private MailOutboxService outbox;
    @Autowired private MailOutboxRepository repository;
    @Autowired private EmailService emailService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    @AfterEach
    void clean() {
        repository.deleteAllInBatch();
    }

    @Test
    @DisplayName("recipients, subject and body are ciphertext on disk")
    void theRowIsEncryptedAtRest() {
        UUID id = outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Activation", SECRET_BODY);

        Map<String, Object> raw = jdbc.queryForMap(
            "select recipients, subject, html_body from platform.mail_outbox where id = ?", id);
        assertThat(raw.values()).allSatisfy(value ->
            assertThat(String.valueOf(value)).startsWith("gcm1:"));
        assertThat(String.valueOf(raw.get("html_body"))).doesNotContain("Tz-94kQ");
        assertThat(String.valueOf(raw.get("recipients"))).doesNotContain("awa.traore");
        assertThat(repository.findById(id)).get()
            .extracting(MailOutboxMessage::getHtmlBody).isEqualTo(SECRET_BODY);
    }

    @Test
    @DisplayName("of two claims on one due row exactly one wins")
    void theClaimIsExclusive() {
        UUID id = outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Activation", SECRET_BODY);
        LocalDateTime now = LocalDateTime.now(clock).plusSeconds(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Integer first = tx.execute(s -> repository.claim(id, MailOutboxStatus.PENDING, 6, now, now.plusMinutes(5)));
        Integer second = tx.execute(s -> repository.claim(id, MailOutboxStatus.PENDING, 6, now, now.plusMinutes(5)));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("the lease moved the row out of reach").isZero();
        assertThat(repository.findById(id)).get()
            .extracting(MailOutboxMessage::getAttempts).isEqualTo(1);
    }

    @Test
    @DisplayName("an enqueue from an after-commit callback is stored, not lost in the finished transaction")
    void enqueueFromAfterCommitIsStored() {
        AtomicReference<UUID> queued = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    queued.set(outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Restored", SECRET_BODY));
                }
            }));

        assertThat(queued.get()).isNotNull();
        assertThat(repository.findById(queued.get())).isPresent();
    }

    @Test
    @DisplayName("the enqueue commits on its own: a caller rolling back afterwards does not take the mail with it")
    void enqueueIsItsOwnTransaction() {
        AtomicReference<UUID> queued = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            queued.set(outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Code", SECRET_BODY));
            status.setRollbackOnly();
        });

        assertThat(repository.findById(queued.get())).isPresent();
    }

    @Test
    @DisplayName("housekeeping closes a row whose last claim never reported back, and purges old terminal rows")
    void housekeepingClosesAndPurges() {
        UUID stranded = outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Code", SECRET_BODY);
        UUID old = outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Old", SECRET_BODY);
        // stranded: every attempt claimed, lease over, no outcome recorded.
        jdbc.update("update platform.mail_outbox set attempts = 6, next_attempt_at = ? where id = ?",
            LocalDateTime.now(clock).minusMinutes(1), stranded);
        // old: delivered long ago.
        jdbc.update("update platform.mail_outbox set status = 'SENT', html_body = null, subject = null, "
            + "updated_at = ? where id = ?", LocalDateTime.now(clock).minusDays(31), old);
        MailOutboxProperties enabled = new MailOutboxProperties();
        MailOutboxDispatchService sweep = new MailOutboxDispatchService(
            repository, emailService, enabled, clock, transactionManager);

        assertThat(sweep.dispatchPending()).as("nothing is due").isZero();

        MailOutboxMessage closed = repository.findById(stranded).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(MailOutboxStatus.FAILED);
        assertThat(closed.getHtmlBody()).isNull();
        assertThat(closed.getSubject()).isNull();
        assertThat(closed.getLastError()).isEqualTo(MailOutboxDispatchService.EXHAUSTED);
        assertThat(repository.findById(old)).isEmpty();
    }

    @Test
    @DisplayName("a swept row is SENT and keeps only its encrypted recipients")
    void sweepSendsAndDropsTheBody() {
        UUID id = outbox.enqueue(List.of(ADDRESS), List.of(), List.of(), "Activation", SECRET_BODY);
        MailOutboxProperties enabled = new MailOutboxProperties();
        enabled.setEnabled(true);
        MailOutboxDispatchService sweep = new MailOutboxDispatchService(
            repository, emailService, enabled, clock, transactionManager);

        assertThat(sweep.dispatchPending()).isEqualTo(1);

        MailOutboxMessage row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MailOutboxStatus.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getSentAt()).isNotNull();
        Map<String, Object> raw = jdbc.queryForMap(
            "select subject, html_body, recipients from platform.mail_outbox where id = ?", id);
        assertThat(raw.get("subject")).isNull();
        assertThat(raw.get("html_body")).isNull();
        assertThat(String.valueOf(raw.get("recipients"))).startsWith("gcm1:");
        assertThat(sweep.dispatchPending()).as("nothing left to send").isZero();
    }
}
