package com.example.hms.service.disclosure;

import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.service.recordaccess.ProxyReadAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * QA D3451 (proxy-read part): what a patient is told of a read made through family access. The row's actor is
 * the holder of the grant and its time is the read's; this adds, from the row's own details, how that person is
 * related to the patient and which part of the record they opened. Nothing else: the row holds no content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FamilyAccessDisclosure {

    private final ObjectMapper objectMapper;

    /** Adds the relationship and the section read to the entry of a family-access row. */
    public void describe(AuditEventLog event, AccessLogEntryDTO entry) {
        String details = event.getDetails();
        if (details == null || details.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(details);
            entry.setRecipientRelationship(text(node, ProxyReadAudit.DETAIL_RELATIONSHIP));
            entry.setAccessedSection(text(node, ProxyReadAudit.DETAIL_SECTION));
        } catch (JacksonException unreadable) {
            log.warn("[DISCLOSURE] family-access row {} has unreadable details: {}", event.getId(),
                unreadable.getClass().getSimpleName());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
