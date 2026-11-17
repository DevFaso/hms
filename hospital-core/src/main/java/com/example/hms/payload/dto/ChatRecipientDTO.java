package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * QA D790: one colleague in the chat's new-conversation picker. The id to
 * write to, the name to show and the role to filter on; no username, e-mail,
 * phone, or active/deleted flag, which are the administrators' directory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRecipientDTO {
    private UUID id;
    private String firstName;
    private String lastName;
    private String roleName;
    private String profileType;
}
