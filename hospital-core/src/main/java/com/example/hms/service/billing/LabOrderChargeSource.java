package com.example.hms.service.billing;

import java.util.Optional;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.enums.PriceListLinkType;
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
    /** QA D2700: the ordered version's lineage, to find the price linked to the test, not to one version. */
    private final com.example.hms.repository.LabTestDefinitionRepository labTestDefinitionRepository;

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
            LabTestDefinition definition = order.getLabTestDefinition();
            String test = definition != null ? definition.getName() : null;
            if (test != null && !test.isBlank()) {
                // PR 3: the tariff linked to the test definition first, else the service of the same name.
                // QA D2700: linked to the test, whatever version was ordered (a revision never unprices it).
                Optional<TariffResolver.Tariff> tariff = Optional.empty();
                for (UUID version : LabTestLineage.ancestry(definition, labTestDefinitionRepository)) {
                    tariff = visit.tariff(PriceListLinkType.LAB_TEST_DEFINITION, version.toString());
                    if (tariff.isPresent()) {
                        break;
                    }
                }
                lines.add(tariff.map(t -> VisitCharge.tariffed(t, ItemCategory.LAB_TEST, 1))
                    .orElseGet(() -> VisitCharge.of(test, ItemCategory.LAB_TEST, 1, visit.prices().sameName(test))));
            }
        }
        return lines;
    }
}
