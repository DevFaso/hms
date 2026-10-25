package com.example.hms.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The language each connected user chose in the app, taken from the
 * {@code Accept-Language} header of their STOMP CONNECT frame, so a pushed
 * notification is rendered in it (QA D665). A push has no HTTP request to read
 * the language from. A user who sent none (or one with no bundle) gets the
 * stored text.
 */
@Component
public class StompLocaleRegistry implements ChannelInterceptor {

    static final String HEADER = "Accept-Language";
    private static final Set<String> SUPPORTED = Set.of("fr", "en", "es");

    private final Map<String, Locale> byUser = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            Principal user = accessor.getUser();
            if (user != null && user.getName() != null) {
                Optional<Locale> locale = parse(accessor.getFirstNativeHeader(HEADER));
                if (locale.isPresent()) {
                    byUser.put(user.getName(), locale.get());
                } else {
                    byUser.remove(user.getName());
                }
            }
        }
        return message;
    }

    public Optional<Locale> localeOf(String username) {
        return username == null ? Optional.empty() : Optional.ofNullable(byUser.get(username));
    }

    static Optional<Locale> parse(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String first = header.split(",")[0].split(";")[0].trim();
        String language = Locale.forLanguageTag(first).getLanguage();
        return SUPPORTED.contains(language) ? Optional.of(Locale.of(language)) : Optional.empty();
    }
}
