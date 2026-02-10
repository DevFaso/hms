package com.example.hms.service;

public interface TwilioSmsService {
    /**
     * Sends an SMS message using Twilio.
     *
     * @param to the recipient's phone number in E.164 format
     * @param body the message body
     * @return the SID of the sent message
     */
    String sendSms(String to, String body);
    /**
     * Sends an SMS message using Twilio with a default recipient.
     *
     * @param body the message body
     * @return the SID of the sent message
     */
    default String sendSms(String body) {
        // Default recipient can be configured or hardcoded
        String defaultRecipient = "+1234567890"; // Replace with actual default number
        return sendSms(defaultRecipient, body);
    }
    /**
     * Sends an SMS message using Twilio with a default recipient and a default body.
     *
     * @return the SID of the sent message
     */
    default String sendSms() {
        // Default recipient and body can be configured or hardcoded
        String defaultRecipient = "+1234567890"; // Replace with actual default number
        String defaultBody = "Default message body"; // Replace with actual default message
        return sendSms(defaultRecipient, defaultBody);
    }

}
