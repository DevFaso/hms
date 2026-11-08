package com.example.hms.service.billing;

import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabOrder;
import com.example.hms.repository.LabOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** QA D972: each lab order of the visit not withdrawn, priced by the service of the same name. */
@Component
@RequiredArgsConstructor
public class LabOrderChargeSource implements VisitChargeSource {

    /** Withdrawn orders are never charged. */
    static final EnumSet<LabOrderStatus> NOT_BILLED = EnumSet.of(LabOrderStatus.CANCELLED, LabOrderStatus.ENTERED_IN_ERROR);

    private final LabOrderRepository labOrderRepository;

    @Override
    public BillableOrderType type() {
        return BillableOrderType.LAB_ORDER;
    }

    @Override
    public List<VisitCharge> charges(Visit visit) {
        List<VisitCharge> lines = new ArrayList<>();
        List<UUID> ids = labOrderRepository.findIdsByEncounterIdAndStatusNotIn(visit.encounter().getId(), NOT_BILLED);
        if (ids == null || ids.isEmpty()) {
            return lines;
        }
        for (LabOrder order : labOrderRepository.findAllById(ids)) {
            String test = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : null;
            if (test != null && !test.isBlank()) {
                lines.add(VisitCharge.of(test, ItemCategory.LAB_TEST, 1, visit.prices().sameName(test)));
            }
        }
        return lines;
    }
}
