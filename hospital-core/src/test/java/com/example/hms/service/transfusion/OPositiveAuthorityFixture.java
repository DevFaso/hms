package com.example.hms.service.transfusion;

import com.example.hms.model.IncompatibleReleaseDecision;
import com.example.hms.model.OPositiveReleaseDecision;
import com.example.hms.model.OPositiveReleaseProtocol;
import com.example.hms.repository.IncompatibleReleaseDecisionRepository;
import com.example.hms.repository.OPositiveReleaseDecisionRepository;
import com.example.hms.repository.OPositiveReleaseProtocolRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.RoleValidator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The real release decision service (O positive, V380, and incompatible release, V381) and its notices for a
 * unit test of the transfusion service, over their own in-basket and audit doubles: what the release paths file
 * there is asserted in {@code OPositiveReleaseAuthorityServiceTest} and {@code IncompatibleReleaseDecisionsTest},
 * and the counts an older test makes on its own doubles stay about the rule that test is on. With no
 * transaction, what the service defers to « after the commit » runs at once.
 */
public final class OPositiveAuthorityFixture {

    public final OPositiveReleaseProtocolRepository protocolRepository = mock(OPositiveReleaseProtocolRepository.class);
    public final OPositiveReleaseDecisionRepository decisionRepository = mock(OPositiveReleaseDecisionRepository.class);
    public final IncompatibleReleaseDecisionRepository incompatibleRows = mock(IncompatibleReleaseDecisionRepository.class);
    public final OnCallScheduleRepository onCallRepository = mock(OnCallScheduleRepository.class);
    public final PositiveAntibodyScreenService antibodyScreen = mock(PositiveAntibodyScreenService.class);
    public final PatientRepository patientRepository = mock(PatientRepository.class);
    public final InBasketService inBasket = mock(InBasketService.class);
    public final AuditEventLogService audit = mock(AuditEventLogService.class);
    /** Every decision row written, in order. */
    public final List<OPositiveReleaseDecision> decisions = new ArrayList<>();
    /** V381: every incompatible release decision row written, in order. */
    public final List<IncompatibleReleaseDecision> incompatibleDecisions = new ArrayList<>();
    public final OPositiveReleaseNotices notices;
    public final IncompatibleReleaseNotices incompatibleNotices;
    public final IncompatibleReleaseDecisions incompatible;
    public final OPositiveReleaseAuthorityService service;
    /** The settings row of the hospital, once a test gave it one. */
    private OPositiveReleaseProtocol protocol;

    public OPositiveAuthorityFixture(UserRoleHospitalAssignmentRepository assignmentRepository, UserRepository userRepository,
                                     RoleValidator roleValidator, Clock clock) {
        this(assignmentRepository, userRepository, roleValidator, clock, mock(PatientBloodGroupRepository.class),
            mock(TransfusionCrossmatchRepository.class), mock(TransfusionAdministrationRepository.class));
    }

