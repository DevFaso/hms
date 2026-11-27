package com.example.hms.service.billing;

import com.example.hms.enums.PriceListSystemKey;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.ItemCategory;
import com.example.hms.model.Admission;
import com.example.hms.model.Treatment;
import com.example.hms.repository.AdmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * QA D972: the stay of an inpatient visit (the encounter its admission
 * opened, V193), charged by the day when the discharge closes it. Days are
 * counted as hospitals count them, by the nights spent (discharge day minus
 * admission day), and a stay that begins and ends the same day is one day.
 * Priced by the hospital's single active service named for a hospital stay,
 * else at zero for the cashier.
 */
@Component
@RequiredArgsConstructor
public class BedDaysChargeSource implements VisitChargeSource {

    /** Words a hospital's priced-service name uses for a day of stay (EN/FR/ES, folded). */
    static final List<String> STAY_WORDS = List.of("hospitalisation", "hospitalization", "hospitalizacion");

    static final String BED_DAYS_CODE = "billing.checkout.bedDays";

    private final AdmissionRepository admissionRepository;

    @Override
    public BillableOrderType type() {
        return BillableOrderType.BED_DAYS;
    }

    @Override
    public List<VisitCharge> charges(Visit visit) {
        if (visit.encounter().getAdmissionId() == null) {
            return List.of();
        }
        Admission admission = admissionRepository.findById(visit.encounter().getAdmissionId()).orElse(null);
        // The stay must belong to the visit's hospital: never bill another tenant's days.
        if (admission == null || admission.getAdmissionDateTime() == null || admission.getHospital() == null
                || visit.encounter().getHospital() == null
                || !admission.getHospital().getId().equals(visit.encounter().getHospital().getId())) {
            return List.of();
        }
        LocalDate in = admission.getAdmissionDateTime().toLocalDate();
        LocalDate out;
        if (admission.getActualDischargeDateTime() != null) {
            out = admission.getActualDischargeDateTime().toLocalDate();
        } else {
            out = visit.checkedOutAt() != null ? visit.checkedOutAt().toLocalDate() : in;
        }
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(in, out));
        // PR 3: the hospital's bed-day tariff first, else its service named for a stay.
        // QA D4221 (class): a stay in observation of under 24 hours takes the hospital's observation tariff, once,
        // when its price list has one; else the bed-day tariff as before.
        if (admission.getAdmissionType() == com.example.hms.enums.AdmissionType.OBSERVATION
                && underADay(admission, visit)) {
            Optional<TariffResolver.Tariff> observation =
                visit.tariff(PriceListLinkType.SYSTEM, PriceListSystemKey.BED_DAY_OBSERVATION.name());
            if (observation.isPresent()) {
                return List.of(VisitCharge.tariffed(observation.get(), ItemCategory.SERVICE, 1));
            }
        }
        Optional<TariffResolver.Tariff> tariff =
            visit.tariff(PriceListLinkType.SYSTEM, PriceListSystemKey.BED_DAY.name());
        if (tariff.isPresent()) {
            return List.of(VisitCharge.tariffed(tariff.get(), ItemCategory.SERVICE, days));
        }
        Optional<Treatment> stay = visit.prices().naming(STAY_WORDS);
        // QA D1901: the hospital's own service name as written; else the
        // server's words, shown in each reader's language.
        if (stay.isPresent()) {
            return List.of(VisitCharge.of(stay.get().getName(), ItemCategory.SERVICE, days, stay));
        }
        return List.of(VisitCharge.worded(BillingText.of(BED_DAYS_CODE), ItemCategory.SERVICE, days, stay));
    }

    /** Whether the stay lasted under 24 hours, from the admission to the discharge (else to the checkout). */
    private static boolean underADay(Admission admission, Visit visit) {
        java.time.LocalDateTime out = admission.getActualDischargeDateTime() != null
            ? admission.getActualDischargeDateTime() : visit.checkedOutAt();
        return out != null && java.time.Duration.between(admission.getAdmissionDateTime(), out).toHours() < 24;
    }
}
