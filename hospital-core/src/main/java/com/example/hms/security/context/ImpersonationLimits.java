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
 * What a support session acting as a user may not do.
 *
 * <ul>
 *   <li><b>Change how that user signs in or recovers the account</b> ({@link #ACCOUNT_PATHS}). A password, username,
 *   email, authenticator or recovery contact set while impersonating would let support take the account over after
 *   the session ends. The authenticator itself is also refused, with its own audited reason, by {@code MfaService}.</li>
 *   <li><b>Move money</b> ({@link #MONEY_PATHS}, billing-money plan PR 8 review). An impersonated session acts as the
 *   target user, so a support agent impersonating a cashier and then an accountant would request and approve the same
 *   refund (four-eyes, owner decision D-3, beaten), and a platform super-admin would move money through someone
 *   else's account. Payments, reversals, refunds, invoice and line prices, the price list: every write there is
 *   refused. A copay taken at check-in is refused in the service ({@link #refuseMoneyWrite}), since the check-in
 *   itself is not money.</li>
 *   <li><b>Sign off or revoke a hospital's provisional rules</b> ({@link #RULE_SIGNOFF_PATHS}, V356). The sign-off is
 *   recorded as the admin's act, and « Je suis le signataire » as the admin's own signature: a support session would
 *   put a clinical approval in someone else's name. Refused on the path and again in the service
 *   ({@link #refuseRuleSignoffWrite}).</li>
 * </ul>
 *
 * <p>Ruled on the path, not handler by handler, so an endpoint added later under these paths is covered without
 * anyone remembering to: every write there is refused under impersonation; reads stay open.
 * {@code ImpersonationMoneyWriteGuardTest} fails when a billing, refund or payment write endpoint is not covered.
 */
@Slf4j
public class ImpersonationLimits implements HandlerInterceptor {

    /** Bundle key of the refusal; the message names what the caller is (a support session), nothing else. */
    public static final String ACCOUNT_CHANGE_REFUSED = "auth.impersonation.accountChange";

    /** Bundle key of the money refusal (billing-money plan PR 8 review). */
    public static final String MONEY_WRITE_REFUSED = "billing.impersonation.moneyWrite";

    /** Bundle key of the rule sign-off refusal (V356). */
    public static final String RULE_SIGNOFF_REFUSED = "clinicalRules.impersonation.signoff";

    /** V356: a hospital's sign-off of its provisional rules (recorded and revoked in the admin's name). */
    public static final List<String> RULE_SIGNOFF_PATHS = List.of("/hospitals/*/clinical-rules/**");

    /**
     * The caller's own sign-in and recovery details, and the password check
     * (the lock screen's), which would otherwise let a support session test
     * guesses at the user's password with no lockout.
     */
    public static final List<String> ACCOUNT_PATHS =
        List.of("/auth/me/**", "/auth/credentials/**", "/auth/verify-password");

    /**
     * Every path whose writes move money or set what is owed: invoices and their payments, reversals, adjustments
     * and refunds; invoice lines (a price override, a line removed); the price list; pharmacy payments and counter
     * sales; an insurer's settlement of a pharmacy claim; a patient's own payment declaration; the cash sessions
     * (a till closed, reopened or marked reviewed).
     */
    public static final List<String> MONEY_PATHS = List.of(
        "/billing-invoices/**", "/invoice-items/**", "/billing/price-list/**", "/billing/payers/**",
        "/pharmacy/payments/**",
        "/pharmacy-sales/**", "/me/patient/billing/invoices/*/pay",
        // Review round 2 of #1244: a claim sent or answered by a money desk is cover for a fill, so every claim
        // write is a money write (it was only the settlement).
        "/pharmacy/claims/**",
        "/pharmacy/refunds/**",
        // PR 10 review: a till's close, reopen and review are four-eyes steps on money counted.
        "/cash-sessions/**",
        // Policy rule 6 (V345): a deferral is asked for by one person and approved by another; the hospital's
        // billing policy sets what a visit is charged.
        "/pharmacy/billing-deferrals/**", "/billing/policy/**");

    /** Writes under {@link #MONEY_PATHS} that only read: a search sent as a POST for its body. */
    public static final List<String> MONEY_PATH_READS = List.of("/billing-invoices/search");

    /** What the money rule names in its audit row ("(money)"; the portal reads it). */
    private static final String MONEY = "money";
    /** What the sign-off rule names in its audit row ("(clinical-rule sign-off)"; the portal reads it). */
    private static final String RULE_SIGNOFF = "clinical-rule sign-off";

    /** The audit service, when the context has one (a {@code @WebMvcTest} slice may not). */
    private final Supplier<AuditEventLogService> audit;
    private final String refusalKey;
    private final String what;

    /** The sign-in and recovery rule. */
    public ImpersonationLimits(Supplier<AuditEventLogService> audit) {
        this(audit, ACCOUNT_CHANGE_REFUSED, "sign-in or recovery details");
    }

    private ImpersonationLimits(Supplier<AuditEventLogService> audit, String refusalKey, String what) {
        this.audit = audit;
        this.refusalKey = refusalKey;
        this.what = what;
    }

    /** The money rule, for {@link #MONEY_PATHS}. */
    public static ImpersonationLimits forMoney(Supplier<AuditEventLogService> audit) {
        return new ImpersonationLimits(audit, MONEY_WRITE_REFUSED, MONEY);
    }

    /** The sign-off rule, for {@link #RULE_SIGNOFF_PATHS}. */
    public static ImpersonationLimits forRuleSignoff(Supplier<AuditEventLogService> audit) {
        return new ImpersonationLimits(audit, RULE_SIGNOFF_REFUSED, RULE_SIGNOFF);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        boolean read = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)
            || HttpMethod.OPTIONS.matches(method);
        if (!read && ImpersonationContextHolder.isImpersonating()) {
            Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            audit(audit, method + " " + (pattern instanceof String p ? p : what), what);
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(refusalKey));
        }
        return true;
    }

    /**
     * The money rule where a path cannot carry it: a service step that moves money inside a request that is not
     * money as a whole (the copay taken at check-in), and the money services themselves, a second line behind the
     * paths. Refused, with an audit row, while a support session impersonates the user.
     */
    public static void refuseMoneyWrite(Supplier<AuditEventLogService> audit, String step) {
        if (ImpersonationContextHolder.isImpersonating()) {
            audit(audit, step, MONEY);
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(MONEY_WRITE_REFUSED));
        }
    }

    /**
     * The sign-off rule in the service, behind the path: a sign-off or a revocation is refused, with an audit row,
     * while a support session impersonates the user.
     */
    public static void refuseRuleSignoffWrite(Supplier<AuditEventLogService> audit, String step) {
        if (ImpersonationContextHolder.isImpersonating()) {
            audit(audit, step, RULE_SIGNOFF);
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(RULE_SIGNOFF_REFUSED));
        }
    }

    /** The audit sentence; one fixed shape per rule, each with its portal pattern. */
    private static String describe(String attempt, String what) {
        if (MONEY.equals(what)) {
            return "Support session refused: " + attempt + " (money)";
        }
        if (RULE_SIGNOFF.equals(what)) {
            return "Support session refused: " + attempt + " (clinical-rule sign-off)";
        }
        return "Support session refused: " + attempt + " (sign-in or recovery details)";
    }

    /**
     * One row naming the target, the attempt and the endpoint's pattern or step (never the raw path, which can carry
     * ids); the audit service adds the impersonator from the request's impersonation context.
     */
    private static void audit(Supplier<AuditEventLogService> audit, String attempt, String what) {
        AuditEventLogService service = audit == null ? null : audit.get();
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
                .eventDescription(describe(attempt, what))
                .build());
        } catch (RuntimeException ex) {
            log.error("[IMPERSONATION] Failed to audit a refused {} write", what, ex);
        }
    }
}
