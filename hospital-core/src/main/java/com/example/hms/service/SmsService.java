package com.example.hms.service;

public interface SmsService {
    void send(String phoneNumber, String message);

    /**
     * True only when messages actually reach a phone. The mock implementation
     * logs message bodies instead of sending — callers MUST check this before
     * routing anything secret (one-time credentials) through {@link #send}.
     */
    default boolean deliversRealSms() {
        return false;
    }
}
