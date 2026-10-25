package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetRequestDTO {
    private String email;
    /** QA D171: a phone-only account identifies itself by its phone number instead. */
    private String phone;
    /**
     * QA D787: what the holder remembers, whichever it is: an e-mail address,
     * a username or a phone number. Wins over {@code email} and {@code phone}.
     */
    private String identifier;

    /** The identifier this request names: {@code identifier}, else {@code email}, else {@code phone}. */
    public String effectiveIdentifier() {
        for (String candidate : new String[] {identifier, email, phone}) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}
