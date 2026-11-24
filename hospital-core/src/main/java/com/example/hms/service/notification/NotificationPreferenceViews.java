package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.model.NotificationPreference;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One provenance presentation for staff and self-service. The recorder is shown only as the reader's
 * {@link ConsentRecorderLabels.Labels} allow (QA r73 D3700): a kind and, at most, a display name; never an id.
 */
public final class NotificationPreferenceViews {
    private NotificationPreferenceViews() { }

    /** A stored choice, labelled for this reader. */
    public static NotificationPreferenceDTO of(NotificationPreference row, UUID accountId,
                                               ConsentRecorderLabels.Labels labels) {
        return of(row.getNotificationType(), row.getChannel(), row, accountId, labels);
    }

    /** One cell of a matrix: on, with no recorder, when nothing is stored. */
    public static NotificationPreferenceDTO of(NotificationType type, NotificationChannel channel,
                                               NotificationPreference row, UUID accountId,
                                               ConsentRecorderLabels.Labels labels) {
        NotificationPreferenceDTO dto = NotificationPreferenceDTO.builder()
            .id(row == null ? null : row.getId())
            .notificationType(type)
            .channel(channel)
            .enabled(row == null || row.isEnabled())
            .recordedAt(row == null ? null : row.getRecordedAt())
            .build();
        labels.apply(dto, row == null ? null : row.getRecordedByUserId(), accountId);
        return dto;
    }

    /**
     * QA r73 D3702: one audit wording for every consent writer (desk and self-service): the kind, the channel and
     * the old and new state. Never a phone number, an address or a message.
     */
    public static String change(NotificationType type, NotificationChannel channel, boolean before, boolean after) {
        return type.name() + "/" + channel.name() + " " + (before ? "on" : "off") + "->" + (after ? "on" : "off");
    }

    /** The recorders to label, for one batched lookup. */
    public static List<UUID> recorders(Collection<NotificationPreference> rows) {
        return rows.stream().filter(Objects::nonNull).map(NotificationPreference::getRecordedByUserId)
            .filter(Objects::nonNull).distinct().toList();
    }
}
