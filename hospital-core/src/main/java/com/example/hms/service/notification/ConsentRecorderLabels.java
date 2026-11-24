package com.example.hms.service.notification;

import com.example.hms.payload.dto.notification.PatientNotificationDeliveryDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * QA r73 D3700: the one place a consent choice's recorder becomes something a reader sees.
 *
 * <p>Choices are stored per patient account, not per hospital, so a refusal recorded by hospital A's desk is read
 * by every hospital the patient is registered at. A staff reader sees the recorder's name only when the recorder
 * is staff of the reader's own hospital; anyone else is shown as {@link Kind#OTHER_FACILITY} with no name.
 * The patient (the account owner) may see the name of whoever recorded their choice. No reader ever receives the
 * recorder's internal account id: the DTOs carry a kind and, at most, a display name.
 *
 * <p>Every consent DTO that shows a recorder is labelled through {@link Labels#apply}; a guard
 * ({@code ConsentRecorderScopeGuardTest}) fails a recorder name set anywhere else.
 */
@Component
@RequiredArgsConstructor
public class ConsentRecorderLabels {

    /** Who recorded a choice, as a reader may know it. */
    public enum Kind {
        /** The patient's own account (the patient, or the patient's own settings page). */
        PATIENT,
        /** A staff member the reader may name (same hospital, or the patient reading their own choices). */
        STAFF,
        /** Staff of another facility: shown without a name. */
        OTHER_FACILITY
    }

    private final UserRepository users;

    /**
     * Labels for a staff reader pinned to {@code hospitalId}: names are read with the hospital-scoped query, so
     * another facility's staff member is never loaded, let alone shown.
     */
    public Labels forStaffViewer(Collection<UUID> recorderIds, UUID hospitalId) {
        List<UUID> ids = distinct(recorderIds);
        Map<UUID, String> names = ids.isEmpty() || hospitalId == null ? Map.of()
            : toNames(users.findStaffActorNamesAtHospital(ids, hospitalId));
        return new Labels(names, Kind.OTHER_FACILITY);
    }

    /** Labels for the account owner reading their own choices: names, never an id. */
    public Labels forAccountOwner(Collection<UUID> recorderIds, UUID ownerId) {
        List<UUID> ids = distinct(recorderIds).stream().filter(id -> !id.equals(ownerId)).toList();
        Map<UUID, String> names = ids.isEmpty() ? Map.of() : toNames(users.findClinicalActorNames(ids));
        return new Labels(names, null);
    }

    private static List<UUID> distinct(Collection<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static Map<UUID, String> toNames(List<UserRepository.ClinicalActorName> actors) {
        return actors.stream().collect(Collectors.toMap(UserRepository.ClinicalActorName::getId,
            actor -> Stream.of(actor.getFirstName(), actor.getLastName())
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" ")),
            (first, ignored) -> first));
    }

    /** What one read may show of each recorder; built only by {@link ConsentRecorderLabels}. */
    public static final class Labels {
        private final Map<UUID, String> visibleNames;
        private final Kind unnamed;

        private Labels(Map<UUID, String> visibleNames, Kind unnamed) {
            this.visibleNames = visibleNames;
            this.unnamed = unnamed;
        }

        /**
         * @param recorderId the stored recorder, null for a legacy (unattributed) choice
         * @param accountId  the patient account the choice belongs to
         */
        Label of(UUID recorderId, UUID accountId) {
            if (recorderId == null) return Label.NONE;
            if (recorderId.equals(accountId)) return new Label(Kind.PATIENT, null);
            if (visibleNames.containsKey(recorderId)) {
                String name = visibleNames.get(recorderId);
                return new Label(Kind.STAFF, name == null || name.isBlank() ? null : name);
            }
            return unnamed == null ? Label.NONE : new Label(unnamed, null);
        }

        public void apply(NotificationPreferenceDTO dto, UUID recorderId, UUID accountId) {
            Label label = of(recorderId, accountId);
            dto.setRecordedByKind(label.kindName());
            dto.setRecordedByName(label.name());
        }

        public void apply(PatientNotificationDeliveryDTO dto, UUID recorderId, UUID accountId) {
            Label label = of(recorderId, accountId);
            dto.setConsentRecordedByKind(label.kindName());
            dto.setConsentRecordedByName(label.name());
        }
    }

    record Label(Kind kind, String name) {
        static final Label NONE = new Label(null, null);

        String kindName() {
            return kind == null ? null : kind.name();
        }
    }
}
