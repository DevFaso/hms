package com.example.hms.service.emar;

import com.example.hms.cdshooks.CdsCardText;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.impl.PrnMinInterval;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CR-03: the documented paracetamol exposure at a dose, read outside the dose's transaction. The MAR
 * calls it after its own commit, so a failure here can never undo a dose already given, and the row
 * just written is on record. The same order settles two doses given at once on two orders of one
 * patient: each reads after it committed, so whichever commits last counts both, with no patient-row
 * lock taken in the dose's transaction.
 */
@Service
public class ParacetamolExposureCheck {

    private final CdsRuleEngine engine;
    private final PrescriptionRepository prescriptions;
    private CdsCardText text = CdsCardText.ENGLISH;

    public ParacetamolExposureCheck(CdsRuleEngine engine, PrescriptionRepository prescriptions) {
        this.engine = engine;
        this.prescriptions = prescriptions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(CdsCardText text) {
        this.text = text;
    }

    /**
     * Throws when the read fails: the caller answers {@link #unknownCard} and the dose stands. Not
     * marked read-only: it must see the commit just made, whatever a read-only transaction is routed to.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CdsCard> cards(UUID prescriptionId, UUID hospitalId, boolean doseJustRecorded) {
        Prescription rx = prescriptions.findById(prescriptionId).orElse(null);
        if (rx == null) {
            return List.of();
        }
        // The dose's own write has checked the order's hospital. Read again in a new transaction, an order of
        // another hospital is read for nobody: no cards, and never a failure of the dose. In the global view the
        // order's own hospital is the scope.
        UUID orderHospital = rx.getHospital() == null ? null : rx.getHospital().getId();
        UUID scope = hospitalId != null ? hospitalId : orderHospital;
        if (scope == null || !java.util.Objects.equals(scope, orderHospital)) {
            return List.of();
        }
        return engine.paracetamolAtAdministration(rx.getPatient(), scope,
            new CdsRuleEngine.ProposedOrder(rx.getMedicationName(), rx.getMedicationCode(), rx.getDosage(),
                rx.getFrequency(), engine.itemOf(rx, scope), rx.getId(),
                PrnMinInterval.highestDosesPerDay(PrnMinInterval.isAsNeeded(rx), rx.getPrnMinIntervalHours()),
                rx.getRoute(), null),
            doseJustRecorded);
    }

    /** The read failed: nothing is known about the exposure, and that is said. It refuses nothing. */
    public CdsCard unknownCard(String medicationName) {
        return new CdsCard(
            text.get("cds.adultParacetamol.administration.readFailed.summary",
                "{0}: the paracetamol exposure could not be read. If this medicine holds paracetamol, the exposure is unknown, not zero",
                medicationName == null ? "" : medicationName),
            text.get("cds.adultParacetamol.administration.readFailed.detail",
                "The dose is recorded. Check the doses given on the administration record before the next one."),
            CdsCard.Indicator.WARNING,
            new Source(text.get("cds.source.adultParacetamol", "e-Keneya Adult Paracetamol Check"), null, null),
            null, null, null, UUID.randomUUID().toString());
    }
}
