package com.example.hms.service.transfusion;

import com.example.hms.model.OPositiveReleaseDecision;
import com.example.hms.model.OPositiveReleaseProtocol;
import com.example.hms.repository.OPositiveReleaseDecisionRepository;
import com.example.hms.repository.OPositiveReleaseProtocolRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.RoleValidator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The real O positive decision service for a unit test of the transfusion service, over its own in-basket and
 * audit doubles: what the release paths file there is asserted in {@code OPositiveReleaseAuthorityServiceTest},
 * and the counts an older test makes on its own doubles stay about the rule that test is on.
 */
public final class OPositiveAuthorityFixture {

    public final OPositiveReleaseProtocolRepository protocolRepository = mock(OPositiveReleaseProtocolRepository.class);
    public final OPositiveReleaseDecisionRepository decisionRepository = mock(OPositiveReleaseDecisionRepository.class);
    public final PatientRepository patientRepository = mock(PatientRepository.class);
    public final InBasketService inBasket = mock(InBasketService.class);
    public final AuditEventLogService audit = mock(AuditEventLogService.class);
    /** Every decision row written, in order. */
    public final List<OPositiveReleaseDecision> decisions = new ArrayList<>();
    public final OPositiveReleaseAuthorityService service;

    public OPositiveAuthorityFixture(UserRoleHospitalAssignmentRepository assignmentRepository, UserRepository userRepository,
                                     RoleValidator roleValidator, Clock clock) {
        lenient().when(decisionRepository.save(any())).thenAnswer(call -> {
            OPositiveReleaseDecision row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                decisions.add(row);
            }
            return row;
        });
        lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.empty());
        service = new OPositiveReleaseAuthorityService(protocolRepository, decisionRepository, assignmentRepository,
            userRepository, patientRepository, roleValidator, inBasket, audit, clock);
    }

    /** The hospital wrote its protocol with the defaults: women of 50 or less and boys protected, 24 hours. */
    public OPositiveAuthorityFixture activeProtocol() {
        return protocol(true, 50, false, 24);
    }

    public OPositiveAuthorityFixture protocol(boolean active, int femaleAgeCutoff, boolean boysLowRisk, int deadlineHours) {
        lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.of(OPositiveReleaseProtocol.builder()
            .active(active).femaleAgeCutoff(femaleAgeCutoff).boysLowRisk(boysLowRisk).reviewDeadlineHours(deadlineHours).build()));
        return this;
    }

    public OPositiveAuthorityFixture noProtocol() {
        lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.empty());
        return this;
    }

    public OPositiveReleaseDecision last() {
        return decisions.get(decisions.size() - 1);
    }
}
