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

    /** The caller's own sign-in and recovery details. */
    public static final List<String> ACCOUNT_PATHS = List.of("/auth/me/**", "/auth/credentials/**");

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
            audit(method);
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(ACCOUNT_CHANGE_REFUSED));
        }
        return true;
    }

    /** One row naming the attempt; the audit service adds the impersonator. The path is not recorded. */
    private void audit(String method) {
        AuditEventLogService service = audit.get();
        if (service == null) {
            return;
        }
        Authentication acting = SecurityContextHolder.getContext().getAuthentication();
        try {
            service.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
                .status(AuditStatus.FAILURE)
                .userName(acting == null ? null : acting.getName())
                .eventDescription("Support session refused: " + method + " on the account's sign-in or recovery details")
                .build());
        } catch (RuntimeException ex) {
            log.error("[IMPERSONATION] Failed to audit a refused account change", ex);
        }
    }
}
