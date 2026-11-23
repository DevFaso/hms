package com.example.hms.service.disclosure;

import com.example.hms.model.AuditEventLog;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.ProxySensitiveRelease;
import com.example.hms.model.User;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.ProxySensitiveReleaseRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D3451 (r70): what a patient is told of a result a clinician released to their parent or guardian.
 *
 * <p>« Qui a consulté mon dossier » promises where the record was sent, and a minor whose HIV result went to her
 * mother found nothing there. The release writes one audit row ({@code SENSITIVE_RESULT_RELEASED_TO_PROXY}, ids and
 * the category only); this reads the release that row names and adds what the patient is owed: <b>to whom</b> (the
 * holder of the grant, and how they are related), and <b>which category</b> of result. Who released it and when are
 * the row's own actor and time. Never the result's value, never the clinician's reason (it can say what the result
 * is), never the test's name.
 *
 * <p>The release is read by the id the audit row carries, and only when it is a release of the SAME patient: a row
 * that names another patient's release adds nothing. A row whose release cannot be read is still listed, as a
 * release to the family, without a name.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FamilyReleaseDisclosure {

    private final ProxySensitiveReleaseRepository releaseRepository;
    private final PatientProxyRepository proxyRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /** Adds the recipient, the relationship and the result's category to the entry of a release row. */
    public void describe(AuditEventLog event, AccessLogEntryDTO entry) {
        releaseOf(event)
            .filter(release -> Objects.equals(release.getPatientId(), event.getPatientId()))
            .ifPresent(release -> {
                entry.setResultCategory(release.getSensitivityCategory() == null ? null
                    : release.getSensitivityCategory().name());
                userRepository.findById(release.getProxyUserId())
                    .ifPresent(user -> entry.setRecipientName(displayName(user)));
                proxyRepository.findById(release.getProxyGrantId())
                    .map(PatientProxy::getRelationship)
                    .ifPresent(relationship -> entry.setRecipientRelationship(relationship.name()));
            });
    }

    private Optional<ProxySensitiveRelease> releaseOf(AuditEventLog event) {
        String details = event.getDetails();
        if (details == null || details.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode releaseId = objectMapper.readTree(details).get("releaseId");
            if (releaseId == null || !releaseId.isString()) {
                return Optional.empty();
            }
            return releaseRepository.findById(UUID.fromString(releaseId.asString()));
        } catch (JacksonException | IllegalArgumentException unreadable) {
            log.warn("[DISCLOSURE] release row {} names no readable release: {}", event.getId(),
                unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }
}
