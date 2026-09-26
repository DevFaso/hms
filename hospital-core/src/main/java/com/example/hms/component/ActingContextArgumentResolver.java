package com.example.hms.component;


import com.example.hms.enums.ActingMode;
import com.example.hms.security.ActingContext;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
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

        // The hospital the caller chose with X-Hospital-Id, AFTER the security
        // filters validated it — never the raw header. Reading the header here
        // let any caller name any hospital to EncounterController and
        // PatientInsuranceController without passing the permitted-scope check
        // in HospitalContextRequestOverrides. Only a header the filter accepted
        // (headerOverridden) counts, so "no header" and "a rejected header"
        // both stay null, exactly as "no header" always did; the token's
        // primary hospital is deliberately NOT surfaced here (that is what
        // pinnedHospitalId() would add), because both consumers treat a null
        // hospital as "the caller named none" and resolve their own default.
        HospitalContext hospitalContext = HospitalContextHolder.getContextOrEmpty();
        UUID hospitalId = hospitalContext.isHeaderOverridden()
            ? hospitalContext.getActiveHospitalId()
            : null;

        String roleCode = req.getHeader("X-Role-Code");

        return new ActingContext(userId, hospitalId, mode, roleCode);
    }
}
