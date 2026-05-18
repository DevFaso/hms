package com.example.hms.security.tenant.schema;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);
    private static final String AUDIT_ENTITY_TYPE = "HOSPITAL";
    /**
     * Same allowlist as {@link SchemaTenantConnectionProvider} —
     * imported here as a Pattern reference so a future regex tweak in
     * one place doesn't drift from the other. The pattern lives on
     * the provider class for historical reasons; if it ever moves
     * we update both call sites in the same commit.
     */
    static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final EntityManager entityManager;
    private final HospitalRepository hospitalRepository;
    private final AuditEventLogService auditEventLogService;
    private final UserRepository userRepository;
    private final SchemaTenancyProperties schemaTenancyProperties;
    private final boolean provisioningFlagEnabled;
    private final String appRole;

    public TenantProvisioningService(
        EntityManager entityManager,
        HospitalRepository hospitalRepository,
        AuditEventLogService auditEventLogService,
        UserRepository userRepository,
        ObjectProvider<SchemaTenancyProperties> schemaTenancyProvider,
        @Value("${app.tenancy.provisioning.enabled:false}") boolean provisioningFlagEnabled,
        @Value("${app.tenancy.provisioning.app-role:hms_app}") String appRole
    ) {
        this.entityManager = entityManager;
        this.hospitalRepository = hospitalRepository;
        this.auditEventLogService = auditEventLogService;
        this.userRepository = userRepository;
        this.schemaTenancyProperties = schemaTenancyProvider.getIfAvailable();
        this.provisioningFlagEnabled = provisioningFlagEnabled;
        this.appRole = appRole;
    }

  
    public boolean isEnabled() {
        return provisioningFlagEnabled;
    }


    @Transactional
    public String provision(UUID hospitalId, String schemaName) {
        if (hospitalId == null) {
            throw new IllegalArgumentException("hospitalId is required");
        }
        if (schemaName == null || !SAFE_IDENTIFIER.matcher(schemaName).matches()) {
            throw new IllegalArgumentException(
                "schemaName '" + schemaName + "' fails the SAFE_IDENTIFIER allowlist "
                    + "(lowercase alpha-leading, 1-63 chars, alnum + underscore)");
        }
        if (appRole == null || !SAFE_IDENTIFIER.matcher(appRole).matches()) {
            throw new IllegalArgumentException(
                "configured app-role '" + appRole + "' fails the SAFE_IDENTIFIER allowlist");
        }
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new IllegalArgumentException(
                "hospital " + hospitalId + " not found"));

        if (hospital.getIsolationMode() == TenantIsolationMode.SCHEMA) {
            throw new IllegalStateException(
                "hospital " + hospitalId + " is already in SCHEMA isolation mode; "
                    + "provisioning is a pre-flip operation");
        }


        entityManager.createNativeQuery(
            "CREATE SCHEMA IF NOT EXISTS " + schemaName).executeUpdate();
        entityManager.createNativeQuery(
            "GRANT USAGE ON SCHEMA " + schemaName + " TO " + appRole).executeUpdate();
        entityManager.createNativeQuery(
            "ALTER DEFAULT PRIVILEGES IN SCHEMA " + schemaName
                + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + appRole)
            .executeUpdate();

        emitAudit(hospital, schemaName);
        log.info("Provisioned schema {} for hospital {} (app-role={})", schemaName, hospitalId, appRole);
        return schemaName;
    }

    private void emitAudit(Hospital hospital, String schemaName) {
        try {
            User caller = currentUserOrNull();
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.TENANT_SCHEMA_PROVISIONED)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .resourceId(hospital.getId().toString())
                .userId(caller != null ? caller.getId() : null)
                .userName(caller != null ? caller.getUsername() : null)
                .eventDescription(
                    "Schema provisioned for hospital " + hospital.getId()
                        + " (code=" + hospital.getCode()
                        + ", schema=" + schemaName
                        + ", appRole=" + appRole + ")")
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            // Audit must never roll back the clinical / platform write;
            // log + swallow per the phi-encryption-audit skill rule.
            log.warn("audit emission failed for schema-provision of hospital {}: {}",
                hospital.getId(), ex.toString());
        }
    }

    private User currentUserOrNull() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) return null;
        return userRepository.findByUsernameIgnoreCase(username).orElse(null);
    }

    // Field exposed for the controller-side runbook reference (the
    // controller renders this in the response so the operator sees
    // which schema-isolation flag also needs flipping after the
    // provision step).
    public boolean isSchemaIsolationRuntimeFlagOn() {
        return schemaTenancyProperties != null && schemaTenancyProperties.isEnabled();
    }
}
