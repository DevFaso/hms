package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Applies {@link MinorPortalRights} where a patient acts on their own account: the one place a refusal is audited
 * and worded. Staff acting for a patient at the desk do not pass here: the desk is where a parent or the legal
 * guardian acts for a minor.
 *
 * <p>The audit row is written in a transaction of its own (the audit service's), so the refusal that follows does
 * not undo it. It carries ids, the action and the age band only.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MinorPortalRightsGuard {

    /** The description of every refusal row; the action and band are in the details. */
    public static final String REFUSAL_DESCRIPTION = "Portal action refused by the minors' rights rule";

    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    /** Refuses (403, in the reader's language) an action the patient's age does not allow alone. */
    public void require(Patient patient, MinorPortalRights.Action action) {
        String key = refusalKeyFor(patient, action);
        if (key != null) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(key));
        }
    }

    /**
     * The activation of a patient's own account: refused under 13. Answered as an invalid request, since the
     * activation pages are public and the caller holds no session to be denied.
     */
    public void requireActivatable(Patient patient) {
        String key = refusalKeyFor(patient, MinorPortalRights.Action.ACTIVATE_ACCOUNT);
        if (key != null) {
            throw new BusinessException(key);
        }
    }

    public MinorPortalRights.Rights rightsOf(Patient patient) {
        return MinorPortalRights.rightsOf(patient == null ? null : patient.getDateOfBirth(), LocalDate.now(clock));
    }

    /** A family access given before the patient's 18th birthday, now that the patient is an adult. */
    public boolean lapsedAtMajority(Patient grantor, LocalDateTime grantedAt) {
        return grantor != null
            && MinorPortalRights.lapsedAtMajority(grantor.getDateOfBirth(), grantedAt, LocalDate.now(clock));
    }

    private String refusalKeyFor(Patient patient, MinorPortalRights.Action action) {
        if (patient == null) {
            return null;
        }
        MinorPortalRights.Band band = MinorPortalRights.bandOf(patient.getDateOfBirth(), LocalDate.now(clock));
        String key = MinorPortalRights.refusalKey(band, action);
        if (key != null) {
            auditRefusal(patient, action, band);
        }
        return key;
    }

    private void auditRefusal(Patient patient, MinorPortalRights.Action action, MinorPortalRights.Band band) {
        try {
            UUID actorUserId = patient.getUser() == null ? null : patient.getUser().getId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.MINOR_PORTAL_ACTION_REFUSED)
                .status(AuditStatus.FAILURE)
                .entityType("PATIENT")
                .resourceId(patient.getId() == null ? null : patient.getId().toString())
                .patientId(patient.getId())
                .userId(actorUserId)
                .hospitalId(patient.getHospitalId())
                .eventDescription(REFUSAL_DESCRIPTION)
                .details(Map.of("action", action.name(), "ageBand", band.name()))
                .build());
        } catch (RuntimeException auditFailure) {
            log.warn("Could not audit a refused portal action ({}): {}", action, auditFailure.getClass().getSimpleName());
        }
    }
}
