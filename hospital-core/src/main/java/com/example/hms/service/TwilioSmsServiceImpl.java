package com.example.hms.service;

import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "twilio", name = "enabled", havingValue = "true")
public class TwilioSmsServiceImpl implements TwilioSmsService {

    private final TwilioRestClient client;
    private final com.example.hms.config.TwilioProperties props;

    @Override
    public String sendSms(String to, String body) {
        Message msg = Message.creator(
            new PhoneNumber(to),
            new PhoneNumber(props.getFromNumber()),
            body
        ).create(client);
        log.info("Sent SMS sid={}", msg.getSid());
        return msg.getSid();
    }
}
