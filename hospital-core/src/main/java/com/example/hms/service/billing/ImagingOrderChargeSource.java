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
                lines.add(tariffOf(order, study, visit).map(t -> VisitCharge.tariffed(t, ItemCategory.SERVICE, 1))
                    .orElseGet(() -> VisitCharge.of(study.trim(), ItemCategory.SERVICE, 1,
                        visit.prices().sameName(study))));
            }
        }
        return lines;
    }

    /**
     * QA D3254 (r70): the order's price. An order picked from the imaging catalogue is priced by that item, whatever
     * its wording (« Radiographie du thorax (face) », the price list's own label, billed 0 F because it was not the
     * link key word for word). A free-text order keeps PR 3's lookup: the tariff linked to
     * {@code <modality>:<study type>}. With neither, the caller falls back to the service of the same name, and
     * then posts the line unpriced for the cashier: an order is never left off the invoice.
     */
    private static Optional<TariffResolver.Tariff> tariffOf(ImagingOrder order, String study, Visit visit) {
        if (order.getCatalogueItemId() != null) {
            Optional<TariffResolver.Tariff> byItem = visit.tariffOfItem(order.getCatalogueItemId());
            if (byItem.isPresent()) {
                return byItem;
            }
        }
        return order.getModality() == null ? Optional.empty()
            : visit.tariff(PriceListLinkType.IMAGING_STUDY, order.getModality().name() + ":" + study);
    }
}
