package com.example.hms.service.disclosure;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.DisclosureCategory;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.recordaccess.ProxyReadAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * QA D3451 (proxy-read part, r70 → r77): a parent read her daughter's medications, records and imaging, and the
 * daughter's « Qui a consulté mon dossier » listed only the doctor. A read through family access wrote no
 * audit row; these pin the row it writes now and what the patient is told of it.
 */
@DisplayName("QA D3451: a read through family access is on the patient's access list")
class FamilyAccessDisclosureTest {

    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final ProxyReadAudit proxyReadAudit = new ProxyReadAudit(audit);
    private final UUID patientId = UUID.randomUUID();
    private final UUID parentUserId = UUID.randomUUID();

    private AuditEventRequestDTO recorded() {
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        return event.getValue();
    }

    @Test
    @DisplayName("the row is keyed to the patient read, names the reader and the part, and holds no content")
    void theRowOfARead() {
        UUID grantId = UUID.randomUUID();

        proxyReadAudit.record(grantId, ProxyRelationship.PARENT, patientId, parentUserId, "VIEW_MEDICATIONS");

        AuditEventRequestDTO event = recorded();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.PATIENT_ACCESS);
        assertThat(event.getEntityType()).isEqualTo(ProxyReadAudit.ENTITY_TYPE);
        assertThat(event.getPatientId()).isEqualTo(patientId);
        assertThat(event.getUserId()).isEqualTo(parentUserId);
        assertThat(event.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(event.getResourceId()).isEqualTo(grantId.toString());
        assertThat(event.getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                ProxyReadAudit.DETAIL_GRANT, grantId.toString(),
                ProxyReadAudit.DETAIL_SECTION, "MEDICATIONS",
                ProxyReadAudit.DETAIL_RELATIONSHIP, "PARENT"));
    }

    @Test
    @DisplayName("nothing is written without a grant, a patient and a reader")
    void nothingWithoutAGrant() {
        proxyReadAudit.record(null, ProxyRelationship.PARENT, patientId, parentUserId, "VIEW_RECORDS");
        proxyReadAudit.record(UUID.randomUUID(), ProxyRelationship.PARENT, null, parentUserId, "VIEW_RECORDS");
        proxyReadAudit.record(UUID.randomUUID(), ProxyRelationship.PARENT, patientId, null, "VIEW_RECORDS");
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("the patient reads it as family access: outside the care team, never a treatment access")
    void classifiedAsFamilyAccess() {
        assertThat(DisclosureCategory.classify(AuditEventType.PATIENT_ACCESS, ProxyReadAudit.ENTITY_TYPE))
            .isEqualTo(DisclosureCategory.FAMILY_ACCESS);
        assertThat(DisclosureCategory.FAMILY_ACCESS.isExternalDisclosure()).isTrue();
        // The care team's own reads and the insurer's are untouched.
        assertThat(DisclosureCategory.classify(AuditEventType.PATIENT_ACCESS, "PATIENT"))
            .isEqualTo(DisclosureCategory.TREATMENT_ACCESS);
        assertThat(DisclosureCategory.classify(AuditEventType.PATIENT_ACCESS, "EligibilityCheck"))
            .isEqualTo(DisclosureCategory.INSURANCE);
    }

    @Test
    @DisplayName("the entry says how the reader is related and which part they opened")
    void describedFromTheRowsOwnDetails() {
        AuditEventLog row = new AuditEventLog();
        row.setDetails("{\"grantId\":\"" + UUID.randomUUID() + "\",\"section\":\"LAB_RESULTS\",\"relationship\":\"PARENT\"}");
        AccessLogEntryDTO entry = AccessLogEntryDTO.builder().category(DisclosureCategory.FAMILY_ACCESS).build();

        new FamilyAccessDisclosure(new ObjectMapper()).describe(row, entry);

        assertThat(entry.getRecipientRelationship()).isEqualTo("PARENT");
        assertThat(entry.getAccessedSection()).isEqualTo("LAB_RESULTS");
    }

    @Test
    @DisplayName("one opening of the family tab shows each part once; another part is another line")
    void foldedPerPart() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 25, 10, 0);
        List<AccessLogEntryDTO> folded = DisclosureAccountingServiceImpl.foldRepeats(List.of(
            read("MEDICATIONS", now),
            read("MEDICATIONS", now.minusSeconds(20)),
            read("RECORDS", now.minusSeconds(30)),
            read("MEDICATIONS", now.minusHours(2))));

        assertThat(folded).extracting(AccessLogEntryDTO::getAccessedSection)
            .containsExactly("MEDICATIONS", "RECORDS", "MEDICATIONS");
    }

    private static AccessLogEntryDTO read(String section, LocalDateTime when) {
        return AccessLogEntryDTO.builder().category(DisclosureCategory.FAMILY_ACCESS).actor("Mariam Ouédraogo")
            .accessedSection(section).timestamp(when).build();
    }
}
