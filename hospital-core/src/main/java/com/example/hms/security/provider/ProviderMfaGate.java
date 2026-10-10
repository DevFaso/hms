package com.example.hms.security.provider;

import com.example.hms.exception.MfaEnrollmentRequiredException;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.MessageUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.CLAIM_AMR;

/**
 * MFA for provider users (provider plan AC-13, T4): a user with a live
 * assignment at a PHARMACY or LABORATORY (the confinement rule,
 * {@link ProviderConfinementPolicy#isConfined}) acts there only with a second
 * factor, on both auth paths. It keys on live assignments, not on roles, so it
 * covers PHARMACIST and every LAB_* role at a provider without listing them,
 * and a hospital user (or a verified super-admin) is never asked.
 *
 * <ul>
 *   <li><b>Proof.</b> Keycloak: {@code otp} in the access token's {@code amr}
 *       claim (the realm's AMR mapper, P1-T11). Legacy: {@code otp} in the
 *       {@code amr} claim the issuer stamps on the tokens minted by
 *       {@code POST /auth/mfa/verify} after a verified TOTP or backup code,
 *       and carries through a refresh. Nothing else is proof: a password-only
 *       token, the MFA challenge token, a ws ticket.</li>
 *   <li><b>Legacy challenge.</b> {@code /auth/login} challenges a provider user
 *       ({@link #isProviderUser}) whatever {@code app.mfa.required-roles}
 *       says, so the existing TOTP enrolment and challenge produce the proof.</li>
 *   <li><b>Where.</b> The session bootstrap reports {@code mfaEnrollmentRequired};
 *       the confinement filter answers every request outside sign-in and MFA
 *       enrolment ({@code CommonProviderConfinement.SECOND_FACTOR_EXEMPT_RULES})
 *       403 {@code mfa.enrollment.required} ({@link #refuse}); and an existing
 *       authenticator is replaced only with the factor it guards
 *       ({@code MfaController}), so a password alone cannot swap it.</li>
 * </ul>
 *
 * <p>A plain component reached through {@link ObjectProvider}s, like
 * {@link ProviderConfinementPolicy}: a {@code @WebMvcTest} slice has no such
 * bean, and its absence leaves the filter and the auth controller as they were.
 */
@Slf4j
@Component
public class ProviderMfaGate {

    private final TenantRoleAssignmentAccessor assignmentAccessor;
    private final ObjectProvider<JwtTokenProvider> tokenProviderProvider;
    private final ObjectProvider<LocaleResolver> localeResolverProvider;
    private final ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider;

    public ProviderMfaGate(TenantRoleAssignmentAccessor assignmentAccessor,
                           ObjectProvider<JwtTokenProvider> tokenProviderProvider,
                           @Qualifier("localeResolver") ObjectProvider<LocaleResolver> localeResolverProvider,
                           @Qualifier("handlerExceptionResolver")
                           ObjectProvider<HandlerExceptionResolver> exceptionResolverProvider) {
        this.assignmentAccessor = assignmentAccessor;
        this.tokenProviderProvider = tokenProviderProvider;
        this.localeResolverProvider = localeResolverProvider;
        this.exceptionResolverProvider = exceptionResolverProvider;
    }

    /**
     * The request's authentication carries a second factor: a Keycloak token
     * with {@code otp} in {@code amr}, or a legacy token this issuer minted
     * after a TOTP challenge. Anything else (no authentication, a ws ticket, a
     * partner key, a password-only token) does not.
     */
    public boolean secondFactorPresented(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken keycloak) {
            return JwtTokenProvider.amrHoldsOtp(keycloak.getToken().getClaims().get(CLAIM_AMR));
        }
        if (authentication instanceof UsernamePasswordAuthenticationToken legacy
            && legacy.getCredentials() instanceof String token) {
            // The legacy filter keeps the bearer as the credentials (JwtTokenHolder).
            JwtTokenProvider tokens = tokenProviderProvider.getIfAvailable();
            return tokens != null && tokens.hasSecondFactor(token);
        }
        return false;
    }

    /** THE gate: a confined (provider) caller whose authentication carries no second factor. */
    public boolean refuses(HospitalContext context, Authentication authentication) {
        return ProviderConfinementPolicy.isConfined(context) && !secondFactorPresented(authentication);
    }

    /**
     * The account holds a live assignment at a provider facility (the
     * confinement rule on its live assignments, a verified super-admin
     * excepted): {@code /auth/login} challenges it for its second factor, and
     * replacing its authenticator needs that factor ({@code MfaController}).
     */
    public boolean isProviderUser(UUID userId, String username) {
        if (userId == null) {
            return false;
        }
        return ProviderConfinementPolicy.isConfined(
            ActingScopeResolver.liveContext(userId, username, assignmentAccessor.findAssignmentsForUser(userId)));
    }

    /**
     * Answer 403 {@code mfa.enrollment.required} in the request's language,
     * through the same resolvers as a controller's refusal. The log names
     * neither the caller nor the path.
     */
    public void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.debug("[MFA-GATE] Provider request without a second factor refused: {}", request.getMethod());
        MfaEnrollmentRequiredException refusal =
            new MfaEnrollmentRequiredException(MessageUtil.resolve(locale(request), MfaEnrollmentRequiredException.CODE));
        HandlerExceptionResolver resolver = exceptionResolverProvider.getIfAvailable();
        ModelAndView answered = null;
        if (resolver != null) {
            try {
                answered = resolver.resolveException(request, response, null, refusal);
            } catch (RuntimeException resolverFailure) {
                log.debug("[MFA-GATE] Resolver failed ({}); answering a bare 403",
                    resolverFailure.getClass().getSimpleName());
            }
        }
        if (answered == null && !response.isCommitted()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    /** The language MVC would answer in (the application's locale resolver), else the request's own. */
    private Locale locale(HttpServletRequest request) {
        LocaleResolver resolver = localeResolverProvider.getIfAvailable();
        if (resolver != null) {
            try {
                return resolver.resolveLocale(request);
            } catch (RuntimeException unresolvable) {
                log.debug("[MFA-GATE] Locale unresolvable ({})", unresolvable.getClass().getSimpleName());
            }
        }
        return request.getLocale();
    }
}
