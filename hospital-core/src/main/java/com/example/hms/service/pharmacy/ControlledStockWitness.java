package com.example.hms.service.pharmacy;

import com.example.hms.exception.BusinessException;
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
 */
@Component
@RequiredArgsConstructor
public class ControlledStockWitness {

    /** Roles whose holder may witness a controlled-substance movement (QA D157). */
    public static final Set<String> WITNESS_ROLE_CODES =
            Set.of("ROLE_PHARMACIST", "ROLE_STORE_MANAGER", "ROLE_HOSPITAL_ADMIN");

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;

    public static boolean isControlled(InventoryItem item) {
        MedicationCatalogItem catalogItem = item == null ? null : item.getMedicationCatalogItem();
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
            throw new BusinessException(MessageUtil.resolve("stockTransaction.witness.required"));
        }
        if (performer != null && witnessId.equals(performer.getId())) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.witness.notSelf"));
        }
        UUID hospitalId = item.getPharmacy() != null && item.getPharmacy().getHospital() != null
                ? item.getPharmacy().getHospital().getId() : null;
        if (hospitalId == null || !assignmentRepository.existsActiveWitness(
                witnessId, hospitalId, WITNESS_ROLE_CODES)) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.witness.invalid"));
        }
        return userRepository.findById(witnessId)
                .orElseThrow(() -> new BusinessException(MessageUtil.resolve("stockTransaction.witness.invalid")));
    }
}
