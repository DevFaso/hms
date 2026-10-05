package com.example.hms.service.integration.message;

import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Content retention for {@code clinical.integration_message_event} (V177).
 *
 * <p>The rows are audit evidence and are never deleted. What goes is the
 * stored message body - {@code payload}, which can hold a whole HL7 message,
 * PID and all - once it is past the retention window. An unresolved dead
 * letter keeps its body whatever its age, because replay needs it; see
 * {@code IntegrationMessageEventRepository.findPayloadPurgeCandidateIds} for
 * the exact rule.
 *
 * <p>One call is one bounded batch in one transaction, so a long backlog is
 * worked through in many short transactions instead of one that holds locks
 * over the whole table. The loop lives in
 * {@code IntegrationMessageRetentionScheduler}, outside any transaction - a
 * self-invoked {@code @Transactional} here would be inert.
 */
@Service
@RequiredArgsConstructor
public class IntegrationMessageRetentionService {

    private final IntegrationMessageEventRepository repository;

    /**
     * Erase the content of at most {@code batchSize} eligible rows.
     *
     * @return how many rows were purged; fewer than {@code batchSize} means
     *         the backlog is drained (or another instance took the rest)
     */
    @Transactional
    public int purgeBatch(LocalDateTime cutoff, LocalDateTime purgedAt, int batchSize) {
        List<UUID> ids = repository.findPayloadPurgeCandidateIds(cutoff, PageRequest.of(0, batchSize));
        if (ids.isEmpty()) {
            return 0;
        }
        return repository.purgePayloads(ids, cutoff, purgedAt);
    }
}
