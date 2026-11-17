package com.example.hms.service;

import com.example.hms.service.integration.IkoddiGateway;
import com.example.hms.service.integration.SmsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Real {@link SmsService} backed by the active {@link IkoddiGateway}: Orange by
 * default, IKODDI when {@code app.sms.provider=ikoddi} (see {@link SmsProvider}).
 * Registered only when that provider has its credentials, and marked
 * {@code @Primary} so it wins over {@link MockSmsServiceImpl}: every existing
 * consumer (prescription dispatch, username reminders, partner notifications,
 * assignment confirmations) starts sending real SMS the moment the credentials
 * are set. The class keeps its historical name.
 */
@Service
@Primary
@Conditional(SmsProvider.TransportConfigured.class)
@RequiredArgsConstructor
@Slf4j
public class IkoddiSmsServiceImpl implements SmsService {

    private final IkoddiGateway ikoddiGateway;

    @Override
    public void send(String phoneNumber, String message) {
        IkoddiGateway.SmsDispatch dispatch = ikoddiGateway.sendSms(List.of(phoneNumber), message, null);
        log.info("📲 SMS accepted by the provider for {} (accepted={})", maskPhone(phoneNumber), dispatch.delivered());
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
