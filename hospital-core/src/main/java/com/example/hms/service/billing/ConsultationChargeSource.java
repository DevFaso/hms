package com.example.hms.service.billing;

import com.example.hms.enums.PriceListSystemKey;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.ItemCategory;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Treatment;
import com.example.hms.repository.EncounterTreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * QA D972: each service recorded on the visit at the hospital's own price for
 * it; when none was recorded, one consultation line, priced by the hospital's
 * single active service named for a consultation, else at zero.
 */
@Component
@RequiredArgsConstructor
public class ConsultationChargeSource implements VisitChargeSource {

    /** Words a hospital's priced-service name uses for a consultation (EN/FR/ES). */
    static final List<String> CONSULTATION_WORDS = List.of("consultation", "consulta");

    static final String CONSULTATION_CODE = "billing.checkout.consultation";

    private final EncounterTreatmentRepository encounterTreatmentRepository;

    @Override
    public BillableOrderType type() {
        return BillableOrderType.CONSULTATION;
    }

    @Override
    public List<VisitCharge> charges(Visit visit) {
        List<EncounterTreatment> services = encounterTreatmentRepository.findByEncounter_Id(visit.encounter().getId());
        List<VisitCharge> lines = new ArrayList<>();
        for (EncounterTreatment given : services) {
            Treatment treatment = given.getTreatment();
            if (treatment != null && treatment.getName() != null) {
                // PR 3: the service's own tariff (a TREATMENT link) first, else the service's price.
                Optional<TariffResolver.Tariff> tariff = treatment.getId() == null ? Optional.empty()
                    : visit.tariff(PriceListLinkType.TREATMENT, treatment.getId().toString());
                lines.add(tariff.map(t -> VisitCharge.tariffed(t, ItemCategory.SERVICE, 1))
                    .orElseGet(() -> new VisitCharge(treatment.getName(), null, ItemCategory.SERVICE, 1,
                        treatment.getPrice(), treatment)));
            }
        }
        if (services.isEmpty()) {
            Optional<TariffResolver.Tariff> tariff =
                visit.tariff(PriceListLinkType.SYSTEM, PriceListSystemKey.CONSULTATION_DEFAULT.name());
            if (tariff.isPresent()) {
                lines.add(VisitCharge.tariffed(tariff.get(), ItemCategory.SERVICE, 1));
                return lines;
            }
            Optional<Treatment> consultation = visit.prices().naming(CONSULTATION_WORDS);
            // QA D1901: the hospital's own service name as written; else the
            // server's word for it, shown in each reader's language.
            lines.add(consultation.isPresent()
                ? VisitCharge.of(consultation.get().getName(), ItemCategory.SERVICE, 1, consultation)
                : VisitCharge.worded(BillingText.of(CONSULTATION_CODE), ItemCategory.SERVICE, 1, consultation));
        }
        return lines;
    }
}
