package com.example.hms.service.mail;

import com.example.hms.model.platform.MailOutboxMessage;
import com.example.hms.repository.platform.MailOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Parks a composed mail in {@code platform.mail_outbox} for the dispatch sweep
 * ({@link MailOutboxDispatchService}) to send.
 *
 * <p><b>Why its own transaction ({@code REQUIRES_NEW}), always.</b> Most mail
 * is enqueued from an after-commit callback ({@code TransactionCallbacks}),
 * which runs while the caller's committed transaction is still bound to the
 * thread: a write that JOINED it would participate in a transaction that has
 * already committed and would silently never reach the database. The other
 * callers are either outside any transaction (schedulers, {@code
 * AuthController}) or send inline inside one; for those a joined write would
 * let a failed insert mark the caller's clinical or account write
 * rollback-only (the item-45 lesson), and would change when their mail goes
 * out. A separate transaction keeps every caller's timing exactly what it was
 * when the send happened inline, costs one short INSERT on a second pooled
 * connection (the same trade {@code AuditEventLogServiceImpl.logEvent} already
 * makes on every write), and never holds that connection across the network.
 *
 * <p>A failure propagates: callers already treat a thrown send as "not
 * delivered" and report it, and a queue that swallowed its own insert failure
 * would turn that into a false "queued".
 */
@Service
public class MailOutboxService {

    private static final String SEPARATOR = "\n";

    private final MailOutboxRepository repository;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public MailOutboxService(MailOutboxRepository repository,
                             PlatformTransactionManager transactionManager,
                             Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Queue one mail, due at once.
     *
     * @return the outbox row id
     */
    public UUID enqueue(List<String> to, List<String> cc, List<String> bcc,
                        String subject, String htmlBody) {
        MailOutboxMessage message = MailOutboxMessage.builder()
            .recipients(join(to))
            .ccRecipients(join(cc))
            .bccRecipients(join(bcc))
            .subject(subject)
            .htmlBody(htmlBody)
            .nextAttemptAt(LocalDateTime.now(clock))
            .build();
        return ownTransaction.execute(status -> repository.save(message).getId());
    }

    static String join(List<String> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        return String.join(SEPARATOR, addresses);
    }

    static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return List.of(joined.split(SEPARATOR));
    }
}
