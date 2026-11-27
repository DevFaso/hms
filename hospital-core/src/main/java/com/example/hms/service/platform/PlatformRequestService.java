package com.example.hms.service.platform;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * QA D4308: a hospital administrator writes to the platform. The request is
 * filed in every platform administrator's inbox, with the hospital it comes
 * from and who sent it; nothing else stores it and nothing is sent by SMS or
 * e-mail.
 */
@Service
@RequiredArgsConstructor
public class PlatformRequestService {

    public static final int MAX_SUBJECT_LENGTH = 120;
    public static final int MAX_MESSAGE_LENGTH = 2000;

    private final PlatformInbox platformInbox;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;

    /** Files the request and answers its reference. */
    @Transactional(readOnly = true)
    public UUID send(UUID requesterId, String subject, String message) {
        String cleanSubject = subject == null ? "" : subject.trim();
        String cleanMessage = message == null ? "" : message.trim();
        RequiredFields.check()
            .require(!cleanSubject.isEmpty(), "subject", "platformRequest.subject.required")
            .require(!cleanMessage.isEmpty(), "message", "platformRequest.message.required")
            .orThrow();
        if (cleanSubject.length() > MAX_SUBJECT_LENGTH) {
            throw new FieldBusinessException("subject", "platformRequest.subject.tooLong",
                MAX_SUBJECT_LENGTH, cleanSubject.length());
        }
        if (cleanMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new FieldBusinessException("message", "platformRequest.message.tooLong",
                MAX_MESSAGE_LENGTH, cleanMessage.length());
        }
        UUID hospitalId = ActingScopeResolver.pinnedHospitalIdOrNull();
        Hospital hospital = hospitalId == null ? null : hospitalRepository.findClinicalById(hospitalId).orElse(null);
        if (hospital == null) {
            // A request comes from one hospital: the one the administrator is working at.
            throw new BusinessException(RoleValidator.HOSPITAL_CONTEXT_REQUIRED);
        }
        String requester = userRepository.findById(requesterId).map(PlatformRequestService::nameOf).orElse(null);
        UUID requestId = UUID.randomUUID();
        platformInbox.hospitalRequest(requestId, hospital.getId(), hospital.getName(), requester,
            cleanSubject, cleanMessage);
        return requestId;
    }

    private static String nameOf(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
            + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}
