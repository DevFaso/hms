package com.example.hms.security.context;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.function.Supplier;

/**
 * What a support session acting as a user may not do: change how that user
 * signs in or recovers the account. A password, username, email,
 * authenticator or recovery contact set while impersonating would let support
 * take the account over after the session ends.
 *
 * <p>Ruled on the path, not handler by handler, so a self-service endpoint
 * added later under {@link #ACCOUNT_PATHS} is covered without anyone
 * remembering to: every write there is refused under impersonation; reads
 * stay open. The authenticator itself is also refused, with its own audited
 * reason, by {@code MfaService}.
 */
@Slf4j
public class ImpersonationLimits implements HandlerInterceptor {

    /** Bundle key of the refusal; the message names what the caller is (a support session), nothing else. */
    public static final String ACCOUNT_CHANGE_REFUSED = "auth.impersonation.accountChange";

    /**
     * The caller's own sign-in and recovery details, and the password check
     * (the lock screen's), which would otherwise let a support session test
     * guesses at the user's password with no lockout.
     */
    public static final List<String> ACCOUNT_PATHS =
        List.of("/auth/me/**", "/auth/credentials/**", "/auth/verify-password");

    /** The audit service, when the context has one (a {@code @WebMvcTest} slice may not). */
    private final Supplier<AuditEventLogService> audit;

    public ImpersonationLimits(Supplier<AuditEventLogService> audit) {
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        boolean read = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
            || HttpMethod.OPTIONS.matches(method);
        if (!read && ImpersonationContextHolder.isImpersonating()) {
            audit(method, request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(ACCOUNT_CHANGE_REFUSED));
        }
        return true;
    }

    /**
     * One row naming the target, the attempt and the endpoint's pattern (never
     * the raw path, which can carry ids); the audit service adds the
     * impersonator from the request's impersonation context.
     */
    private void audit(String method, Object pattern) {
        AuditEventLogService service = audit.get();
        if (service == null) {
            return;
        }
        Authentication acting = SecurityContextHolder.getContext().getAuthentication();
        try {
            service.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .status(AuditStatus.FAILURE)
                .userId(acting != null && acting.getPrincipal() instanceof com.example.hms.security.HospitalUserDetails target
                    ? target.getUserId() : null)
                .userName(acting == null ? null : acting.getName())
                .eventDescription("Support session refused: " + method + " " + (pattern instanceof String p ? p : "account details")
                    + " (sign-in or recovery details)")
                .build());
        } catch (RuntimeException ex) {
            log.error("[IMPERSONATION] Failed to audit a refused account change", ex);
        }
    }
}
