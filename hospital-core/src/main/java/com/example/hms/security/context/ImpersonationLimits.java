package com.example.hms.security.context;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.utility.MessageUtil;
import lombok.experimental.UtilityClass;

/**
 * What a support session acting as a user may not do: change how that user
 * signs in or recovers the account. A password, username, email,
 * authenticator or recovery contact set while impersonating would let support
 * take the account over after the session ends.
 *
 * <p>The authenticator itself is also refused, with its own audited reason,
 * by {@code MfaService}; this covers the self-service account endpoints.
 */
@UtilityClass
public class ImpersonationLimits {

    /** Bundle key of the refusal; the message names what the caller is (a support session), nothing else. */
    public static final String ACCOUNT_CHANGE_REFUSED = "auth.impersonation.accountChange";

    /** 403 with a readable reason when the request is a support session acting as the user. */
    public static void refuseAccountChange() {
        if (ImpersonationContextHolder.isImpersonating()) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(ACCOUNT_CHANGE_REFUSED));
        }
    }
}
