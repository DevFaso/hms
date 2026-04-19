package com.example.hms.payload.dto.mfa;

import java.util.List;

public record MfaEnrollmentResponse(
        String secret,
        String otpauthUri,
        String qrCodeDataUrl,
        List<String> backupCodes
) {}
