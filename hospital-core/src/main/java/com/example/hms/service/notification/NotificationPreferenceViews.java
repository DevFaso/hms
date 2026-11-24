package com.example.hms.service.notification;

import com.example.hms.model.NotificationPreference;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** One provenance presentation for staff and self-service; batch names, never contact identifiers. */
public final class NotificationPreferenceViews {
    private NotificationPreferenceViews() { }

    public static NotificationPreferenceDTO of(NotificationPreference row) {
        return NotificationPreferenceDTO.builder().id(row.getId()).notificationType(row.getNotificationType())
            .channel(row.getChannel()).enabled(row.isEnabled()).recordedByUserId(row.getRecordedByUserId())
            .recordedAt(row.getRecordedAt()).build();
    }

    public static Map<UUID, String> names(List<UUID> ids, UserRepository users) {
        if (ids.isEmpty()) return Map.of();
        return users.findClinicalActorNames(ids).stream().collect(Collectors.toMap(
            UserRepository.ClinicalActorName::getId,
            actor -> java.util.stream.Stream.of(actor.getFirstName(), actor.getLastName())
                .filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" ")),
            (first, ignored) -> first));
    }

    public static List<NotificationPreferenceDTO> describe(List<NotificationPreferenceDTO> rows, UserRepository users) {
        var ids = rows.stream().map(NotificationPreferenceDTO::getRecordedByUserId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        var names = names(ids, users);
        rows.forEach(row -> row.setRecordedByName(row.getRecordedByUserId() == null ? null : names.get(row.getRecordedByUserId())));
        return rows;
    }
}
