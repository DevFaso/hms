package com.example.hms.service.billing;

import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * QA D972: blood given on the visit. A request is charged once its blood was
 * issued (ISSUED or COMPLETED), for the units issued against it (a unit
 * returned unused is not charged); a facility that does not track units per
 * request is charged the units requested. A request still waiting, or
 * cancelled, is not charged. The product is named in the staff language and
 * priced by the hospital's service of that name, else at zero.
 */
@Component
@RequiredArgsConstructor
public class TransfusionChargeSource implements VisitChargeSource {

    static final EnumSet<TransfusionRequestStatus> BILLED =
        EnumSet.of(TransfusionRequestStatus.ISSUED, TransfusionRequestStatus.COMPLETED);
    private static final Set<BloodUnitStatus> GIVEN = EnumSet.of(BloodUnitStatus.ISSUED, BloodUnitStatus.TRANSFUSED);

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
            String product = MessageUtil.resolve(NotificationLocales.STAFF,
                "transfusion.productType." + request.getProductType().name());
            long issued = bloodUnitRepository.findByRequest_IdOrderByUnitNumberAsc(request.getId()).stream()
                .filter(unit -> GIVEN.contains(unit.getStatus()))
                .count();
            int units;
            if (issued > 0) {
                units = (int) issued;
            } else {
                units = request.getUnitsRequested() != null ? request.getUnitsRequested() : 1;
            }
            String description = MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.transfusion", product);
            lines.add(VisitCharge.of(description, ItemCategory.SERVICE, units,
                visit.prices().sameName(product).or(() -> visit.prices().sameName(description))));
        }
        return lines;
    }
}
