package com.example.hms.service.billing;

import com.example.hms.enums.ItemCategory;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Treatment;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
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
                lines.add(new VisitCharge(treatment.getName(), ItemCategory.SERVICE, 1, treatment.getPrice(), treatment));
            }
        }
        if (services.isEmpty()) {
            Optional<Treatment> consultation = visit.prices().naming(CONSULTATION_WORDS);
            lines.add(VisitCharge.of(consultation.map(Treatment::getName)
                    .orElseGet(() -> MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.consultation")),
                ItemCategory.SERVICE, 1, consultation));
        }
        return lines;
    }
}
