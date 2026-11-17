package com.example.hms.service.billing;

import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.model.Treatment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * QA D972: blood given on the visit. A request is charged once its blood was
 * issued (ISSUED or COMPLETED), for the units issued against it (a unit
 * returned unused is not charged); a facility that does not track units per
 * request is charged the units requested. A request still waiting, or
 * cancelled, is not charged. The line is stored as a code (QA D1901) and
 * priced by the hospital's service named as the product, else at zero.
 */
@Component
@RequiredArgsConstructor
public class TransfusionChargeSource implements VisitChargeSource {

    static final EnumSet<TransfusionRequestStatus> BILLED =
        EnumSet.of(TransfusionRequestStatus.ISSUED, TransfusionRequestStatus.COMPLETED);
    private static final Set<BloodUnitStatus> GIVEN = EnumSet.of(BloodUnitStatus.ISSUED, BloodUnitStatus.TRANSFUSED);

    static final String TRANSFUSION_CODE = "billing.checkout.transfusion";

    private final TransfusionRequestRepository transfusionRequestRepository;
    private final BloodUnitRepository bloodUnitRepository;

    @Override
    public BillableOrderType type() {
        return BillableOrderType.TRANSFUSION;
    }

    @Override
    public List<VisitCharge> charges(Visit visit) {
        List<VisitCharge> lines = new ArrayList<>();
        List<TransfusionRequest> requests = transfusionRequestRepository.findByEncounter_IdAndStatusIn(
            visit.encounter().getId(), BILLED);
        if (requests == null) {
            return lines;
        }
        for (TransfusionRequest request : requests) {
            if (request.getProductType() == null) {
                continue;
            }
            String productCode = "transfusion.productType." + request.getProductType().name();
            long issued = bloodUnitRepository.findByRequest_IdOrderByUnitNumberAsc(request.getId()).stream()
                .filter(unit -> GIVEN.contains(unit.getStatus()))
                .count();
            int units;
            if (issued > 0) {
                units = (int) issued;
            } else {
                units = request.getUnitsRequested() != null ? request.getUnitsRequested() : 1;
            }
            // QA D1901: stored as its code and the product's code, shown in each
            // reader's language. The hospital names its priced services in its
            // own language (French), so the price is matched on the French words.
            BillingText text = BillingText.of(TRANSFUSION_CODE, BillingText.code(productCode));
            // PR 3: the tariff linked to the product first, else the service named as the product.
            Optional<TariffResolver.Tariff> tariff =
                visit.tariff(PriceListLinkType.TRANSFUSION_PRODUCT, request.getProductType().name());
            if (tariff.isPresent()) {
                lines.add(VisitCharge.tariffed(tariff.get(), ItemCategory.SERVICE, units));
            } else {
                lines.add(VisitCharge.worded(text, ItemCategory.SERVICE, units, priceOf(visit, productCode, text)));
            }
        }
        return lines;
    }

    /** The hospital's service named as the product, else as the whole line, in the stored (French) words. */
    private static Optional<Treatment> priceOf(Visit visit, String productCode, BillingText line) {
        String productWords = BillingText.of(productCode).french();
        String lineWords = line.french();
        return visit.prices().sameName(productWords).or(() -> visit.prices().sameName(lineWords));
    }
}
