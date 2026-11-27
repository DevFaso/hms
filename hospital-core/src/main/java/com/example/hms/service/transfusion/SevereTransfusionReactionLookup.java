package com.example.hms.service.transfusion;

import com.example.hms.enums.TransfusionReactionSeverity;
import com.example.hms.enums.TransfusionReactionType;
import com.example.hms.model.Hospital;
import com.example.hms.model.TransfusionReaction;
import com.example.hms.repository.TransfusionReactionRepository;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.utility.RoleValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D4151 (r81) and its review: a severe or life-threatening transfusion reaction is a permanent safety line of
 * the patient, at every hospital, like the allo-immunisation flag. What crosses from another hospital is the
 * minimum the next transfusion needs: the type, the date and the hospital that holds the record; never its text.
 * Each time another hospital's reaction is shown, the read is accounted as a cross-hospital one.
 */
@Slf4j
@Service
public class SevereTransfusionReactionLookup {

    /** The severities that stay on the patient's record as a safety line. */
    public static final Set<TransfusionReactionSeverity> ON_RECORD =
        EnumSet.of(TransfusionReactionSeverity.SEVERE, TransfusionReactionSeverity.LIFE_THREATENING);

    static final String REACH_DESCRIPTION =
        "Cross-hospital severe transfusion reaction read (transfusion safety, shown whatever the sharing choice)";

    /** The newest severe reaction on record: its type, onset and the hospital that holds it. */
    public record SevereReaction(TransfusionReactionType type, LocalDateTime onsetAt, UUID hospitalId,
                                 String hospitalName) {
    }

    private final TransfusionReactionRepository reactionRepository;
    private final ObjectProvider<CrossHospitalReachRecorder> reachRecorder;
    private final ObjectProvider<RoleValidator> roleValidator;

    public SevereTransfusionReactionLookup(TransfusionReactionRepository reactionRepository,
                                           ObjectProvider<CrossHospitalReachRecorder> reachRecorder,
                                           ObjectProvider<RoleValidator> roleValidator) {
        this.reactionRepository = reactionRepository;
        this.reachRecorder = reachRecorder;
        this.roleValidator = roleValidator;
    }

    /**
     * The newest severe reaction of the patient at any hospital, as it is SHOWN to somebody acting at
     * {@code actingHospitalId}: another hospital's reaction is accounted as a cross-hospital read.
     */
    public Optional<SevereReaction> shownAt(UUID patientId, UUID actingHospitalId) {
        if (patientId == null) {
            return Optional.empty();
        }
        Optional<TransfusionReaction> newest =
            reactionRepository.findFirstByPatient_IdAndSeverityInOrderByOnsetAtDesc(patientId, ON_RECORD);
        newest.ifPresent(reaction -> accountReach(patientId, actingHospitalId, reaction.getHospital()));
        return newest.map(reaction -> new SevereReaction(reaction.getReactionType(), reaction.getOnsetAt(),
            CrossHospitalReachRecorder.hospitalIdOf(reaction.getHospital()),
            reaction.getHospital() != null ? reaction.getHospital().getName() : null));
    }

    private void accountReach(UUID patientId, UUID actingHospitalId, Hospital holder) {
        UUID holderId = CrossHospitalReachRecorder.hospitalIdOf(holder);
        if (actingHospitalId == null || holderId == null || Objects.equals(holderId, actingHospitalId)) {
            return;
        }
        CrossHospitalReachRecorder recorder = reachRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        try {
            RoleValidator roles = roleValidator.getIfAvailable();
            recorder.recordReach(patientId, actingHospitalId, roles != null ? roles.getCurrentUserId() : null, null,
                CrossHospitalReachRecorder.reachOf(List.of(holderId), actingHospitalId), REACH_DESCRIPTION);
        } catch (RuntimeException ex) {
            log.warn("Cross-hospital transfusion reaction read not accounted for patient {} at hospital {}: {}",
                patientId, actingHospitalId, ex.getClass().getSimpleName());
        }
    }
}
