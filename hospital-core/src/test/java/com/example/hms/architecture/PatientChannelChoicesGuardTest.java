package com.example.hms.architecture;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.service.notification.PatientChannelPreferenceService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2204 (class): the patient's notification matrix offered PUSH and three kinds of notice no sender read
 * (32 switches that did nothing). The choices the portal offers ({@code notification-channel-choices.ts}) must be
 * exactly what the senders read: the kinds are the preferences {@code PatientNotificationEvent} names (the desk's
 * list, {@link PatientChannelPreferenceService#DESK_TYPES}), and the channels are the bell, e-mail and SMS, the
 * three a sender checks. A new kind of notice, or a new channel, must be wired to a sender before it is offered.
 */
class PatientChannelChoicesGuardTest {

    private static final Path CHOICES =
        Path.of("..", "hospital-portal", "src", "app", "shared", "notification-channel-choices.ts");

    @Test
    void thePortalOffersExactlyTheKindsTheSendersRead() throws IOException {
        Set<String> offered = array("NOTICE_TYPES");
        Set<String> read = PatientChannelPreferenceService.DESK_TYPES.stream().map(NotificationType::name)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(offered).isEqualTo(read);
    }

    @Test
    void thePortalOffersOnlyChannelsASenderReads() throws IOException {
        // PatientChannelConsent / PatientMessageGate read SMS and EMAIL; allowsInApp and the reminder and
        // result notifiers read IN_APP. Nothing reads PUSH.
        assertThat(array("NOTICE_CHANNELS")).containsExactlyInAnyOrder(
            NotificationChannel.IN_APP.name(), NotificationChannel.EMAIL.name(), NotificationChannel.SMS.name());
        Set<String> desk = PatientChannelPreferenceService.DESK_CHANNELS.stream().map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
        assertThat(array("DESK_NOTICE_CHANNELS")).isEqualTo(desk);
    }

    private static Set<String> array(String name) throws IOException {
        String source = Files.readString(CHOICES, StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("export const " + name + " = \\[([^\\]]*)]").matcher(source);
        assertThat(block.find()).as(name + " in " + CHOICES).isTrue();
        Matcher item = Pattern.compile("'([A-Z_]+)'").matcher(block.group(1));
        Set<String> values = new TreeSet<>();
        while (item.find()) {
            values.add(item.group(1));
        }
        return values;
    }
}
