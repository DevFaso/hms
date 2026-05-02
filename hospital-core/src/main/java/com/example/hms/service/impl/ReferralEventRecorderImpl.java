package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.ReferralEvent;
import com.example.hms.repository.ReferralEventRepository;
import com.example.hms.service.ReferralEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReferralEventRecorderImpl implements ReferralEventRecorder {

    private static final String USER_LABEL = "USER";
    private static final String SYSTEM_LABEL_PREFIX = "SYSTEM:";
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private final ReferralEventRepository repository;

    @Override
    public void recordUserEvent(GeneralReferral referral,
                                ReferralEventType type,
                                ReferralStatus fromStatus,
                                String note) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = resolveAuthenticatedUsername(auth);
        persist(referral, type, fromStatus, username, USER_LABEL, note);
    }

    /**
     * Returns the principal name, or null if there is no authenticated user.
     * Spring's {@link AnonymousAuthenticationToken} reports {@code isAuthenticated() == true},
     * so a plain auth-flag check would record {@code "anonymousUser"} on the audit row;
     * we detect both shapes so anonymous calls leave the column null.
     */
    private static String resolveAuthenticatedUsername(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String name = auth.getName();
        return ANONYMOUS_PRINCIPAL.equals(name) ? null : name;
    }

    @Override
    public void recordSystemEvent(GeneralReferral referral,
                                  ReferralEventType type,
                                  ReferralStatus fromStatus,
                                  String source,
                                  String note) {
        String label = SYSTEM_LABEL_PREFIX + (source == null || source.isBlank() ? "unknown" : source);
        persist(referral, type, fromStatus, null, label, note);
    }

    private void persist(GeneralReferral referral,
                         ReferralEventType type,
                         ReferralStatus fromStatus,
                         String actorUsername,
                         String actorLabel,
                         String note) {
        ReferralEvent event = ReferralEvent.builder()
            .referralId(referral.getId())
            .eventType(type)
            .fromStatus(fromStatus)
            .toStatus(referral.getStatus())
            .actorUsername(actorUsername)
            .actorLabel(actorLabel)
            .note(note)
            .build();
        repository.save(event);
    }
}
