package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MedicalCertificateType;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientDocumentMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientUploadedDocument;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.MedicalCertificateRequestDTO;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.PatientUploadedDocumentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.CallerIdentity;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.PatientLocaleResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * QA D961: a doctor issues a medical certificate or a sick-leave note
 * (arrêt de travail) from a visit. The certificate is a dated PDF in the
 * patient's language (or the one the doctor picks: FR, EN, ES), signed in the
 * doctor's name with a fingerprint of its content, and filed in the patient's
 * documents, so the staff chart and the patient portal list and download it
 * like any other document. Nothing is stored that the PDF does not say.
 *
 * <p>Who may issue it: a doctor (controller) with a staff record at the
 * visit's hospital, for a visit of that hospital that the patient was seen
 * in; a visit closed unseen or cancelled has nothing to certify.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalCertificateServiceImpl implements MedicalCertificateService {

    private static final String NOT_FOUND = "encounter.notfound";
    private static final float MARGIN = 64f;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

    private final EncounterRepository encounterRepository;
    private final StaffRepository staffRepository;
    private final PatientUploadedDocumentRepository documentRepository;
    private final PatientDocumentMapper documentMapper;
    private final FileUploadService fileUploadService;
    private final PatientLocaleResolver patientLocaleResolver;
    private final MessageSource messageSource;
    private final com.example.hms.repository.EncounterNoteRepository encounterNoteRepository;

    @Override
    @Transactional
    public PatientDocumentResponseDTO issue(UUID encounterId, MedicalCertificateRequestDTO request) {
        Encounter encounter = encounterRepository.findById(encounterId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND, encounterId));
        Hospital hospital = encounter.getHospital();
        // 404-not-403: another hospital's visit is indistinguishable from a missing one.
        if (hospital == null) {
            throw new ResourceNotFoundException(NOT_FOUND, encounterId);
        }
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, NOT_FOUND, encounterId);
        if (EncounterClosureRules.isNeverSeen(encounter.getStatus())
                || EncounterStatus.ENDED_UNSEEN.contains(encounter.getStatus())) {
            throw new BusinessException("medicalCertificate.visitNotSeen");
        }
        // The signer is the signed-in doctor at this hospital, never a name in the body.
        Staff doctor = CallerIdentity.resolveActor(staffRepository, null, hospital.getId());
        if (doctor == null || doctor.getUser() == null) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("identity.notCaller"));
        }
        // The certificate says "I examined the patient on that day": only a
        // clinician who treated the visit (its clinician of record or a note
        // author on it) may sign it, not any doctor of the hospital.
        if (!TreatingClinician.isCaller(encounter, encounterNoteRepository)) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolveOrRaw("medicalCertificate.notTreating"));
        }
        Patient patient = encounter.getPatient();
        Certificate certificate = describe(encounter, request);
        Locale locale = request.getLanguage() != null
            ? Locale.forLanguageTag(request.getLanguage())
            : patientLocaleResolver.resolve(patient, NotificationLocales.PATIENT_FALLBACK);

        LocalDateTime signedAt = LocalDateTime.now();
        List<String> body = bodyLines(certificate, patient, doctor, locale);
        String fingerprint = fingerprint(certificate, patient, doctor, hospital, signedAt);
        byte[] pdf = render(locale, hospital, certificate, body, doctor, signedAt, fingerprint);

        String title = text(locale, "medicalCertificate.title." + certificate.type().name());
        String baseName = title + " " + signedAt.toLocalDate();
        FileUploadService.StoredFileDescriptor stored;
        try {
            stored = fileUploadService.storeGeneratedPatientDocument(pdf, baseName, doctor.getUser().getId());
        } catch (IOException e) {
            throw new IllegalStateException("medicalCertificate.store.failed", e);
        }
        PatientUploadedDocument doc = PatientUploadedDocument.builder()
            .patient(patient)
            .uploadedByUser(doctor.getUser())
            .documentType(PatientDocumentType.MEDICAL_CERTIFICATE)
            .displayName(stored.displayName())
            .filePath(stored.storageKey())
            .fileUrl(stored.publicUrl())
            .mimeType(stored.contentType())
            .fileSizeBytes(stored.sizeBytes())
            .checksumSha256(stored.sha256())
            .collectionDate(signedAt.toLocalDate())
            // The reference printed on the PDF is kept with the record, so the
            // hospital can check a presented copy against what it issued.
            .notes(summary(certificate, locale) + " - " + text(locale, "medicalCertificate.reference",
                fingerprint.substring(0, 16)))
            .build();
        PatientUploadedDocument saved = documentRepository.save(doc);
        log.info("Medical certificate {} ({}) issued for encounter {} by staff {}",
            saved.getId(), certificate.type(), encounterId, doctor.getId());
        return documentMapper.toDto(saved);
    }

    /** What the certificate attests, validated: the days of a sick leave, the doctor's words of a general one. */
    record Certificate(MedicalCertificateType type, LocalDate visitDay, LocalDate start, LocalDate end,
                       Integer days, String remarks) {
    }

    static Certificate describe(Encounter encounter, MedicalCertificateRequestDTO request) {
        LocalDate visitDay = encounter.getEncounterDate() != null
            ? encounter.getEncounterDate().toLocalDate() : LocalDate.now();
        String remarks = request.getRemarks() == null || request.getRemarks().isBlank()
            ? null : request.getRemarks().trim();
        return switch (request.getType()) {
            case SICK_LEAVE -> {
                if (request.getDays() == null) {
                    throw new BusinessException("medicalCertificate.days.required");
                }
                LocalDate start = request.getStartDate() != null ? request.getStartDate() : visitDay;
                if (start.isBefore(visitDay)) {
                    throw new BusinessException("medicalCertificate.start.beforeVisit");
                }
                yield new Certificate(MedicalCertificateType.SICK_LEAVE, visitDay, start,
                    start.plusDays(request.getDays() - 1L), request.getDays(), remarks);
            }
            case ATTENDANCE -> new Certificate(MedicalCertificateType.ATTENDANCE, visitDay, visitDay, visitDay, null, remarks);
            case GENERAL -> {
                if (remarks == null) {
                    throw new BusinessException("medicalCertificate.remarks.required");
                }
                yield new Certificate(MedicalCertificateType.GENERAL, visitDay, visitDay, visitDay, null, remarks);
            }
        };
    }

    private List<String> bodyLines(Certificate c, Patient patient, Staff doctor, Locale locale) {
        String patientName = joinName(patient.getFirstName(), patient.getLastName());
        String dob = patient.getDateOfBirth() != null ? date(patient.getDateOfBirth(), locale) : "—";
        List<String> lines = new ArrayList<>();
        lines.add(text(locale, "medicalCertificate.body.intro", doctorName(doctor), patientName, dob,
            date(c.visitDay(), locale)));
        switch (c.type()) {
            case SICK_LEAVE -> lines.add(text(locale, "medicalCertificate.body.sickLeave", c.days(),
                date(c.start(), locale), date(c.end(), locale)));
            case ATTENDANCE -> lines.add(text(locale, "medicalCertificate.body.attendance", date(c.visitDay(), locale)));
            case GENERAL -> lines.add(text(locale, "medicalCertificate.body.general"));
        }
        if (c.remarks() != null) {
            lines.add(c.remarks());
        }
        lines.add(text(locale, "medicalCertificate.body.closing"));
        return lines;
    }

    private String summary(Certificate c, Locale locale) {
        return switch (c.type()) {
            case SICK_LEAVE -> text(locale, "medicalCertificate.summary.sickLeave", c.days(),
                date(c.start(), locale), date(c.end(), locale));
            case ATTENDANCE -> text(locale, "medicalCertificate.summary.attendance", date(c.visitDay(), locale));
            case GENERAL -> text(locale, "medicalCertificate.summary.general", date(c.visitDay(), locale));
        };
    }

    /**
     * SHA-256 over what the certificate says and who issued it when. Its first
     * 16 characters are printed on the PDF as the document reference and kept
     * in the stored record (with the PDF's own checksum), which is what a
     * presented copy is checked against; it is not a cryptographic signature.
     */
    static String fingerprint(Certificate c, Patient patient, Staff doctor, Hospital hospital, LocalDateTime signedAt) {
        String canonical = String.join("|", c.type().name(), String.valueOf(patient.getId()),
            String.valueOf(doctor.getId()), String.valueOf(hospital.getId()), String.valueOf(c.start()),
            String.valueOf(c.end()), String.valueOf(c.days()), c.remarks() == null ? "" : c.remarks(),
            signedAt.toString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("medicalCertificate.fingerprint.failed", e);
        }
    }

    private byte[] render(Locale locale, Hospital hospital, Certificate c, List<String> body, Staff doctor,
                          LocalDateTime signedAt, String fingerprint) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float y = PDRectangle.A4.getHeight() - MARGIN;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                y = line(cs, PDType1Font.HELVETICA_BOLD, 13, y, pdfSafe(hospital.getName()));
                if (hospital.getCity() != null) {
                    y = line(cs, PDType1Font.HELVETICA, 10, y, pdfSafe(hospital.getCity()));
                }
                y -= 28;
                y = line(cs, PDType1Font.HELVETICA_BOLD, 17, y,
                    pdfSafe(text(locale, "medicalCertificate.title." + c.type().name()).toUpperCase(locale)));
                y -= 18;
                for (String paragraph : body) {
                    for (String wrapped : wrap(pdfSafe(paragraph), PDType1Font.HELVETICA, 11)) {
                        y = line(cs, PDType1Font.HELVETICA, 11, y, wrapped);
                    }
                    y -= 8;
                }
                y -= 16;
                String place = hospital.getCity() != null ? hospital.getCity() : hospital.getName();
                y = line(cs, PDType1Font.HELVETICA, 11, y,
                    pdfSafe(text(locale, "medicalCertificate.doneAt", place, date(signedAt.toLocalDate(), locale))));
                y -= 24;
                y = line(cs, PDType1Font.HELVETICA_BOLD, 11, y, pdfSafe(doctorName(doctor)));
                y -= 30;
                String signed = text(locale, "medicalCertificate.signature",
                    signedAt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)),
                    fingerprint.substring(0, 16));
                for (String wrapped : wrap(pdfSafe(signed), PDType1Font.HELVETICA_OBLIQUE, 8)) {
                    y = line(cs, PDType1Font.HELVETICA_OBLIQUE, 8, y, wrapped);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalStateException("medicalCertificate.pdf.failed", e);
        }
    }

    private static float line(PDPageContentStream cs, PDFont font, float size, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText(text);
        cs.endText();
        return y - size * 1.45f;
    }

    /** Greedy word wrap to the page width. */
    static List<String> wrap(String text, PDFont font, float size) throws IOException {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder current = new StringBuilder();
            for (String word : para.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > WIDTH && !current.isEmpty()) {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            out.add(current.toString());
        }
        return out;
    }

    /** The standard fonts are WinAnsi: keep what they can draw (French and Spanish letters are in it). */
    static String pdfSafe(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char ch : text.replace('’', '\'').replace(' ', ' ').replace(' ', ' ').toCharArray()) {
            sb.append(ch == '\n' || (ch >= 0x20 && ch <= 0x7e) || (ch >= 0xa0 && ch <= 0xff) || ch == '—' ? ch : '?');
        }
        return sb.toString();
    }

    private String text(Locale locale, String key, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    private static String date(LocalDate day, Locale locale) {
        return day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale));
    }

    private static String doctorName(Staff doctor) {
        String name = doctor.getFullName();
        return name == null || name.isBlank() ? "—" : name;
    }

    private static String joinName(String first, String last) {
        return ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
    }
}
