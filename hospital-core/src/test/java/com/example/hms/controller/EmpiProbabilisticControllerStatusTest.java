package com.example.hms.controller;

import com.example.hms.empi.probabilistic.EmpiProbabilisticMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** QA D987: the portal offers the duplicate-patient page only while the matcher is on. */
class EmpiProbabilisticControllerStatusTest {

    @Test
    @DisplayName("the status answers the matcher's switch, on and off")
    void statusFollowsTheSwitch() {
        EmpiProbabilisticMatcher matcher = mock(EmpiProbabilisticMatcher.class);
        EmpiProbabilisticController controller = new EmpiProbabilisticController(matcher);

        when(matcher.isEnabled()).thenReturn(false);
        assertThat(controller.status().getBody()).isEqualTo(Map.of("enabled", false));

        when(matcher.isEnabled()).thenReturn(true);
        assertThat(controller.status().getBody()).isEqualTo(Map.of("enabled", true));
    }
}
