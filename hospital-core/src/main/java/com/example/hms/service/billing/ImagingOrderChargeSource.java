package com.example.hms.service.billing;

import java.util.Optional;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.model.ImagingOrder;
import com.example.hms.repository.ImagingOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * QA D972 (round 18): each imaging order of the visit, priced by the service
 * named like its study. An unsigned draft and a cancelled order are not
 * charged: neither is an examination the patient was sent for.
 */
@Component
@RequiredArgsConstructor
public class ImagingOrderChargeSource implements VisitChargeSource {

    static final EnumSet<ImagingOrderStatus> NOT_BILLED = EnumSet.of(ImagingOrderStatus.DRAFT, ImagingOrderStatus.CANCELLED);

    private final ImagingOrderRepository imagingOrderRepository;

    @Override
    public BillableOrderType type() {
        return BillableOrderType.IMAGING_ORDER;
    }

    @Override
    public List<VisitCharge> charges(Visit visit) {
        List<VisitCharge> lines = new ArrayList<>();
        List<ImagingOrder> orders = imagingOrderRepository.findByEncounterIdAndStatusNotIn(
            visit.encounter().getId(), NOT_BILLED);
        if (orders == null) {
            return lines;
        }
        for (ImagingOrder order : orders) {
            String study = order.getStudyType();
            if (study != null && !study.isBlank()) {
                // PR 3: the tariff linked to <modality>:<study type> first, else the service of the same name.
                Optional<TariffResolver.Tariff> tariff = order.getModality() == null ? Optional.empty()
                    : visit.tariff(PriceListLinkType.IMAGING_STUDY, order.getModality().name() + ":" + study);
                lines.add(tariff.map(t -> VisitCharge.tariffed(t, ItemCategory.SERVICE, 1))
                    .orElseGet(() -> VisitCharge.of(study.trim(), ItemCategory.SERVICE, 1,
                        visit.prices().sameName(study))));
            }
        }
        return lines;
    }
}
