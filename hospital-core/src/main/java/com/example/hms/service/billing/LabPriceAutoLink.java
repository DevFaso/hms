package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PriceListItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PriceListAutoLinkReportDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * QA r70 (lab set-up, the D3213 follow-up): a hospital imports its tariff before its lab has any live test, so every
 * lab price line comes in unlinked; once the tests were activated, each line had to be opened and linked by hand,
 * one by one (9 of 11 stayed unlinked). This links them by CODE, the hospital's own key for both lists:
 *
 * <ul>
 *   <li>an active, unlinked price line of category LAB_TEST is linked to the ONE live lab test (of the hospital, or
 *       the platform catalogue) whose code is the line's code, or, failing that, whose code after its prefix is the
 *       line's code after its prefix (« LAB-GLY » and « TWP-GLY »);</li>
 *   <li>no match, two tests that match, or a test already linked to another line: the line is left as it is and
 *       reported, never guessed;</li>
 *   <li>the link is stored as the screen stores it (the test's lineage, {@link PriceListLinks#normalise}) and
 *       audited per line.</li>
 * </ul>
 *
 * <p>It runs when a lab test is activated (the case the QA hit), after a price-list import is applied, and from the
 * price list's « Lier par code » action. It never changes a line that already has a link, a price, a label or a code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabPriceAutoLink {

    private final PriceListItemRepository itemRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final PriceListLinks links;
    private final AuditEventLogService auditEventLogService;

    /** Links what can be linked at {@code hospitalId}; {@code actorUserId} is recorded on each line linked. */
    @Transactional
    public PriceListAutoLinkReportDTO link(UUID hospitalId, UUID actorUserId) {
        PriceListAutoLinkReportDTO report = PriceListAutoLinkReportDTO.builder()
            .linked(new ArrayList<>()).unmatched(new ArrayList<>()).ambiguous(new ArrayList<>())
            .alreadyTaken(new ArrayList<>()).build();
        if (hospitalId == null) {
            return report;
        }
        List<PriceListItem> unlinked = itemRepository.findByHospitalIdOrderByCodeAsc(hospitalId).stream()
            .filter(item -> item.isActive() && item.getLinkType() == null
                && item.getCategory() == PriceListCategory.LAB_TEST && item.getCode() != null)
            .toList();
        if (unlinked.isEmpty()) {
            return report;
        }
        List<LabTestDefinition> live = new ArrayList<>(labTestDefinitionRepository.findByHospital_IdAndActiveTrue(
            hospitalId));
        live.addAll(labTestDefinitionRepository.findByHospitalIsNullAndActiveTrue());
        live.sort(Comparator.comparing(LabTestDefinition::getTestCode, Comparator.nullsLast(Comparator.naturalOrder())));
        for (PriceListItem item : unlinked) {
            linkOne(item, live, hospitalId, actorUserId, report);
        }
        return report;
    }

    private void linkOne(PriceListItem item, List<LabTestDefinition> live, UUID hospitalId, UUID actorUserId,
                         PriceListAutoLinkReportDTO report) {
        Map<String, LabTestDefinition> byLineage = matches(item.getCode(), live, hospitalId);
        if (byLineage.isEmpty()) {
            report.getUnmatched().add(item.getCode());
            return;
        }
        if (byLineage.size() > 1) {
            report.getAmbiguous().add(item.getCode());
            return;
        }
        Map.Entry<String, LabTestDefinition> only = byLineage.entrySet().iterator().next();
        String key = only.getKey();
        boolean taken = itemRepository.findActiveByLink(hospitalId, PriceListLinkType.LAB_TEST_DEFINITION, key)
            .stream().anyMatch(other -> !other.getId().equals(item.getId()));
        if (taken) {
            report.getAlreadyTaken().add(item.getCode());
            return;
        }
        item.setLinkType(PriceListLinkType.LAB_TEST_DEFINITION);
        item.setLinkKey(key);
        item.setUpdatedBy(actorUserId);
        itemRepository.save(item);
        LabTestDefinition test = only.getValue();
        report.getLinked().add(PriceListAutoLinkReportDTO.Linked.builder()
            .itemId(item.getId()).code(item.getCode()).label(item.getLabel())
            .testCode(test.getTestCode()).testName(test.getName()).build());
        audit(actorUserId, hospitalId, item.getId(),
            "Price list item " + item.getId() + " updated");
    }

    /** The live tests whose code matches {@code code}, by lineage (one test revised twice is one match). */
    private Map<String, LabTestDefinition> matches(String code, List<LabTestDefinition> live, UUID hospitalId) {
        Map<String, LabTestDefinition> exact = new LinkedHashMap<>();
        Map<String, LabTestDefinition> stem = new LinkedHashMap<>();
        String wanted = norm(code);
        String wantedStem = stem(wanted);
        for (LabTestDefinition test : live) {
            String testCode = norm(test.getTestCode());
            if (testCode.isEmpty()) {
                continue;
            }
            if (testCode.equals(wanted)) {
                putLineage(exact, test, hospitalId);
            } else if (wantedStem != null && wantedStem.equals(stem(testCode))) {
                putLineage(stem, test, hospitalId);
            }
        }
        return exact.isEmpty() ? stem : exact;
    }

    private void putLineage(Map<String, LabTestDefinition> found, LabTestDefinition test, UUID hospitalId) {
        try {
            String key = links.normalise(hospitalId, PriceListLinkType.LAB_TEST_DEFINITION, test.getId().toString());
            found.putIfAbsent(key, test);
        } catch (RuntimeException notLinkable) {
            // A test the screen would refuse (another hospital's, a retired lineage) is never linked here either.
            log.debug("Lab test {} not linkable: {}", test.getId(), notLinkable.getClass().getSimpleName());
        }
    }

    /** Upper case, every run of characters other than letters and digits one hyphen, no hyphen at either end. */
    static String norm(String code) {
        if (code == null) {
            return "";
        }
        String folded = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-");
        return folded.replaceAll("(^-+)|(-+$)", "");
    }

    /** The code after its first segment (« LAB-GLY » → « GLY »), or null when it has a single segment. */
    static String stem(String normalised) {
        int dash = normalised == null ? -1 : normalised.indexOf('-');
        if (dash <= 0 || dash == normalised.length() - 1) {
            return null;
        }
        return normalised.substring(dash + 1);
    }

    private void audit(UUID userId, UUID hospitalId, UUID itemId, String description) {
        String itemIdText = Objects.toString(itemId);
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.PRICE_LIST_ITEM_UPDATED)
                    .status(AuditStatus.SUCCESS)
                    .userId(userId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(PriceListService.AUDIT_ENTITY)
                    .resourceId(itemIdText)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Price list audit not written for item {}: {}", itemIdText, ex.getClass().getSimpleName());
            }
        });
    }
}
