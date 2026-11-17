package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Notification;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.NotificationRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.StompLocaleRegistry;
import com.example.hms.security.provider.ProviderCallerResolver;
import com.example.hms.service.NotificationServiceImpl;
import com.example.hms.service.i18n.NotificationLocalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D665: a notification body was stored once, in French, and a Spanish or
 * English reader got French. The row now keeps its message key and arguments
 * and is rendered in the reader's language on GET /notifications and on the
 * STOMP push; the French text stays in the row.
 */
class NotificationReaderLanguageTest {

    private static final MessageSource MESSAGES = TestMessageSources.bundles();
    private static final String KEY = "lab.critical.notification";
    private static final Object[] ARGS = {"Potassium", "6,8 mmol/L", NotificationLocalizer.key("patient.fallback.generic")};

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationWebSocketController push = mock(NotificationWebSocketController.class);
    private final NotificationLocalizer localizer = new NotificationLocalizer(MESSAGES);
    private final NotificationServiceImpl service = realService();

    @SuppressWarnings("unchecked")
    private NotificationServiceImpl realService() {
        ObjectProvider<ProviderCallerResolver> resolver = mock(ObjectProvider.class);
        return new NotificationServiceImpl(repository, push, mock(NotificationPreferenceRepository.class),
            mock(UserRepository.class), resolver, localizer);
    }

    private Notification written() {
        when(repository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification row = invocation.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        service.createNotification(KEY, ARGS, "dr.awa", "CRITICAL_LAB");
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    private static String expected(Locale locale) {
        return MESSAGES.getMessage(KEY, new Object[]{"Potassium", "6,8 mmol/L",
            MESSAGES.getMessage("patient.fallback.generic", null, locale)}, locale);
    }

    @Test
    @DisplayName("the row keeps the key, the arguments and the French text")
    void theRowKeepsTheKeyAndTheFrenchText() {
        Notification row = written();

        assertThat(row.getMessageKey()).isEqualTo(KEY);
        assertThat(row.getMessageArgs()).hasSize(3);
        assertThat(row.getMessage()).isEqualTo(expected(Locale.FRENCH));
    }

    @ParameterizedTest(name = "GET /notifications with Accept-Language {0}")
    @CsvSource({"fr", "es", "en"})
    @DisplayName("a staff reader gets the body in the language they asked for")
    void staffReadInTheirLanguage(String language) throws Exception {
        Notification row = written();
        when(repository.findByRecipientUsername(eq("dr.awa"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(row), org.springframework.data.domain.PageRequest.of(0, 10), 1));
        MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new NotificationController(service, mock(ControllerAuthUtils.class)))
            .setLocaleResolver(new AcceptHeaderLocaleResolver())
            .build();

        mvc.perform(get("/notifications").header("Accept-Language", language)
                .principal(new UsernamePasswordAuthenticationToken("dr.awa", null, List.of())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].message").value(expected(Locale.of(language))))
            .andExpect(jsonPath("$.content[0].messageKey").doesNotExist());
        // The stored row is untouched: still French.
        assertThat(row.getMessage()).isEqualTo(expected(Locale.FRENCH));
    }

    @Test
    @DisplayName("a row with no key (before V225, or typed text) shows its stored text in every language")
    void aRowWithoutAKeyShowsItsText() {
        Notification legacy = Notification.builder().message("Texte saisi").recipientUsername("x").build();

        assertThat(localizer.localized(legacy, Locale.ENGLISH)).isSameAs(legacy);
        assertThat(localizer.localized(legacy, Locale.ENGLISH).getMessage()).isEqualTo("Texte saisi");
    }

    @Test
    @DisplayName("a key that no longer renders falls back to the stored text")
    void anUnknownKeyKeepsTheStoredText() {
        Notification row = Notification.builder().message("stocké").messageKey("no.such.key")
            .messageArgs(List.of()).build();

        assertThat(localizer.localized(row, Locale.ENGLISH).getMessage()).isEqualTo("stocké");
    }

    @ParameterizedTest(name = "STOMP push to a user who connected with {0}")
    @CsvSource({"fr", "es", "en"})
    @DisplayName("the STOMP push is rendered in the language sent on CONNECT")
    void stompPushInTheReaderLanguage(String language) {
        Notification row = written();
        StompLocaleRegistry registry = new StompLocaleRegistry();
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setNativeHeader("Accept-Language", language + ";q=1, fr;q=0.5");
        connect.setUser(new UsernamePasswordAuthenticationToken("dr.awa", null, List.of()));
        Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
        registry.preSend(frame, mock(MessageChannel.class));
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);

        new NotificationWebSocketController(template, registry, localizer).sendNotification(row);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq("dr.awa"), eq("/topic/notifications"), payload.capture());
        assertThat(((Notification) payload.getValue()).getMessage()).isEqualTo(expected(Locale.of(language)));
    }

    @Test
    @DisplayName("a user who sent no language on CONNECT gets the stored text")
    void stompPushWithoutALanguage() {
        Notification row = written();
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);

        new NotificationWebSocketController(template, new StompLocaleRegistry(), localizer).sendNotification(row);

        verify(template).convertAndSendToUser("dr.awa", "/topic/notifications", row);
    }

    @Test
    @DisplayName("Accept-Language parsing keeps only languages with a bundle")
    void parsesTheFirstSupportedLanguage() {
        assertThat(StompLocaleRegistryAccess.parse("es-ES,es;q=0.9")).contains(Locale.of("es"));
        assertThat(StompLocaleRegistryAccess.parse("de")).isEmpty();
        assertThat(StompLocaleRegistryAccess.parse(null)).isEmpty();
    }

    /** {@code StompLocaleRegistry.parse} is package-private in the security package. */
    private static final class StompLocaleRegistryAccess {
        static Optional<Locale> parse(String header) {
            StompLocaleRegistry registry = new StompLocaleRegistry();
            StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
            if (header != null) {
                connect.setNativeHeader("Accept-Language", header);
            }
            connect.setUser(new UsernamePasswordAuthenticationToken("u", null, List.of()));
            registry.preSend(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()),
                mock(MessageChannel.class));
            return registry.localeOf("u");
        }
    }
}
