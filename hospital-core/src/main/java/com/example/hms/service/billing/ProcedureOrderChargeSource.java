package com.example.hms.service.billing;

import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.repository.ProcedureOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** QA D972: each procedure order of the visit not cancelled, priced by the service of the same name. */
@Component
@RequiredArgsConstructor
public class ProcedureOrderChargeSource implements VisitChargeSource {

    static final EnumSet<ProcedureOrderStatus> NOT_BILLED = EnumSet.of(ProcedureOrderStatus.CANCELLED);

    private final ProcedureOrderRepository procedureOrderRepository;

    @Override
    public BillableOrderType type() {
        return BillableOrderType.PROCEDURE_ORDER;
    }

    @Override
    public List<VisitCharge> charges(Visit visit) {
        List<VisitCharge> lines = new ArrayList<>();
        List<UUID> ids = procedureOrderRepository.findIdsByEncounterIdAndStatusNotIn(visit.encounter().getId(), NOT_BILLED);
        if (ids == null || ids.isEmpty()) {
            return lines;
        }
        for (ProcedureOrder order : procedureOrderRepository.findAllById(ids)) {
            String procedure = order.getProcedureName();
            if (procedure != null && !procedure.isBlank()) {
                lines.add(VisitCharge.of(procedure, ItemCategory.SERVICE, 1, visit.prices().sameName(procedure)));
            }
        }
        return lines;
    }
}
