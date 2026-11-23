package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.EndedStayOrderGuard;
import com.example.hms.service.support.DeceasedPatientGuard;
import com.example.hms.service.support.OrderReviewHoldGuard;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * QA D3054 (r68): the prescriber could not hand the patient an ordonnance. The
 * only printable prescription was the pharmacy's « copie pour le patient »,
 * which exists only once the pharmacy routed an order PRINTED_FOR_PATIENT.
 *
 * <p>This is the prescriber's own document: the hospital, the date, the
 * patient, the prescriber, the prescriber's own still-dispensable medicines of
 * the same visit (dose, route, frequency, duration, quantity, instructions)
 * and the prescriber's signature line.
 *
 * <p>Who and what (review of #1264):
 * <ul>
 *   <li>the caller's active hospital only, and only that hospital's
 *       prescriptions (another hospital's, or none, answers 404);</li>
 *   <li>never for a deceased patient;</li>
 *   <li>only the prescriber of the order asked for (403 for a colleague, with
 *       a sentence about the caller, never about the record);</li>
 *   <li>only an order still to be dispensed ({@link #PRINTABLE_STATUSES}): an
 *       order already dispensed, partly filled, with a partner pharmacy, printed
 *       for the patient by the pharmacy, or waiting on the prescriber's answer
 *       to a pharmacist's question has no new ordonnance, or it could be filled
 *       twice or the question bypassed;</li>
 *   <li>not while a prescriber review holds it, nor for a ward order of an
 *       ended stay (the rule every pharmacy action applies);</li>
 *   <li>each print is audited (PRESCRIPTION_PRINTED, ids only).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OrdonnancePdfService {

    private static final String NOT_FOUND = "prescription.notfound";
    static final String NOT_IN_FORCE = "prescription.ordonnance.notInForce";
    static final String NOT_PRESCRIBER = "prescription.ordonnance.notPrescriber";
    private static final String AUDIT_ENTITY = "PRESCRIPTION";

    /**
     * Still to be dispensed, by nobody else yet: the SMS dispatch's statuses
     * less SENT_TO_PARTNER (a partner pharmacy holds the offer). Not
     * PENDING_CLARIFICATION, PARTIALLY_FILLED, PARTNER_ACCEPTED, DISPENSED,
     * PARTNER_DISPENSED or PRINTED_FOR_PATIENT.
     */
    static final Set<PrescriptionStatus> PRINTABLE_STATUSES = Set.of(
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.TRANSMISSION_FAILED,
            PrescriptionStatus.PENDING_STOCK,
            PrescriptionStatus.PARTNER_REJECTED);

    private final PrescriptionRepository prescriptionRepository;
    private final RoleValidator roleValidator;
    private final StaffRepository staffRepository;
    private final EndedStayOrderGuard endedStayOrders;
    private final PharmacyServiceSupport support;
    private final com.example.hms.repository.PatientVitalSignRepository vitalSigns;

    /** As on the pharmacy copy (QA D922): a drug the catalogue marks controlled is controlled. */
    private PrescriptionControlledResolver controlledResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setControlledResolver(PrescriptionControlledResolver controlledResolver) {
        this.controlledResolver = controlledResolver;
    }

    @Transactional(readOnly = true)
    public byte[] forPrescriber(UUID prescriptionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        Prescription rx = prescriptionRepository.findById(prescriptionId)
                .filter(p -> p.getHospital() != null && hospitalId.equals(p.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        DeceasedPatientGuard.refuseNewCare(rx.getPatient());
        requireOwnPrescription(rx, hospitalId);
        if (!printable(rx)) {
            throw new BusinessException(NOT_IN_FORCE);
        }
        OrderReviewHoldGuard.refuseCopyIfReviewPending(rx);
        if (endedStayOrders.endedWithAStay(rx)) {
            throw new BusinessException("pharmacy.prescription.stayEnded");
        }
        List<Prescription> lines = visitLines(rx, hospitalId);
        byte[] pdf = render(rx, lines, LocaleContextHolder.getLocale());
        support.logAudit(AuditEventType.PRESCRIPTION_PRINTED,
                "Ordonnance printed by its prescriber: prescriptions "
                        + lines.stream().map(l -> String.valueOf(l.getId())).collect(Collectors.joining(",")),
                rx.getId().toString(), AUDIT_ENTITY, hospitalId);
        return pdf;
    }

    private static boolean printable(Prescription rx) {
        return rx.getStatus() != null && PRINTABLE_STATUSES.contains(rx.getStatus());
    }

    /** The ordonnance is the prescriber's document: the caller's staff record here wrote the order. */
    private void requireOwnPrescription(Prescription rx, UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        UUID callerStaffId = userId == null ? null : staffRepository.findByUserIdAndHospitalId(userId, hospitalId)
                .map(Staff::getId).orElse(null);
        UUID prescriberStaffId = rx.getStaff() != null ? rx.getStaff().getId() : null;
        if (callerStaffId == null || !callerStaffId.equals(prescriberStaffId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw(NOT_PRESCRIBER));
        }
    }

    /**
     * The same prescriber's medicines of the same visit for the same patient at the same hospital, still to
     * be dispensed, not held for review, not ended with a stay, and on the same side of the inpatient /
     * take-home line as the one asked for. The one asked for is always listed.
     */
    List<Prescription> visitLines(Prescription rx, UUID hospitalId) {
        List<Prescription> lines = new ArrayList<>();
        lines.add(rx);
        if (rx.getEncounter() != null && rx.getEncounter().getId() != null) {
            UUID patientId = rx.getPatient() != null ? rx.getPatient().getId() : null;
            UUID prescriberId = rx.getStaff() != null ? rx.getStaff().getId() : null;
            prescriptionRepository.findByEncounter_IdAndHospital_IdAndStatusIn(
                            rx.getEncounter().getId(), hospitalId, PRINTABLE_STATUSES, Pageable.unpaged())
                    .stream()
                    .filter(p -> !Objects.equals(p.getId(), rx.getId()))
                    .filter(p -> p.getPatient() != null && Objects.equals(p.getPatient().getId(), patientId))
                    .filter(p -> p.getStaff() != null && Objects.equals(p.getStaff().getId(), prescriberId))
                    .filter(OrdonnancePdfService::printable)
                    .filter(p -> p.isInpatientOrder() == rx.isInpatientOrder())
                    .filter(p -> OrderReviewHoldGuard.reviewPendingSince(p) == null)
                    .filter(p -> !endedStayOrders.endedWithAStay(p))
                    .forEach(lines::add);
        }
        lines.sort(Comparator.comparing(Prescription::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return lines;
    }

    byte[] render(Prescription rx, List<Prescription> lines, Locale locale) {
        DateTimeFormatter date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
        try (PharmacyPdfWriter pdf = new PharmacyPdfWriter(false)) {
            writeHospital(pdf, rx.getHospital());
            pdf.rule();
            pdf.space(6f);
            pdf.paragraph(PharmacyPdfWriter.BOLD, 18f, label(locale, "pdf.ordonnance.title"));
            pdf.space(4f);
            LocalDate prescribedOn = rx.getCreatedAt() != null ? rx.getCreatedAt().toLocalDate() : null;
            pdf.field(label(locale, "pdf.ordonnance.date"),
                    prescribedOn == null ? null : date.format(prescribedOn), 11f);
            writePatient(pdf, rx, locale, date);
            pdf.field(label(locale, "pdf.ordonnance.prescriber"), prescriberLine(rx.getStaff(), locale), 11f);
            pdf.space(6f);
            pdf.rule();
            pdf.space(4f);
            int n = 1;
            for (Prescription line : lines) {
                writeLine(pdf, line, n++, locale);
            }
            pdf.space(18f);
            pdf.rule();
            pdf.paragraph(PharmacyPdfWriter.BOLD, 11f, label(locale, "pdf.ordonnance.signature"));
            pdf.space(48f);
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 11f, "______________________________");
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, Objects.toString(prescriberName(rx.getStaff()), ""));
            pdf.space(10f);
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 8f, label(locale, "pdf.ordonnance.printedOn",
                    com.example.hms.utility.DisplayDates.dateTime(
                            com.example.hms.utility.HospitalTime.at(now, rx.getHospital()), locale)));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 8f, label(locale, "pdf.rxcopy.reference", rx.getId()));
            return pdf.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("Ordonnance rendering failed", e);
        }
    }

    private static void writeHospital(PharmacyPdfWriter pdf, Hospital hospital) throws IOException {
        if (hospital == null) {
            return;
        }
        pdf.paragraph(PharmacyPdfWriter.BOLD, 13f, Objects.toString(hospital.getName(), ""));
        String place = joinNonBlank(", ", hospital.getAddress(), hospital.getCity());
        if (place != null) {
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, place);
        }
        String contact = joinNonBlank("  ", hospital.getPhoneNumber(), hospital.getEmail());
        if (contact != null) {
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, contact);
        }
    }

    private void writePatient(PharmacyPdfWriter pdf, Prescription rx, Locale locale,
                                     DateTimeFormatter date) throws IOException {
        Patient patient = rx.getPatient();
        pdf.field(label(locale, "pdf.ordonnance.patient"), patient == null ? null : patient.getFullName(), 11f);
        if (patient == null) {
            return;
        }
        if (patient.getDateOfBirth() != null) {
            pdf.field(label(locale, "pdf.rxcopy.birthDate"), date.format(patient.getDateOfBirth()), 11f);
            if (rx.getCreatedAt() != null && !patient.getDateOfBirth().isAfter(rx.getCreatedAt().toLocalDate())) {
                java.time.Period age = java.time.Period.between(patient.getDateOfBirth(), rx.getCreatedAt().toLocalDate());
                pdf.field(label(locale, "pdf.ordonnance.age"), label(locale, "pdf.ordonnance.ageValue",
                        age.getYears(), age.getMonths(), age.getDays()), 11f);
            }
        }
        writeLatestWeight(pdf, patient, rx.getHospital(), locale);
        String mrn = rx.getHospital() != null ? patient.getMrnForHospital(rx.getHospital().getId()) : null;
        if (mrn != null && !mrn.isBlank()) {
            pdf.field(label(locale, "pdf.ordonnance.mrn"), mrn, 11f);
        }
    }

    /** The most recent measured weight in this hospital, explicitly dated rather than a dose snapshot. */
    private void writeLatestWeight(PharmacyPdfWriter pdf, Patient patient, Hospital hospital,
                                   Locale locale) throws IOException {
        if (patient.getId() == null || hospital == null || hospital.getId() == null) {
            return;
        }
        var latest = vitalSigns.findFirstByPatient_IdAndHospital_IdAndWeightKgIsNotNullOrderByRecordedAtDesc(
                patient.getId(), hospital.getId());
        if (latest.isEmpty()) {
            return;
        }
        var vital = latest.get();
        String measuredAt = vital.getRecordedAt() == null ? null
                : com.example.hms.utility.DisplayDates.dateTime(
                        com.example.hms.utility.HospitalTime.at(vital.getRecordedAt(), hospital), locale);
        pdf.field(label(locale, "pdf.ordonnance.latestWeight"), joinNonBlank(" - ",
                com.example.hms.utility.DisplayNumbers.quantity(
                        java.math.BigDecimal.valueOf(vital.getWeightKg()), locale) + " kg", measuredAt), 11f);
    }

    /** One medicine: its name, then dose · route · frequency · duration · quantity, then the instructions. */
    private void writeLine(PharmacyPdfWriter pdf, Prescription rx, int n, Locale locale) throws IOException {
        String name = firstNonBlank(rx.getMedicationDisplayName(), rx.getMedicationName());
        pdf.paragraph(PharmacyPdfWriter.BOLD, 12f, n + ". " + Objects.toString(name, "-"));
        pdf.field("   " + label(locale, "pdf.rxcopy.dosage"), joinNonBlank(" ", rx.getDosage(), rx.getDoseUnit()), 10f);
        pdf.field("   " + label(locale, "pdf.rxcopy.route"),
                com.example.hms.service.emar.MedicationRoutes.label(rx.getRoute(), locale), 10f);
        pdf.field("   " + label(locale, "pdf.rxcopy.frequency"), rx.getFrequency(), 10f);
        if (com.example.hms.service.impl.PrnMinInterval.isAsNeeded(rx)) {
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, "   " + (rx.getPrnMinIntervalHours() != null
                    ? label(locale, "pdf.rxcopy.asNeededInterval", rx.getPrnMinIntervalHours())
                    : label(locale, "pdf.rxcopy.asNeeded")));
        }
        pdf.field("   " + label(locale, "pdf.rxcopy.duration"), rx.getDuration(), 10f);
        if (rx.getQuantity() != null) {
            pdf.field("   " + label(locale, "pdf.ordonnance.quantity"),
                    joinNonBlank(" ", com.example.hms.utility.DisplayNumbers.quantity(rx.getQuantity(), locale), rx.getQuantityUnit()), 10f);
        }
        if (rx.getRefillsAllowed() != null && rx.getRefillsAllowed() > 0) {
            pdf.field("   " + label(locale, "pdf.ordonnance.refills"), String.valueOf(rx.getRefillsAllowed()), 10f);
        }
        if (rx.getInstructions() != null && !rx.getInstructions().isBlank()) {
            pdf.field("   " + label(locale, "pdf.rxcopy.instructions"), rx.getInstructions(), 10f);
        }
        if (controlledResolver != null ? controlledResolver.isControlled(rx) : rx.isControlledSubstance()) {
            pdf.paragraph(PharmacyPdfWriter.BOLD, 9f, "   " + label(locale, "pdf.rxcopy.controlled"));
        }
        pdf.space(6f);
    }

    private static String label(Locale locale, String key, Object... args) {
        return MessageUtil.resolve(locale, key, args);
    }

    private static String prescriberName(Staff staff) {
        if (staff == null || staff.getUser() == null) {
            return null;
        }
        String full = joinNonBlank(" ", staff.getUser().getFirstName(), staff.getUser().getLastName());
        return full != null ? full : staff.getUser().getUsername();
    }

    /** The name, then the speciality and the licence number when the staff record has them. */
    private static String prescriberLine(Staff staff, Locale locale) {
        if (staff == null) {
            return null;
        }
        return joinNonBlank(" - ", prescriberName(staff), specializationLabel(staff.getSpecialization(), locale), staff.getLicenseNumber());
    }

    /** Known catalogue codes are labels; an entered free-text specialty stays intact. */
    private static String specializationLabel(String specialization, Locale locale) {
        if (specialization == null || specialization.isBlank()) {
            return specialization;
        }
        String translated = MessageUtil.resolveOrNull(locale, "pdf.ordonnance.specialty." + specialization.trim());
        return translated == null ? specialization : translated;
    }

    private static String joinNonBlank(String separator, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(separator);
            }
            out.append(part.trim());
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