    /** With the blood group, crossmatch and administration doubles of the test: an incompatible release reads them. */
    public OPositiveAuthorityFixture(UserRoleHospitalAssignmentRepository assignmentRepository, UserRepository userRepository,
                                     RoleValidator roleValidator, Clock clock,
                                     PatientBloodGroupRepository bloodGroupRepository,
                                     TransfusionCrossmatchRepository crossmatchRepository,
                                     TransfusionAdministrationRepository administrationRepository) {
        lenient().when(decisionRepository.save(any())).thenAnswer(call -> {
            OPositiveReleaseDecision row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                decisions.add(row);
            }
            return row;
        });
        lenient().when(decisionRepository.lockById(any())).thenAnswer(call -> decisions.stream()
            .filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
        lenient().when(decisionRepository.lockByIdAndHospital(any(), any())).thenAnswer(call -> decisions.stream()
            .filter(row -> row.getId().equals(call.getArgument(0)))
            .filter(row -> row.getHospital() != null && Objects.equals(row.getHospital().getId(), call.getArgument(1)))
            .findFirst());
        lenient().when(incompatibleRows.save(any())).thenAnswer(call -> {
            IncompatibleReleaseDecision row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                incompatibleDecisions.add(row);
            }
            return row;
        });
        lenient().when(incompatibleRows.lockById(any())).thenAnswer(call -> incompatibleDecisions.stream()
            .filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
        lenient().when(incompatibleRows.lockByIdAndHospital(any(), any())).thenAnswer(call -> incompatibleDecisions.stream()
            .filter(row -> row.getId().equals(call.getArgument(0)))
            .filter(row -> row.getHospital() != null && Objects.equals(row.getHospital().getId(), call.getArgument(1)))
            .findFirst());
        lenient().when(incompatibleRows.findByHospital_IdOrderByIssuedAtDesc(any())).thenAnswer(call -> {
            List<IncompatibleReleaseDecision> rows = new ArrayList<>(incompatibleDecisions.stream()
                .filter(row -> row.getHospital() != null && Objects.equals(row.getHospital().getId(), call.getArgument(0)))
                .toList());
            java.util.Collections.reverse(rows);
            return rows;
        });
        lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.empty());
        notices = new OPositiveReleaseNotices(decisionRepository, assignmentRepository, userRepository, patientRepository,
            inBasket, audit, clock);
        incompatibleNotices = new IncompatibleReleaseNotices(incompatibleRows, assignmentRepository, userRepository,
            onCallRepository, inBasket, audit, clock);
        incompatible = new IncompatibleReleaseDecisions(incompatibleRows, protocolRepository, assignmentRepository,
            bloodGroupRepository, crossmatchRepository, administrationRepository, userRepository, antibodyScreen,
            roleValidator, inBasket, audit, incompatibleNotices, clock);
        service = new OPositiveReleaseAuthorityService(protocolRepository, decisionRepository, assignmentRepository,
            userRepository, roleValidator, inBasket, audit, notices, clock);
        service.setIncompatible(incompatible);
    }

    /**
     * For a test of the O positive rule alone: the incompatible release rule (V381) is replaced by a double that
     * allows everything and records nothing, so a release typed « serologically incompatible » exercises only what
     * the O positive rule says of it.
     */
    public OPositiveAuthorityFixture oPositiveRuleOnly() {
        service.setIncompatible(mock(IncompatibleReleaseDecisions.class));
        return this;
    }

    /** The hospital wrote its protocol with the defaults: women of 50 or less and boys protected, 24 hours. */
    public OPositiveAuthorityFixture activeProtocol() {
        return protocol(true, 50, false, 24);
    }

    public OPositiveAuthorityFixture protocol(boolean active, int femaleAgeCutoff, boolean boysLowRisk, int deadlineHours) {
        boolean fallback = protocol != null && protocol.isIncompatibleFallbackEnabled();
        protocol = OPositiveReleaseProtocol.builder()
            .active(active).femaleAgeCutoff(femaleAgeCutoff).boysLowRisk(boysLowRisk).reviewDeadlineHours(deadlineHours)
            .incompatibleFallbackEnabled(fallback).build();
        lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.of(protocol));
        return this;
    }

    public OPositiveAuthorityFixture noProtocol() {
        protocol = null;
        lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.empty());
        return this;
    }

    /** V381: the laboratory director switched « Libération incompatible sans médecin joignable » on, or off. */
    public OPositiveAuthorityFixture incompatibleFallback(boolean enabled, String... declarerRoles) {
        if (protocol == null) {
            protocol = OPositiveReleaseProtocol.builder().build();
            lenient().when(protocolRepository.findByHospital_Id(any())).thenReturn(Optional.of(protocol));
        }
        protocol.setIncompatibleFallbackEnabled(enabled);
        if (declarerRoles.length > 0) {
            protocol.setIncompatibleFallbackDeclarerRoles(String.join(",", declarerRoles));
        }
        return this;
    }

    public OPositiveReleaseDecision last() {
        return decisions.get(decisions.size() - 1);
    }

    public IncompatibleReleaseDecision lastIncompatible() {
        return incompatibleDecisions.get(incompatibleDecisions.size() - 1);
    }
}
