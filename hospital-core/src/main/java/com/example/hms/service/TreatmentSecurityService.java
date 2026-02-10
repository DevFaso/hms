package com.example.hms.service;

import com.example.hms.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreatmentSecurityService {

    private final TreatmentRepository treatmentRepository;
    private final AuthService authService;

    public boolean isTreatmentCreator(Authentication authentication, UUID treatmentId) {
        UUID currentUserId = authService.getCurrentUserId();
        return treatmentRepository.findById(treatmentId)
                .map(treatment -> treatment.getAssignment().getUser().getId().equals(currentUserId))
                .orElse(false);
    }
}

