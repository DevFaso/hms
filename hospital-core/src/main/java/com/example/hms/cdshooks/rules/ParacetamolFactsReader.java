package com.example.hms.cdshooks.rules;

import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.service.InpatientOrderEnd;
import com.example.hms.service.MedicationCourseStatus;
import com.example.hms.service.pharmacy.DispenseVerificationService;
import com.example.hms.service.pharmacy.PharmacyRefillReminderScheduler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The persistence behind {@link AdultParacetamolRule}. In its own read-only transaction: a CDS
 * hook runs with no session (open-in-view is off), and whether an order ended with its stay reads
 * the order's LAZY encounter (QA D1121). Nothing is read for a child or for another drug.
 */
@Service
public class ParacetamolFactsReader {

    private final PrescriptionRepository prescriptions;
    private final MedicationCatalogItemRepository catalogue;
    private final PatientProblemRepository problems;
    private final AdmissionRepository admissions;

    public ParacetamolFactsReader(PrescriptionRepository prescriptions, MedicationCatalogItemRepository catalogue,
                                  PatientProblemRepository problems, AdmissionRepository admissions) {
        this.prescriptions = prescriptions;
        this.catalogue = catalogue;
        this.problems = problems;
        this.admissions = admissions;
    }

    /**
     * @param item          the proposed order's catalogue item (may be null)
     * @param editedOrderId the order being edited, not "another order" of itself (null on a create)
     */
    @Transactional(readOnly = true)
    public ParacetamolFacts read(Patient patient, UUID hospitalId, String medicationName,
                                 MedicationCatalogItem item, UUID editedOrderId) {
        if (patient == null || !PediatricDoseRule.isAdult(patient)) {
            return ParacetamolFacts.NONE;
        }
        boolean named = ParacetamolIdentity.names(medicationName, item);
        // An item that names another drug settles it, with no catalogue read.
        if (!named && (item != null || medicationName == null || medicationName.isBlank())) {
            return ParacetamolFacts.NONE;
        }
        List<MedicationCatalogItem> paracetamol = paracetamolInCatalogue(hospitalId);
        if (!named && paracetamol.stream()
                .noneMatch(i -> ParacetamolIdentity.namesCatalogueItem(medicationName, i))) {
            return ParacetamolFacts.NONE;
        }
        return new ParacetamolFacts(true, otherOrders(patient.getId(), hospitalId, editedOrderId, paracetamol),
            ParacetamolRisk.of(activeProblems(patient.getId(), hospitalId)));
    }

    /** This hospital's and the national list's active items that are paracetamol alone. */
    private List<MedicationCatalogItem> paracetamolInCatalogue(UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        Page<MedicationCatalogItem> page = catalogue.findActivePage(hospitalId, Pageable.unpaged());
        return page == null ? List.of()
            : page.getContent().stream().filter(ParacetamolIdentity::isParacetamolAlone).toList();
    }

    private List<ParacetamolFacts.OtherOrder> otherOrders(UUID patientId, UUID hospitalId, UUID editedOrderId,
                                                          List<MedicationCatalogItem> paracetamol) {
        List<Prescription> all = hospitalId != null
            ? prescriptions.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : prescriptions.findByPatient_Id(patientId, Pageable.unpaged()).getContent();
        List<ParacetamolFacts.OtherOrder> others = all.stream()
            .filter(rx -> rx.getStatus() != null && !CdsRuleEngine.isTerminal(rx.getStatus()))
            .filter(rx -> !rx.getId().equals(editedOrderId))
            .map(rx -> otherOrder(rx, paracetamol))
            .filter(Objects::nonNull)
            .toList();
        if (others.isEmpty()) {
            return others;
        }
        List<Admission> closedStays = admissions.findByPatientIdOrderByAdmissionDateTimeDesc(patientId).stream()
            .filter(a -> a.getActualDischargeDateTime() != null)
            .toList();
        return others.stream().filter(other -> stillRunning(other.order(), closedStays)).toList();
    }

    /** The order as a paracetamol order, or {@code null} when it is another drug. */
    private static ParacetamolFacts.OtherOrder otherOrder(Prescription rx, List<MedicationCatalogItem> paracetamol) {
        List<MedicationCatalogItem> linked = paracetamol.stream().filter(i -> carries(rx, i)).toList();
        if (!linked.isEmpty()) {
            return new ParacetamolFacts.OtherOrder(rx, linked.get(0));
        }
        List<MedicationCatalogItem> named = paracetamol.stream()
            .filter(i -> ParacetamolIdentity.namesCatalogueItem(rx.getMedicationName(), i))
            .toList();
        if (named.isEmpty()) {
            return ParacetamolIdentity.nameIsParacetamolAlone(rx.getMedicationName())
                ? new ParacetamolFacts.OtherOrder(rx, null) : null;
        }
        boolean oneStrength = named.stream()
            .map(i -> Objects.toString(CdsRuleEngine.strengthMgOf(i))).distinct().count() == 1;
        return new ParacetamolFacts.OtherOrder(rx, oneStrength ? named.get(0) : null);
    }

    private static boolean carries(Prescription rx, MedicationCatalogItem item) {
        if (rx.getMedicationCatalogItemId() != null) {
            return rx.getMedicationCatalogItemId().equals(item.getId());
        }
        return rx.getMedicationCode() != null && rx.getMedicationCode().equals(item.getCode());
    }

    /**
     * Not a past course. {@code MedicationCourseStatus}'s end date reads only the English
     * words, so "5 jours" is read here too; its ON_HOLD (a draft) still counts, because a
     * draft is about to be given. An order with no duration runs until it is stopped.
     */
    private static boolean stillRunning(Prescription rx, List<Admission> closedStays) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate end = MedicationCourseStatus.endDate(rx);
        if (end == null && rx.getCreatedAt() != null) {
            Integer days = PharmacyRefillReminderScheduler.parseDurationDays(rx.getDuration());
            end = days == null || days <= 0 ? null : rx.getCreatedAt().toLocalDate().plusDays(days);
        }
        return (end == null || !end.isBefore(today)) && !InpatientOrderEnd.endedWithAStay(rx, closedStays);
    }

    /** Active rows at this hospital, never one removed as entered in error. */
    private List<PatientProblem> activeProblems(UUID patientId, UUID hospitalId) {
        List<PatientProblem> all = hospitalId != null
            ? problems.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : problems.findByPatient_Id(patientId);
        return all.stream()
            .filter(p -> !p.isEnteredInError())
            .filter(p -> p.getStatus() == null || p.getStatus() == ProblemStatus.ACTIVE
                || p.getStatus() == ProblemStatus.RECURRENCE)
            .toList();
    }
}
