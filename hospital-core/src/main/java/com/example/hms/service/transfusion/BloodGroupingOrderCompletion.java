package com.example.hms.service.transfusion;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabResult;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.service.lab.LabOrderCompletion;
import com.example.hms.service.lab.LabOrderLifecycle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3905 (blood-group half, r81 D4144): a blood-group order is answered by the grouping the blood bank records,
 * the one place a group is entered and the one that feeds the transfusion checks and the patient record. Before
 * this, the order stayed « En analyse » for ever, or the group was typed a second time as a free-text result that
 * fed nothing. The culture workflow closes its order the same way on its final report (#1340).
 *
 * <p>Only orders whose specimen reached the laboratory are answered: an order still awaiting its sample is left
 * for the grouping done on that sample. An order carrying a lab result that is not released keeps waiting for that
 * release, by the same readiness rule as the result path ({@link LabOrderCompletion}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloodGroupingOrderCompletion {

    /** The specimen is in the laboratory, or a result was entered: a grouping recorded now answers the order. */
    static final Set<LabOrderStatus> ANSWERABLE = EnumSet.of(
        LabOrderStatus.RECEIVED, LabOrderStatus.IN_PROGRESS, LabOrderStatus.RESULTED, LabOrderStatus.VERIFIED);

    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;

    /**
     * Closes the patient's open blood-group orders handled by {@code hospitalId}, in the grouping's transaction:
     * the group and the closed order commit together or not at all.
     *
     * @return the ids of the orders closed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> closeAnsweredOrders(UUID patientId, UUID hospitalId) {
        if (patientId == null || hospitalId == null) {
            return List.of();
        }
        List<UUID> candidates = labOrderRepository.findOpenBloodGroupingOrderIds(patientId, hospitalId, ANSWERABLE);
        List<UUID> closed = new java.util.ArrayList<>();
        for (UUID orderId : candidates) {
            // Serialised with the result path, which decides completion under the same lock.
            labOrderRepository.findWithLockById(orderId);
            LabOrderStatus committed = labOrderRepository.findStatusById(orderId);
            if (committed == null || !ANSWERABLE.contains(committed)) {
                continue;
            }
            LabOrderStatus target = LabOrderLifecycle.statusAfterAllResultsReleased(committed);
            List<LabResult> results = labResultRepository.findByLabOrder_Id(orderId);
            if (target != null && LabOrderCompletion.allReleased(results, true, true)
                    && labOrderRepository.updateStatusFrom(orderId, committed, target) == 1) {
                closed.add(orderId);
            }
        }
        if (!closed.isEmpty()) {
            log.info("Blood-bank grouping closed {} blood-group lab order(s)", closed.size());
        }
        return closed;
    }
}
