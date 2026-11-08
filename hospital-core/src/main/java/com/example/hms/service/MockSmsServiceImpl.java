package com.example.hms.service;

import com.example.hms.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MockSmsServiceImpl implements SmsService {

    @Override
    public void send(String phoneNumber, String message) {
        // Simulate SMS sending (or later replace with Twilio, etc.)
        log.info("📲 Mock SMS sent to {}: {}", phoneNumber, message);
    }
}
