package com.example.hms.service.integration;

import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically pushes the prior period's aggregate to DHIS2 for every
 * active facility config. Default OFF; manual-trigger is the v0 path.
 *
 * <p>The default cron fires at 02:00 on the first day of every month
 * (UTC) so the prior month's data is settled. Override via
 * {@code dhis2.export.scheduler.cron}.
 */
@Component
@ConditionalOnProperty(name = "dhis2.export.scheduler.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DhisAdxScheduler {

    private final Dhis2FacilityConfigRepository facilityConfigRepository;
    private final DhisAdxExportService exportService;

    @Scheduled(cron = "${dhis2.export.scheduler.cron:0 0 2 1 * *}",
               zone = "${dhis2.export.scheduler.zone:UTC}")
    public void runSweep() {
        log.info("DHIS2 export scheduler tick");
        for (Dhis2FacilityConfig config : facilityConfigRepository.findByActiveTrue()) {
            try {
                pushOne(config);
            } catch (RuntimeException ex) {
                log.warn("DHIS2 export failed for hospital={} : {}",
                    config.getHospital().getId(), ex.getMessage(), ex);
            }
        }
    }

    private void pushOne(Dhis2FacilityConfig config) {
        if (config.getDefaultDatasetUid() == null || config.getDefaultDatasetUid().isBlank()) {
            log.info("DHIS2 facility {} has no default dataset; skipping",
                config.getHospital().getId());
            return;
        }
        final String periodIso = priorPeriodToken(config);
        log.info("DHIS2 scheduler pushing hospital={} dataset={} period={}",
            config.getHospital().getId(), config.getDefaultDatasetUid(), periodIso);
        exportService.triggerImmunizationsExport(
            config.getHospital().getId(),
            config.getDefaultDatasetUid(),
            config.getDefaultPeriodType(),
            periodIso,
            null);
    }

    /** Token for the period that just closed, given today's date. */
    private static String priorPeriodToken(Dhis2FacilityConfig config) {
        final LocalDate today = LocalDate.now();
        return switch (config.getDefaultPeriodType()) {
            case MONTHLY -> YearMonth.from(today).minusMonths(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            case YEARLY -> Integer.toString(today.getYear() - 1);
            case WEEKLY -> config.getDefaultPeriodType().toIsoPeriod(today.minusWeeks(1));
        };
    }
}
