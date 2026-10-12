package com.example.hms.security.context;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.utility.MessageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

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
public class ImpersonationLimits implements HandlerInterceptor {

    /** Bundle key of the refusal; the message names what the caller is (a support session), nothing else. */
    public static final String ACCOUNT_CHANGE_REFUSED = "auth.impersonation.accountChange";

    /** The caller's own sign-in and recovery details. */
    public static final List<String> ACCOUNT_PATHS = List.of("/auth/me/**", "/auth/credentials/**");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        boolean read = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
            || HttpMethod.OPTIONS.matches(method);
        if (!read && ImpersonationContextHolder.isImpersonating()) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(ACCOUNT_CHANGE_REFUSED));
        }
        return true;
    }
}
