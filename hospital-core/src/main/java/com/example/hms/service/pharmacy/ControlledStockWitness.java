package com.example.hms.service.pharmacy;

import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * QA D157 / D654 / D655: the one copy of the controlled-stock witness rule.
 * Every movement of a controlled substance's stock (a manual movement, a
 * receipt, a dispense and its cancel) is witnessed by a second person, not
 * the performer, who holds an active stock role in the hospital that owns
 * the stock. "Controlled" is read from the catalog item the stock is of,
 * so a prescription that was not flagged cannot move it unwitnessed.
 *
 * <p>QA D2679 (r65 → r77): two things the rule lacked. <b>The witness confirms</b>: being named is not
 * witnessing, so a movement is recorded only while the witness's own confirmation, given with their password or
 * authenticator code, is still good ({@link ControlledWitnessConfirmation}). And <b>the witness is pharmacy staff</b>: a second pharmacist, a
 * pharmacy verifier or a store manager. The hospital administrator, who is not pharmacy staff, witnesses only
 * where there is no such colleague besides the person recording the movement (a hospital with one pharmacist),
 * so a small pharmacy is never left unable to move a controlled drug.
 */
@Component
@RequiredArgsConstructor
public class ControlledStockWitness {
    /** Request properties a refusal names (QA D1352 class, round 30). */
    private static final String FIELD_WITNESSED_BY = "witnessedBy";


    /** Roles whose holder may witness a controlled-substance movement (QA D157). */
    public static final Set<String> WITNESS_ROLE_CODES =
            Set.of("ROLE_PHARMACIST", "ROLE_PHARMACY_VERIFIER", "ROLE_STORE_MANAGER", "ROLE_HOSPITAL_ADMIN");

    /** QA D2679: the pharmacy staff among them; whoever holds one of these witnesses in their own right. */
    public static final Set<String> PHARMACY_WITNESS_ROLE_CODES =
            Set.of("ROLE_PHARMACIST", "ROLE_PHARMACY_VERIFIER", "ROLE_STORE_MANAGER");

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    /**
     * QA D2679: the witness's own confirmation. Setter-injected so the hand-built instances of the unit tests
     * keep working; Spring refuses to start without it, so the running application always confirms.
     */
    private ControlledWitnessConfirmation confirmation;

    @org.springframework.beans.factory.annotation.Autowired
    void setConfirmation(ControlledWitnessConfirmation confirmation) {
        this.confirmation = confirmation;
    }

    /**
     * QA D2679: may this holder of a witness role witness for {@code performerId} at this hospital? Pharmacy
     * staff always; the hospital administrator only when no pharmacy colleague other than the performer exists.
     */
    public boolean mayWitness(UUID witnessId, UUID hospitalId, UUID performerId) {
        java.util.List<User> pharmacyStaff = assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId,
                PHARMACY_WITNESS_ROLE_CODES);
        if (pharmacyStaff == null) {
            return true;
        }
        boolean colleagueAvailable = false;
        for (User colleague : pharmacyStaff) {
            UUID id = colleague == null ? null : colleague.getId();
            if (id == null) {
                continue;
            }
            if (id.equals(witnessId)) {
                return true;
            }
            colleagueAvailable |= !id.equals(performerId);
        }
        return !colleagueAvailable;
    }

    public static boolean isControlled(InventoryItem item) {
        MedicationCatalogItem catalogItem = item.getMedicationCatalogItem();
        return catalogItem != null && catalogItem.isControlled();
    }

    /**
     * The witness of a movement of {@code item}'s stock: {@code null} when
     * the item is not controlled (any witness sent is then not recorded, so
     * the column means one thing), otherwise the validated witness.
     */
    public User resolve(UUID witnessId, InventoryItem item, User performer) {
        if (!isControlled(item)) {
            return null;
        }
        if (witnessId == null) {
            // QA D1282: every caller's body names it witnessedBy; the form marks that picker.
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, "stockTransaction.witness.required");
        }
        if (performer != null && witnessId.equals(performer.getId())) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, MessageUtil.resolve("stockTransaction.witness.notSelf"));
        }
        UUID hospitalId = item.getPharmacy() != null && item.getPharmacy().getHospital() != null
                ? item.getPharmacy().getHospital().getId() : null;
        if (hospitalId == null || !assignmentRepository.existsActiveWitness(
                witnessId, hospitalId, WITNESS_ROLE_CODES)) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, MessageUtil.resolve("stockTransaction.witness.invalid"));
        }
        // QA D2679: the administrator is the fallback witness, not a colleague's alternative.
        if (!mayWitness(witnessId, hospitalId, performer != null ? performer.getId() : null)) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, "witnessConfirmation.pharmacyColleagueAvailable");
        }
        User witness = userRepository.findById(witnessId)
                .orElseThrow(() -> new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, MessageUtil.resolve("stockTransaction.witness.invalid")));
        // QA D2679: named is not witnessed. Nothing is written unless the witness has confirmed, themselves,
        // for this performer at this hospital, in the last few minutes.
        if (confirmation != null && !confirmation.isConfirmed(witnessId,
                performer != null ? performer.getId() : null, hospitalId)) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, "witnessConfirmation.notConfirmed");
        }
        return witness;
    }

    /**
     * QA D2679: the witness's own confirmation for the movements {@code performer} is about to record at
     * {@code hospitalId}. The same eligibility as {@link #resolve} (not the performer, a live witness role at
     * this hospital, the administrator only as the fallback), then the witness's password or authenticator
     * code. Returns the confirmation that now stands.
     */
    public com.example.hms.model.pharmacy.StockWitnessConfirmation confirmWitness(UUID witnessId, String secret,
                                                                                  User performer, UUID hospitalId) {
        if (witnessId == null) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, "stockTransaction.witness.required");
        }
        if (performer == null || performer.getId() == null || witnessId.equals(performer.getId())) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, MessageUtil.resolve("stockTransaction.witness.notSelf"));
        }
        if (hospitalId == null || !assignmentRepository.existsActiveWitness(witnessId, hospitalId, WITNESS_ROLE_CODES)) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, MessageUtil.resolve("stockTransaction.witness.invalid"));
        }
        if (!mayWitness(witnessId, hospitalId, performer.getId())) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, "witnessConfirmation.pharmacyColleagueAvailable");
        }
        User witness = userRepository.findById(witnessId)
                .orElseThrow(() -> new com.example.hms.exception.FieldBusinessException(FIELD_WITNESSED_BY, MessageUtil.resolve("stockTransaction.witness.invalid")));
        return confirmation.confirm(witness, performer, hospitalId, secret);
    }

    /** QA D2679: how this colleague would confirm (TOTP, PASSWORD or UNAVAILABLE), for the picker. */
    public String confirmationMethodOf(User witness) {
        return confirmation == null ? ControlledWitnessConfirmation.METHOD_PASSWORD : confirmation.methodFor(witness);
    }
}
