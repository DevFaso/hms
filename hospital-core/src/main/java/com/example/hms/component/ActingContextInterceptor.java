package com.example.hms.component;

import com.example.hms.enums.ActingMode;
import com.example.hms.security.ActingContext;
import com.example.hms.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;
import java.util.UUID;

@Component
public class ActingContextInterceptor implements HandlerInterceptor {
    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return true;

        UUID userId = ((CustomUserDetails) auth.getPrincipal()).getUserId();

        String actAs = Optional.ofNullable(req.getHeader("X-Act-As")).orElse("STAFF");
        ActingMode mode = "PATIENT".equalsIgnoreCase(actAs) ? ActingMode.PATIENT : ActingMode.STAFF;

        UUID hospitalId = null;
        if (mode == ActingMode.STAFF) {
            String hid = req.getHeader("X-Hospital-Id");
            if (hid != null && !hid.isBlank()) hospitalId = UUID.fromString(hid);
        }

        String roleCode = req.getHeader("X-Role-Code"); // optional

        req.setAttribute("ACTING_CONTEXT", new ActingContext(userId, hospitalId, mode, roleCode));
        return true;
    }
}
