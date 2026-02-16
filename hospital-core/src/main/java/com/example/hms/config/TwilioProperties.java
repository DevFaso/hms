package com.example.hms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "twilio")
public class TwilioProperties {
    /** Whether to initialize Twilio at startup */
    private boolean enabled = false;

    /** Account SID */
    private String accountSid;

    /** Auth token */
    private String authToken;

    /** From phone number (E.164) */
    private String fromNumber;

    // getters/setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAccountSid() { return accountSid; }
    public void setAccountSid(String accountSid) { this.accountSid = accountSid; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public String getFromNumber() { return fromNumber; }
    public void setFromNumber(String fromNumber) { this.fromNumber = fromNumber; }
}
