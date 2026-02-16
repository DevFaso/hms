package com.example.hms.service;

import com.example.hms.model.Staff;
import com.example.hms.model.Treatment;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.TreatmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreatmentSecurityServiceTest {

    @Mock private TreatmentRepository treatmentRepository;
    @Mock private AuthService authService;
    @Mock private Authentication authentication;

    @InjectMocks private TreatmentSecurityService service;

    private UUID treatmentId;
    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        treatmentId = UUID.randomUUID();
        currentUserId = UUID.randomUUID();
    }

    @Test
    void isTreatmentCreator_returnsTrue_whenCurrentUserIsCreator() {
        User user = new User();
        user.setId(currentUserId);
        Staff staff = new Staff();
        staff.setUser(user);
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().user(user).build();
        Treatment treatment = Treatment.builder().name("T").build();
        treatment.setAssignment(assignment);

        when(authService.getCurrentUserId()).thenReturn(currentUserId);
        when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));

        boolean result = service.isTreatmentCreator(authentication, treatmentId);

        assertThat(result).isTrue();
    }

    @Test
    void isTreatmentCreator_returnsFalse_whenCurrentUserIsNotCreator() {
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().user(otherUser).build();
        Treatment treatment = Treatment.builder().name("T").build();
        treatment.setAssignment(assignment);

        when(authService.getCurrentUserId()).thenReturn(currentUserId);
        when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.of(treatment));

        boolean result = service.isTreatmentCreator(authentication, treatmentId);

        assertThat(result).isFalse();
    }

    @Test
    void isTreatmentCreator_returnsFalse_whenTreatmentNotFound() {
        when(authService.getCurrentUserId()).thenReturn(currentUserId);
        when(treatmentRepository.findById(treatmentId)).thenReturn(Optional.empty());

        boolean result = service.isTreatmentCreator(authentication, treatmentId);

        assertThat(result).isFalse();
    }
}
