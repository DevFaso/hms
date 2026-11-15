package com.example.hms.service.maternity;

import com.example.hms.model.labor.FetalDeathEvent;
import com.example.hms.repository.FetalDeathEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D1714 (user decision 2026-11-13): the one-time historical reconciliation. Every delivery recorded with
 * {@code liveBirth = false} before V293 gets ONE fetal-death event, from its recorded facts only
 * ({@link FetalDeathEventService#backfillHistorical}): a missing gestation stays missing (classification
 * incomplete), and a single flag never becomes several babies.
 *
 * <p>Idempotent and safe on every start and on several instances at once: a delivery is selected only while it has
 * no event at all, the service checks again in its own transaction, and the database refuses a second live event
 * per baby ({@code uq_fetal_death_live_per_baby}); an instance that loses that race skips the row. Once every
 * historical delivery has its event the query finds nothing, so later starts do nothing.
 *
 * <p>Counts only are logged (never an id or a name). A failure is logged and never blocks startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FetalDeathEventBackfill {

    private final FetalDeathEventRepository fetalDeathEventRepository;
    private final FetalDeathEventService fetalDeathEventService;

    /** What one pass did. */
    public record Counts(int candidates, int created, int classificationIncomplete, int skipped) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runAfterReady() {
        try {
            run();
        } catch (RuntimeException ex) {
            log.error("Fetal-death event backfill failed: {}", ex.getClass().getSimpleName(), ex);
        }
    }

    /** The whole pass, synchronously. */
    public Counts run() {
        List<UUID> candidates = fetalDeathEventRepository.findStillbirthDeliveriesWithoutEvent();
        int created = 0;
        int incomplete = 0;
        int skipped = 0;
        for (UUID deliveryId : candidates) {
            try {
                Optional<FetalDeathEvent> event = fetalDeathEventService.backfillHistorical(deliveryId);
                if (event.isPresent()) {
                    created++;
                    if (event.get().getGestationalAgeWeeks() == null) {
                        incomplete++;
                    }
                } else {
                    skipped++;
                }
            } catch (DataIntegrityViolationException raced) {
                skipped++;
            }
        }
        Counts counts = new Counts(candidates.size(), created, incomplete, skipped);
        if (!candidates.isEmpty()) {
            log.info("Fetal-death event backfill: {} historical stillbirth deliveries, {} event(s) created "
                    + "({} with classification incomplete: no gestation recorded), {} skipped",
                counts.candidates(), counts.created(), counts.classificationIncomplete(), counts.skipped());
        }
        return counts;
    }
}
