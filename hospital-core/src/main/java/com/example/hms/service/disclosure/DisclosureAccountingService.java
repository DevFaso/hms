package com.example.hms.service.disclosure;

import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Who has seen, received or exported one patient's record (Tier 2 item 39).
 *
 * <p>Reads the audit ledger through the {@code patient_id} key V141 added.
 * The predecessor of this service was a single call in
 * {@code PatientPortalServiceImpl}:
 *
 * <pre>{@code
 * getAuditLogsByTarget("PATIENT", patientId.toString(), pageable)
 * }</pre>
 *
 * <p>which matched on the convention {@code entityType='PATIENT'} +
 * {@code resourceId=patientId}. Only three of the six emitters that write
 * patient-related audit rows followed it. Break-the-glass keys on the
 * session id and eligibility checks on the check id, so emergency access to
 * a chart — the single category a patient opens this page to find — and
 * disclosures to an insurance scheme were both absent, with nothing on the
 * page indicating the list was partial.
 *
 * <p>This service does <b>not</b> enforce who may ask. Callers do:
 * {@code PatientPortalController} resolves the patient from the JWT so a
 * patient can only ever ask about themselves, and the staff-facing
 * controller applies its own role check. Keeping authorisation at the edge
 * rather than in here means there is one place to read it.
 */
public interface DisclosureAccountingService {

    /**
     * Full accounting over a window: per-category counts across the whole
     * window plus one page of entries.
     *
     * @param patientId whose record
     * @param from      inclusive lower bound, or null for no lower bound
     * @param to        inclusive upper bound, or null for no upper bound
     * @param pageable  paging for the entry list only; the counts always
     *                  cover the whole window
     */
    DisclosureAccountingDTO getAccounting(UUID patientId, LocalDateTime from,
                                          LocalDateTime to, Pageable pageable);

    /**
     * Just the entries, for the portal's simpler "who viewed my records"
     * list. Same data and same classification as {@link #getAccounting},
     * without the grouped count query.
     */
    Page<AccessLogEntryDTO> getEntries(UUID patientId, LocalDateTime from,
                                       LocalDateTime to, Pageable pageable);
}
