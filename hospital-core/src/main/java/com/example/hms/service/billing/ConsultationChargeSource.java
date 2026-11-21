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
import java.util.UUID;

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
    /** QA D2701: the visit's admission, to tell a planned stay from one that came through a consultation. */
    private final com.example.hms.repository.AdmissionRepository admissionRepository;

    /**
     * QA D2701 (r65): admissions that do not start with a medical consultation. A labour admission, a planned
     * (elective) stay, a day case, a newborn's stay or a transfer in got a « Consultation générale » line by default,
     * which the cashier had to find and delete. They are billed only for the services actually recorded. An emergency,
     * urgent, observation or psychiatric admission is first seen by a doctor and keeps the default line (E3), unless
     * the emergency visit of the same episode was already billed for it ({@link EmergencyEpisodeConsultation}).
     */
    static final java.util.Set<com.example.hms.enums.AdmissionType> NO_DEFAULT_CONSULTATION = java.util.EnumSet.of(
        com.example.hms.enums.AdmissionType.LABOR_DELIVERY,
        com.example.hms.enums.AdmissionType.ELECTIVE,
        com.example.hms.enums.AdmissionType.DAY_CASE,
        com.example.hms.enums.AdmissionType.NEWBORN,
        com.example.hms.enums.AdmissionType.TRANSFER);

    /**
     * Policy rule 6 (provisional, V345): the rule above is the default of every hospital, and a hospital may choose
     * otherwise for itself ({@code hospital_billing_policies.auto_consultation_on_planned_admission}). A required
     * setter: the application always wires it; a source built by hand in a unit test has none and uses the default.
     */
    private HospitalBillingPolicyService billingPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public void setBillingPolicy(HospitalBillingPolicyService billingPolicy) {
        this.billingPolicy = billingPolicy;
    }

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
        if (services.isEmpty() && !plannedAdmission(visit) && !billedOnTheEmergencyVisit(visit)) {
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

    /**
     * Policy rule 6 (provisional, owner decision 2026-11-20): one consultation per episode. A required setter: the
     * application always wires it; a source built by hand in a unit test has none and bills as before.
     */
    private EmergencyEpisodeConsultation emergencyEpisode;

    @org.springframework.beans.factory.annotation.Autowired
    public void setEmergencyEpisode(EmergencyEpisodeConsultation emergencyEpisode) {
        this.emergencyEpisode = emergencyEpisode;
    }

    /**
     * Whether the stay follows an emergency visit already billed in the same episode: its consultation was charged
     * there, and the stay's invoice gets no second line ({@link EmergencyEpisodeConsultation}).
     */
    private boolean billedOnTheEmergencyVisit(Visit visit) {
        UUID admissionId = visit.encounter().getAdmissionId();
        if (admissionId == null || admissionRepository == null || emergencyEpisode == null) {
            return false;
        }
        return admissionRepository.findById(admissionId)
            .map(emergencyEpisode::alreadyBilledOnTheEmergencyVisit)
            .orElse(false);
    }

    /** QA D2701: whether the visit is an admission of a kind that does not begin with a consultation. */
    private boolean plannedAdmission(Visit visit) {
        UUID admissionId = visit.encounter().getAdmissionId();
        if (admissionId == null || admissionRepository == null) {
            return false;
        }
        // Policy rule 6: a hospital that chose a default consultation on every admission gets one.
        UUID hospitalId = visit.encounter().getHospital() != null ? visit.encounter().getHospital().getId() : null;
        if (billingPolicy != null && billingPolicy.autoConsultationOnPlannedAdmission(hospitalId)) {
            return false;
        }
        return admissionRepository.findById(admissionId)
            .map(admission -> admission.getAdmissionType() != null
                && NO_DEFAULT_CONSULTATION.contains(admission.getAdmissionType()))
            .orElse(false);
    }
}
