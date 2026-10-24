package com.example.hms.service.signature;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D627: every per-type picker query parses against the real entity model
 * and runs; with nothing authored there is nothing to sign.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(SignableReportFinder.class)
class SignableReportFinderJpaTest {

    @Autowired
    private SignableReportFinder finder;

    @Test
    void everyQueryRunsAndAStrangerHasNothingToSign() {
        assertThat(finder.find(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }
}
