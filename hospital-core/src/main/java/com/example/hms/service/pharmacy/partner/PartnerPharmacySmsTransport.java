package com.example.hms.service.pharmacy.partner;

import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Partner-only transport: a patient or an arbitrary destination cannot be passed here. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PartnerPharmacySmsTransport {
    private final ObjectProvider<SmsService> smsServiceProvider;

    public void send(Pharmacy partner, String message) {
        String phone = partner == null ? null : partner.getPhoneNumber();
        if (phone == null || phone.isBlank()) return;
        SmsService sms = smsServiceProvider.getIfAvailable();
        if (sms == null || !sms.deliversRealSms()) return;
        try {
            sms.send(phone, message);
        } catch (RuntimeException ex) {
            log.warn("Partner SMS failed for {}: {}", SmsPartnerNotificationChannel.maskPhone(phone),
                ex.getClass().getSimpleName());
        }
    }
}
