package com.example.hms.service.notification;

import com.example.hms.enums.NotificationChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PatientContactMaskTest {
    @ParameterizedTest
    @CsvSource({"SMS,+22670123456,•••• 3456", "SMS,1234,••••", "EMAIL,patient@example.test,p•••@e•••.test", "EMAIL,a@b.co,a•••@b•••.co", "EMAIL,invalid,••••"})
    void onlyASafeHintIsReturned(NotificationChannel channel, String input, String expected) {
        assertThat(PatientContactMask.of(input, channel)).isEqualTo(expected).doesNotContain(input);
    }
    @Test void absentContactStaysAbsent() {
        for (var channel : NotificationChannel.values()) {
            assertThat(PatientContactMask.of(null, channel)).isNull();
            assertThat(PatientContactMask.of("  ", channel)).isNull();
        }
    }
}
