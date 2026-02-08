package com.example.hms.component;


import com.example.hms.enums.ActingMode;
import com.example.hms.security.ActingContext;
import com.example.hms.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;
import java.util.UUID;

@Component
public class ActingContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(ActingContext.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = null;
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails cud) {
            userId = cud.getUserId();
        }

        HttpServletRequest req = (HttpServletRequest) webRequest.getNativeRequest();

        // Default to STAFF so staff flows keep working unless the caller says otherwise
        String actAs = Optional.ofNullable(req.getHeader("X-Act-As")).orElse("STAFF");
        ActingMode mode = "PATIENT".equalsIgnoreCase(actAs) ? ActingMode.PATIENT : ActingMode.STAFF;

        UUID hospitalId = null;
        String hid = req.getHeader("X-Hospital-Id");
        if (hid != null && !hid.isBlank()) {
            try { hospitalId = UUID.fromString(hid); } catch (IllegalArgumentException ignored) { }
        }

        String roleCode = req.getHeader("X-Role-Code");

        return new ActingContext(userId, hospitalId, mode, roleCode);
    }
}
