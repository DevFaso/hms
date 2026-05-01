package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.ReferralEvent;
import com.example.hms.repository.ReferralEventRepository;
import com.example.hms.service.ReferralEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReferralEventRecorderImpl implements ReferralEventRecorder {

    private static final String USER_LABEL = "USER";
    private static final String SYSTEM_LABEL_PREFIX = "SYSTEM:";

    private final ReferralEventRepository repository;

    @Override
    public void recordUserEvent(GeneralReferral referral,
                                ReferralEventType type,
                                ReferralStatus fromStatus,
                                String note) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
        persist(referral, type, fromStatus, username, USER_LABEL, note);
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
