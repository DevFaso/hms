package com.example.hms.service.maternity;

import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.WardType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.repository.LaborEpisodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3308 (r70): a woman in active labour (MT-03) was ordered to a surgical bed from the bed board with no
 * word about her labour: the first « Transférer » on the page was hers, not the searched patient's.
 *
 * <p>One rule for every way a bed changes inside a hospital (the transfer order, carrying it out, and the direct
 * bed assignment): while a woman has an ACTIVE labour episode at the hospital, a move to a bed <b>outside a
 * maternity ward</b> is never silent. The person who orders it, and the person who carries it out, must
 * acknowledge the labour ({@code laborAcknowledged}); the order's reason, mandatory for every transfer, says why.
 * A move between maternity beds (labour room to delivery room) is her ordinary care and asks nothing. A bed
 * change by direct assignment has no reason field, so it is refused and sent to the transfer order.
 *
 * <p>Nothing is forbidden: a labouring woman may have to go to theatre or to intensive care. The move is made
 * knowingly, and the order keeps who made it and why. The transfer to another hospital is
 * {@code LaborServiceImpl.transferEpisode}, which has its own reason and handover.
 */
@Component
@RequiredArgsConstructor
public class LabourBedMove {

    /** The request field the acknowledgement travels in; the form marks it. */
    public static final String FIELD = "laborAcknowledged";

    private final LaborEpisodeRepository laborEpisodes;

    /** Whether moving this stay's patient to {@code destination} must be acknowledged as a labouring woman's move. */
    public boolean needsAcknowledgement(Admission admission, Bed destination, UUID hospitalId) {
        if (admission == null || admission.getPatient() == null || destination == null || hospitalId == null) {
            return false;
        }
        WardType ward = destination.getWard() == null ? null : destination.getWard().getWardType();
        return ward != WardType.MATERNITY
            && laborEpisodes.existsByPatient_IdAndHospital_IdAndStatus(admission.getPatient().getId(), hospitalId,
                LaborStatus.ACTIVE);
    }

    /** Refuses a move that needs the acknowledgement and does not carry it. */
    public void requireAcknowledged(Admission admission, Bed destination, UUID hospitalId, Boolean acknowledged) {
        if (!Boolean.TRUE.equals(acknowledged) && needsAcknowledgement(admission, destination, hospitalId)) {
            throw new FieldBusinessException(FIELD, "transfer.labor.acknowledgeRequired");
        }
    }

    /**
     * A bed change by direct assignment (no reason field) for a labouring woman, to a bed outside a maternity
     * ward: refused, the transfer order is where it is made. Her first bed is an admission, not a move.
     */
    public void requireTransferOrderForABedChange(Admission admission, Bed destination, UUID hospitalId, String field) {
        Bed held = admission == null ? null : admission.getBed();
        if (held != null && destination != null && !held.getId().equals(destination.getId())
            && needsAcknowledgement(admission, destination, hospitalId)) {
            throw new FieldBusinessException(field, "transfer.labor.useTransferOrder");
        }
    }

    /** Of {@code patientIds}, the women in active labour at the hospital, in one query (the bed board). */
    public Set<UUID> inActiveLabour(Collection<UUID> patientIds, UUID hospitalId) {
        if (patientIds == null || patientIds.isEmpty() || hospitalId == null) {
            return Set.of();
        }
        return new HashSet<>(laborEpisodes.findPatientIdsWithStatusAmong(hospitalId, LaborStatus.ACTIVE, patientIds));
    }
}
