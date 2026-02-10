package com.example.hms.controller;

import com.example.hms.payload.dto.UsernameReminderRequestDTO;
import com.example.hms.service.UsernameReminderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/auth/username")
@RequiredArgsConstructor
public class UsernameReminderController {

    private final UsernameReminderService usernameReminderService;

    @PostMapping("/reminder")
    public ResponseEntity<Void> remind(
        @Valid @RequestBody UsernameReminderRequestDTO dto,
        Locale locale,
        HttpServletRequest request
    ) {
        String ip = clientIp(request);
        usernameReminderService.sendReminder(dto.getIdentifier().trim(), locale, ip);
        return ResponseEntity.noContent().build();
    }

    private static String clientIp(HttpServletRequest request) {
        String h = request.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            int i = h.indexOf(',');
            return i > 0 ? h.substring(0, i).trim() : h.trim();
        }
        h = request.getHeader("X-Real-IP");
        return (h != null && !h.isBlank()) ? h.trim() : request.getRemoteAddr();
    }
}
