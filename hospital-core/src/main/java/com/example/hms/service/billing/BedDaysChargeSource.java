package com.example.hms.service.billing;

import com.example.hms.enums.ItemCategory;
import com.example.hms.model.Admission;
import com.example.hms.model.Treatment;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
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
        Optional<Treatment> stay = visit.prices().naming(STAY_WORDS);
        String description = stay.map(Treatment::getName)
            .orElseGet(() -> MessageUtil.resolve(NotificationLocales.STAFF, "billing.checkout.bedDays"));
        return List.of(VisitCharge.of(description, ItemCategory.SERVICE, days, stay));
    }
}
