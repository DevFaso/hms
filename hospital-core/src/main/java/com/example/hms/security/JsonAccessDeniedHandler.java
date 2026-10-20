package com.example.hms.security;

import com.example.hms.utility.MessageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * QA D427: a 403 decided by the URL rules in {@code SecurityConfig} (before any
 * controller runs) used to be a bare {@code sendError}, so the portal showed a
 * refusal with no message. It now answers with the same body the
 * {@code GlobalExceptionHandler} gives a method-security refusal: the localized
 * opaque "Access denied" and the {@code ACCESS_DENIED} code. The exception's
 * own message is never sent (it can name rules or resources).
 */
public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

    static final String CODE_ACCESS_DENIED = "ACCESS_DENIED";

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String message = MessageUtil.resolveOrRaw("error.access.denied");
        if (message == null || "error.access.denied".equals(message)) {
            message = "Access denied";
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\""
            + ",\"status\":403,\"error\":\"Forbidden\""
            + ",\"message\":\"" + jsonEscape(message) + "\""
            + ",\"path\":\"" + jsonEscape(request.getRequestURI()) + "\""
            + ",\"code\":\"" + CODE_ACCESS_DENIED + "\"}");
    }

    static String jsonEscape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
