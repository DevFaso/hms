package com.example.hms.security;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * QA D2120 review: gives {@link ClinicalActor} (static, so a {@code @PreAuthorize} reaches it
 * with {@code T(...)}) the audit row it writes when a support session acting as a user attempts a
 * clinical act. The audit service adds the impersonator from the request's impersonation context.
 */
@Slf4j
@Component
public class ClinicalActorAuditRegistrar {

    private final ObjectProvider<AuditEventLogService> audit;

    public ClinicalActorAuditRegistrar(ObjectProvider<AuditEventLogService> audit) {
        this.audit = audit;
    }

    @PostConstruct
    void register() {
        ClinicalActor.setImpersonationAudit(this::auditRefusal);
    }

    @PreDestroy
    void unregister() {
        ClinicalActor.setImpersonationAudit(null);
    }

    void auditRefusal(String roles) {
        AuditEventLogService service = audit.getIfAvailable();
        if (service == null) {
            return;
        }
        Authentication acting = SecurityContextHolder.getContext().getAuthentication();
        try {
            service.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .status(AuditStatus.FAILURE)
                .userId(acting != null && acting.getPrincipal() instanceof HospitalUserDetails target
                    ? target.getUserId() : null)
                .userName(acting == null ? null : acting.getName())
                .eventDescription("Support session refused: a clinical act (" + roles + ") during impersonation")
                .build());
        } catch (RuntimeException ex) {
            log.error("[IMPERSONATION] Failed to audit a refused clinical act", ex);
        }
    }
}
