package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class BootstrapSignupResponse {
    private boolean success;
    private String message;
    private String username;
    private String email;
}
