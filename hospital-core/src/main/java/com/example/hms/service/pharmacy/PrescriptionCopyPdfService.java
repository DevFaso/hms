package com.example.hms.service.pharmacy;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * QA D966: "Imprimer pour le patient" recorded the routing decision and
 * produced nothing to print; the patient portal then said "Imprimée pour le
 * patient" with nothing to download. This renders the copy the patient takes
 * to an outside pharmacy: the prescription as signed, the quantity still to
 * dispense (what the print decision recorded), who prescribed it and where.
 *
 * <p>Only a prescription in PRINTED_FOR_PATIENT has a copy: the copy is the
 * fact of that routing decision, not a way to print any order. Staff read it
 * in their own hospital; the patient reads their own.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionCopyPdfService {

    private static final String NOT_FOUND = "prescription.notfound";

    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final DispenseRepository dispenseRepository;
    private final RoleValidator roleValidator;

    /** The copy for the pharmacy that printed it (the active hospital's prescriptions only). */
    @Transactional(readOnly = true)
    public byte[] copyForStaff(UUID prescriptionId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(rx -> rx.getHospital() != null && hospitalId.equals(rx.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        return render(prescription, LocaleContextHolder.getLocale());
    }

    /** The copy for the patient it was printed for (their own prescriptions only). */
    @Transactional(readOnly = true)
    public byte[] copyForPatient(UUID prescriptionId, UUID patientId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(rx -> rx.getPatient() != null && patientId != null
                        && patientId.equals(rx.getPatient().getId()))
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        return render(prescription, LocaleContextHolder.getLocale());
    }

    byte[] render(Prescription rx, Locale locale) {
        if (rx.getStatus() != PrescriptionStatus.PRINTED_FOR_PATIENT) {
            throw new BusinessException("pharmacy.rxCopy.notPrinted");
        }
        PrescriptionRoutingDecision print = routingDecisionRepository
                .findByPrescriptionIdOrderByDecidedAtDesc(rx.getId()).stream()
                .filter(d -> d.getRoutingType() == RoutingType.PRINT)
                .findFirst().orElse(null);
        BigDecimal remaining = print != null && print.getRemainingQuantity() != null
                ? print.getRemainingQuantity()
                : FillAccounting.remaining(rx, dispenseRepository.sumQuantityDispensedForPrescription(
                        rx.getId(), DispenseRepository.NOT_A_FILL));
        DateTimeFormatter date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
        DateTimeFormatter dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale);
        try (PharmacyPdfWriter pdf = new PharmacyPdfWriter(false)) {
            pdf.paragraph(PharmacyPdfWriter.BOLD, 16f, label(locale, "pdf.rxcopy.title"));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, rx.getHospital() != null
                    ? Objects.toString(rx.getHospital().getName(), "") : "");
            pdf.rule();
            pdf.space(4f);
            writeWhoAndWhen(pdf, rx, locale, date);
            pdf.space(6f);
            pdf.rule();
            pdf.space(4f);
            writeMedication(pdf, rx, remaining, locale);
            pdf.space(10f);
            pdf.rule();
            LocalDateTime printedAt = print != null && print.getDecidedAt() != null
                    ? print.getDecidedAt() : LocalDateTime.now(ZoneId.systemDefault());
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label(locale, "pdf.rxcopy.printedBy",
                    print != null ? userName(print.getDecidedByUser()) : "-", dateTime.format(printedAt)));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label(locale, "pdf.rxcopy.footer"));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 8f, label(locale, "pdf.rxcopy.reference", rx.getId()));
            return pdf.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("Prescription copy rendering failed", e);
        }
    }

    /** The patient, the prescriber and the date of the prescription. */
    private static void writeWhoAndWhen(PharmacyPdfWriter pdf, Prescription rx, Locale locale,
                                        DateTimeFormatter date) throws IOException {
        Patient patient = rx.getPatient();
        pdf.field(label(locale, "pdf.rxcopy.patient"), patient == null ? null
                : join(patient.getFirstName(), patient.getLastName()), 11f);
        if (patient != null && patient.getDateOfBirth() != null) {
            pdf.field(label(locale, "pdf.rxcopy.birthDate"), date.format(patient.getDateOfBirth()), 11f);
        }
        pdf.field(label(locale, "pdf.rxcopy.prescriber"), prescriberName(rx.getStaff()), 11f);
        pdf.field(label(locale, "pdf.rxcopy.prescribedOn"), rx.getCreatedAt() == null ? null
                : date.format(rx.getCreatedAt().toLocalDate()), 11f);
    }

    /** The medicine as prescribed, and what is still to be dispensed of it. */
    private static void writeMedication(PharmacyPdfWriter pdf, Prescription rx, BigDecimal remaining,
                                        Locale locale) throws IOException {
        pdf.field(label(locale, "pdf.rxcopy.medication"), firstNonBlank(rx.getMedicationDisplayName(),
                rx.getMedicationName()), 12f);
        pdf.field(label(locale, "pdf.rxcopy.dosage"), join(rx.getDosage(), rx.getDoseUnit()), 11f);
        pdf.field(label(locale, "pdf.rxcopy.route"), rx.getRoute(), 11f);
        pdf.field(label(locale, "pdf.rxcopy.frequency"), rx.getFrequency(), 11f);
        pdf.field(label(locale, "pdf.rxcopy.duration"), rx.getDuration(), 11f);
        pdf.field(label(locale, "pdf.rxcopy.quantityToDispense"),
                FillAccounting.remainingLabel(remaining, rx.getQuantityUnit()), 12f);
        BigDecimal perFill = FillAccounting.perFillQuantity(rx);
        if (remaining != null && perFill != null && remaining.compareTo(FillAccounting.expectedLifetimeQuantity(rx)) < 0) {
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, label(locale, "pdf.rxcopy.partlyDispensed",
                    FillAccounting.remainingLabel(FillAccounting.expectedLifetimeQuantity(rx), rx.getQuantityUnit())));
        }
        if (rx.getInstructions() != null && !rx.getInstructions().isBlank()) {
            pdf.field(label(locale, "pdf.rxcopy.instructions"), rx.getInstructions(), 11f);
        }
        if (rx.isControlledSubstance()) {
            pdf.space(4f);
            pdf.paragraph(PharmacyPdfWriter.BOLD, 10f, label(locale, "pdf.rxcopy.controlled"));
        }
    }

    private static String label(Locale locale, String key, Object... args) {
        return MessageUtil.resolve(locale, key, args);
    }

    private static String prescriberName(Staff staff) {
        if (staff == null) {
            return null;
        }
        return staff.getUser() != null ? userName(staff.getUser()) : null;
    }

    private static String userName(User user) {
        if (user == null) {
            return "-";
        }
        String full = join(user.getFirstName(), user.getLastName());
        return full == null ? user.getUsername() : full;
    }

    private static String join(String first, String second) {
        String joined = (Objects.toString(first, "").trim() + " " + Objects.toString(second, "").trim()).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
