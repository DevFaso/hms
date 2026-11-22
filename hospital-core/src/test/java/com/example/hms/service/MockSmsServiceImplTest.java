package com.example.hms.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.example.hms.testsupport.RandomIds.withoutRandomIds;
import static org.assertj.core.api.Assertions.assertThat;

/** D2000 class: the no-provider SMS bean says "not sent" and never logs the number or the text. */
class MockSmsServiceImplTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(MockSmsServiceImpl.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void logsNotSentWithAMaskedNumberAndNeverTheBody() {
        MockSmsServiceImpl mock = new MockSmsServiceImpl();

        mock.send("+22670123456", "e-Keneya : votre code de vérification est 482913.");

        assertThat(mock.deliversRealSms()).isFalse();
        assertThat(appender.list).hasSize(1);
        String line = appender.list.get(0).getFormattedMessage();
        assertThat(withoutRandomIds(line)).contains("NOT sent").contains("56")
            .doesNotContain("482913").doesNotContain("70123456").doesNotContain("votre code");
    }
}
