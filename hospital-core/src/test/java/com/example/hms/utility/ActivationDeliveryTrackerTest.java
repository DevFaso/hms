package com.example.hms.utility;

import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tracker is the only channel that carries delivery outcomes across the
 * AFTER_COMMIT boundary back to the registrar's response, so its arm/disarm
 * contract is load-bearing: recording while unarmed must be a no-op (bulk
 * flows on pooled threads), and close() must always disarm (no bleed into
 * the next request on the same thread).
 */
class ActivationDeliveryTrackerTest {

    @AfterEach
    void disarm() {
        ActivationDeliveryTracker.close();
    }

    @Test
    void recordsOnlyWhileArmedAndCloseDisarms() {
        ActivationDeliveryTracker.record(status("EMAIL"));
        assertThat(ActivationDeliveryTracker.close())
            .as("recording while unarmed must collect nothing")
            .isEmpty();

        ActivationDeliveryTracker.open();
        ActivationDeliveryTracker.record(status("EMAIL"));
        ActivationDeliveryTracker.record(status("SMS"));
        assertThat(ActivationDeliveryTracker.close()).hasSize(2);

        ActivationDeliveryTracker.record(status("SMS"));
        assertThat(ActivationDeliveryTracker.close())
            .as("close() must disarm — a later record on the same thread is dropped")
            .isEmpty();
    }

    @Test
    void masksRecipientsWithoutLeakingThem() {
        assertThat(ActivationDeliveryTracker.maskEmail("jdoe@hospital.com"))
            .isEqualTo("j***@hospital.com");
        assertThat(ActivationDeliveryTracker.maskEmail(null)).isNull();
        assertThat(ActivationDeliveryTracker.maskEmail("no-at-sign")).isEqualTo("***");

        assertThat(ActivationDeliveryTracker.maskPhone("+22670123456")).isEqualTo("+226*****56");
        assertThat(ActivationDeliveryTracker.maskPhone("123")).isEqualTo("***");
        assertThat(ActivationDeliveryTracker.maskPhone(null)).isNull();
    }

    private static NotificationDeliveryStatusDTO status(String channel) {
        return NotificationDeliveryStatusDTO.builder()
            .channel(channel)
            .purpose(NotificationDeliveryStatusDTO.PURPOSE_ACTIVATION)
            .outcome(NotificationDeliveryStatusDTO.OUTCOME_SENT)
            .build();
    }
}
