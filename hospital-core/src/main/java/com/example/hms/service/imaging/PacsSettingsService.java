package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.imaging.dicom.PacsConnection;
import com.example.hms.imaging.dicom.PacsConnectionResolver;
import com.example.hms.imaging.dicom.PacsDicomWebGateway;
import com.example.hms.imaging.dicom.PacsUnavailableException;
import com.example.hms.imaging.dicom.PacsUrlPolicy;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsConnectionTestDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsUpdateRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A hospital's PACS connection (V317): read, set, test.
 *
 * <p>The password is write-only (stored AES-GCM by {@code EncryptedStringConverter},
 * never returned, never logged). The DICOMweb URL is held to {@link PacsUrlPolicy}
 * when saved, since the backend calls it. Every change is a
 * {@code CONFIGURATION_CHANGED} audit row naming the fields that changed, never
 * their secret values.
 */
@Service
public class PacsSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PacsSettingsService.class);
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notfound";
    static final String URL_REQUIRED = "imaging.pacs.url.required";
    static final String VIEWER_TEMPLATE_INVALID = "imaging.pacs.viewerUrl.invalid";
    private static final String FIELD_BASE_URL = "dicomwebBaseUrl";

    private final HospitalRepository hospitalRepository;
    private final PacsConnectionResolver resolver;
    private final PacsDicomWebGateway gateway;
    private final AuditEventLogService auditEventLogService;

    public PacsSettingsService(HospitalRepository hospitalRepository, PacsConnectionResolver resolver,
                               PacsDicomWebGateway gateway, AuditEventLogService auditEventLogService) {
        this.hospitalRepository = hospitalRepository;
        this.resolver = resolver;
        this.gateway = gateway;
        this.auditEventLogService = auditEventLogService;
    }

    @Transactional(readOnly = true)
    public PacsSettingsDTO get(UUID hospitalId) {
        return toDto(require(hospitalId));
    }

    @Transactional
    public PacsSettingsDTO set(UUID hospitalId, PacsSettingsUpdateRequestDTO body, UUID actorUserId) {
        String baseUrl = PacsUrlPolicy.normalise(body.dicomwebBaseUrl());
        // An absent switch means off; switching on needs the address.
        boolean enabled = Boolean.TRUE.equals(body.enabled());
        if (enabled && baseUrl == null) {
            throw new FieldBusinessException(FIELD_BASE_URL, URL_REQUIRED);
        }
        Hospital hospital = require(hospitalId);
        String template = blankToNull(body.viewerUrlTemplate());
        String username = blankToNull(body.authUsername());
        requireAcceptable(baseUrl, template);

        List<String> changed = applySettings(hospital, enabled, baseUrl, template, username);
        changed.addAll(applyPassword(hospital, body));
        if (changed.isEmpty()) {
            return toDto(hospital);
        }
        Hospital saved = hospitalRepository.save(hospital);
        auditAfterCommit(hospitalId, actorUserId, changed, enabled);
        log.info("[pacs] hospital {} PACS settings changed: {}", hospitalId, changed);
        return toDto(saved == null ? hospital : saved);
    }

    /** The DICOMweb URL against the SSRF fence; the viewer link to http(s). */
    private void requireAcceptable(String baseUrl, String template) {
        if (baseUrl != null) {
            try {
                PacsUrlPolicy.requireCallable(baseUrl, resolver.allowPrivateHosts());
            } catch (PacsUrlPolicy.RejectedException ex) {
                throw urlRefusal(ex);
            }
        }
        if (template != null) {
            requireViewerTemplate(template);
        }
    }

    /** Writes the plain settings; returns the names of those that changed. */
    private static List<String> applySettings(Hospital hospital, boolean enabled, String baseUrl, String template,
                                              String username) {
        List<String> changed = new ArrayList<>();
        if (hospital.isPacsEnabled() != enabled) {
            changed.add("enabled");
        }
        if (!Objects.equals(hospital.getPacsDicomwebBaseUrl(), baseUrl)) {
            changed.add(FIELD_BASE_URL);
        }
        if (!Objects.equals(hospital.getPacsViewerUrlTemplate(), template)) {
            changed.add("viewerUrlTemplate");
        }
        if (!Objects.equals(hospital.getPacsAuthUsername(), username)) {
            changed.add("authUsername");
        }
        hospital.setPacsEnabled(enabled);
        hospital.setPacsDicomwebBaseUrl(baseUrl);
        hospital.setPacsViewerUrlTemplate(template);
        hospital.setPacsAuthUsername(username);
        return changed;
    }

    /** Clears or replaces the write-only password; a blank one keeps the stored one. */
    private static List<String> applyPassword(Hospital hospital, PacsSettingsUpdateRequestDTO body) {
        if (Boolean.TRUE.equals(body.clearPassword())) {
            boolean had = hospital.getPacsAuthPassword() != null;
            hospital.setPacsAuthPassword(null);
            return had ? List.of("authPassword") : List.of();
        }
        if (body.authPassword() != null && !body.authPassword().isBlank()) {
            hospital.setPacsAuthPassword(body.authPassword());
            return List.of("authPassword");
        }
        return List.of();
    }

    private void auditAfterCommit(UUID hospitalId, UUID actorUserId, List<String> changed, boolean enabled) {
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.CONFIGURATION_CHANGED)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .entityType("HOSPITAL_PACS_SETTINGS")
                    .resourceId(hospitalId.toString())
                    .eventDescription("PACS settings changed")
                    .details(Map.of("hospitalId", hospitalId.toString(), "changed", String.join(",", changed),
                        "enabled", String.valueOf(enabled)))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("[pacs] settings audit failed for hospital {}: {}", hospitalId, ex.getClass().getSimpleName());
            }
        });
    }

    /**
     * Tries the connection the viewer would use for this hospital. Never throws
     * for an upstream failure; not transactional, so no database connection is
     * held while the PACS answers.
     */
    public PacsConnectionTestDTO test(UUID hospitalId) {
        PacsConnectionResolver.Resolution resolution = resolver.resolve(require(hospitalId));
        if (!resolution.ready()) {
            return new PacsConnectionTestDTO(resolution.state().name(), null);
        }
        String source = resolution.connection().source().name();
        try {
            gateway.ping(resolution.connection());
            return new PacsConnectionTestDTO("OK", source);
        } catch (PacsUrlPolicy.RejectedException ex) {
            return new PacsConnectionTestDTO(PacsUnavailableException.Reason.URL_REFUSED.name(), source);
        } catch (PacsUnavailableException ex) {
            return new PacsConnectionTestDTO(ex.reason().name(), source);
        }
    }

    /** The refusal in the admin's language; each key literal, so the bundles' guard can follow it. */
    private static BusinessException urlRefusal(PacsUrlPolicy.RejectedException ex) {
        if (PacsUrlPolicy.PRIVATE_HOST.equals(ex.messageKey())) {
            return new FieldBusinessException(FIELD_BASE_URL, "imaging.pacs.url.privateHost");
        }
        if (PacsUrlPolicy.HTTPS_REQUIRED.equals(ex.messageKey())) {
            return new FieldBusinessException(FIELD_BASE_URL, "imaging.pacs.url.httpsOnly");
        }
        return new FieldBusinessException(FIELD_BASE_URL, "imaging.pacs.url.invalid");
    }

    /** The template with sample values must be an http(s) link: the browser opens it, never a script. */
    private static void requireViewerTemplate(String template) {
        String sample = template.replace("{studyInstanceUid}", "1.2.3").replace("{accessionNumber}", "A1");
        try {
            PacsUrlPolicy.requireWebUrl(sample, true);
        } catch (PacsUrlPolicy.RejectedException ex) {
            throw new FieldBusinessException("viewerUrlTemplate", VIEWER_TEMPLATE_INVALID);
        }
    }

    private Hospital require(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, hospitalId));
    }

    private PacsSettingsDTO toDto(Hospital h) {
        PacsConnectionResolver.Resolution resolution = resolver.resolve(h);
        String password = h.getPacsAuthPassword();
        PacsConnection connection = resolution.ready() ? resolution.connection() : null;
        return new PacsSettingsDTO(
            h.getId(),
            h.isPacsEnabled(),
            h.getPacsDicomwebBaseUrl(),
            h.getPacsViewerUrlTemplate(),
            h.getPacsAuthUsername(),
            password != null && !password.isEmpty(),
            resolution.state().name(),
            connection == null ? null : connection.source().name(),
            resolver.platformEnabled(),
            resolver.platformFallbackConfigured(),
            resolver.allowPrivateHosts());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
