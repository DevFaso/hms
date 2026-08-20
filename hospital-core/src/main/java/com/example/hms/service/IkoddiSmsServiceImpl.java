package com.example.hms.service;

import com.example.hms.service.integration.IkoddiGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Real {@link SmsService} backed by the IKODDI gateway. Registered only when
 * {@code app.ikoddi.enabled=true} and marked {@code @Primary} so it wins over
 * {@link MockSmsServiceImpl} — every existing consumer (prescription dispatch,
 * username reminders, partner notifications, assignment confirmations) starts
 * sending real SMS the moment the flag and credentials are set.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.ikoddi.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class IkoddiSmsServiceImpl implements SmsService {

    private final IkoddiGateway ikoddiGateway;

    @Override
    public void send(String phoneNumber, String message) {
        IkoddiGateway.SmsDispatch dispatch = ikoddiGateway.sendSms(List.of(phoneNumber), message, null);
        log.info("📲 IKODDI SMS dispatched to {} (delivered={})", maskPhone(phoneNumber), dispatch.delivered());
    }

    @Override
    public void sendUsernameReminderSms(String phoneNumber, String username, Locale locale) {
        send(phoneNumber, String.format("Hello! Your username is: %s", username));
    }

    @Override
    public boolean deliversRealSms() {
        return ikoddiGateway.isConfigured();
    }

    /** Display-safe form for logs: keep a leading '+' and the last two digits. */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "••••";
        }
        boolean plus = phone.startsWith("+");
        return (plus ? "+" : "") + "•".repeat(phone.length() - 2 - (plus ? 1 : 0))
            + phone.substring(phone.length() - 2);
    }
}
