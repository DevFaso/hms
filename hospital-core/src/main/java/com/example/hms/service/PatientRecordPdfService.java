package com.example.hms.service;

import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.chartreview.ChartReviewDTO;
import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The patient's chart as one printable PDF: identity, the four storyboard cards, then the
 * chart-review sections (encounters, notes, results, medications, imaging, procedures).
 * It is the human-readable sibling of the FHIR bundle export: same roles, same active-hospital
 * scope, same registration gate, same PATIENT_EXPORT audit row, and the same reason it
 * exists (a referral, a transfer, a patient asking for their record). It reads through
 * {@link PatientStoryboardService} and {@link ChartReviewService}, so the E8 cross-hospital
 * rules that decide what a clinician may see on screen decide what is on paper, and rows those
 * rules hold back are counted, never listed.
 *
 * <p>Every label on the page comes from the message bundle in the locale of the request that
 * asked for the document (the clinician who clicked), resolved once in {@link #render(UUID)}
 * and handed down as a {@link PdfLabels}; the drawing helpers never consult the request
 * themselves. Dates follow the same locale. Patient data, codes and units are printed as
 * stored.
 *
 * <p>PDFBox with the standard Helvetica faces, like the wristband and invoice PDFs: no font
 * file to ship. Text outside WinAnsi (the fonts' encoding) is written as '?' rather than
 * failing the whole document on one character.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientRecordPdfService {

    /** Chart-review row cap per section; the screen's default is far lower. */
    static final int CHART_LIMIT = 200;
    private static final String AUDIT_ENTITY_TYPE = "PATIENT";
    private static final String NOT_FOUND_KEY = "patient.notFound";
    private static final String SEPARATOR = " - ";

    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientStoryboardService storyboardService;
    private final ChartReviewService chartReviewService;
    private final AuditEventLogService auditEventLogService;
    private final UserRepository userRepository;
    private final MessageSource messageSource;

    public byte[] render(UUID patientId) {
        PdfLabels labels = PdfLabels.ofRequest(messageSource);
        UUID hospitalId = ActingScopeResolver.pinnedHospitalIdOrNull();
        if (hospitalId == null) {
            throw new AccessDeniedException(
                "A patient record download requires an active hospital scope; supply X-Hospital-Id or scope the session.");
        }
        Patient patient = patientRepository.findByIdUnscoped(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, patientId));
        // 404, not 403: a scoped caller asking about an unregistered patient learns nothing.
        if (!registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)) {
            throw new ResourceNotFoundException(NOT_FOUND_KEY, patientId);
        }
        PatientStoryboardDTO storyboard = storyboardService.getStoryboard(patientId, hospitalId);
        ChartReviewDTO chart = chartReviewService.getChartReview(patientId, hospitalId, CHART_LIMIT);

        byte[] pdf = write(storyboard, chart, patient.getAllergies(), labels);
        emitAudit(patient, hospitalId, chart);
        return pdf;
    }

    // ---------------------------------------------------------------- layout

    static byte[] write(PatientStoryboardDTO storyboard, ChartReviewDTO chart, String allergySummary, PdfLabels t) {
        try (Pages pages = new Pages(t)) {
            writeIdentity(pages, storyboard, t);
            writeStoryboard(pages, storyboard, allergySummary, t);
            writeChart(pages, chart, t);
            int held = storyboard.getRestrictedRows() == null ? 0 : storyboard.getRestrictedRows().size();
            if (held > 0) {
                pages.gap();
                pages.text(t.get("pdf.record.heldRows", String.valueOf(held)));
            }
            return pages.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate the patient record PDF", e);
        }
    }

    private static void writeIdentity(Pages pages, PatientStoryboardDTO storyboard, PdfLabels t) throws IOException {
        String printedBy = SecurityUtils.getCurrentUsername();
        pages.title(t.get("pdf.record.title"));
        pages.text(join("   -   ", safe(storyboard.getHospitalName()),
            t.get("pdf.record.generatedAt", t.fmt(LocalDateTime.now(ZoneOffset.UTC))),
            printedBy != null ? t.get("pdf.record.printedBy", printedBy) : null));
        pages.gap();
        pages.heading(t.get("pdf.record.section.identity"));
        PatientStoryboardDTO.PatientHeaderDTO p = storyboard.getPatient();
        if (p == null) return;
        pages.field(t.get("pdf.record.field.name"), p.getFullName());
        pages.field(t.get("pdf.record.field.mrn"), p.getMrn());
        pages.field(t.get("pdf.record.field.dob"), join(" ", t.fmt(p.getDateOfBirth()),
            p.getAgeYears() != null ? t.get("pdf.record.field.ageYears", String.valueOf(p.getAgeYears())) : null));
        pages.field(t.get("pdf.record.field.sex"), label(t, p.getGender()));
        pages.field(t.get("pdf.record.field.bloodType"), p.getBloodType());
    }

    private static void writeAllergies(Pages pages, PatientStoryboardDTO storyboard, String allergySummary,
                                       PdfLabels t) throws IOException {
        pages.heading(t.get("pdf.record.section.allergies"));
        boolean noStructured = storyboard.getAllergies() == null || storyboard.getAllergies().isEmpty();
        if (noStructured && allergySummary != null && !allergySummary.isBlank()) {
            // D85: a record whose allergies exist only as the registration
            // summary text (not yet backfilled into rows) must not print "none".
            pages.wrapped(t.get("pdf.record.allergies.summary", allergySummary.trim()));
        } else {
            pages.rows(storyboard.getAllergies(), a -> join(SEPARATOR,
                a.getAllergenDisplay(), label(t, a.getSeverity()), a.getReaction(),
                label(t, a.getVerificationStatus())));
        }
    }

    private static void writeStoryboard(Pages pages, PatientStoryboardDTO storyboard, String allergySummary,
                                        PdfLabels t) throws IOException {
        writeAllergies(pages, storyboard, allergySummary, t);

        pages.heading(t.get("pdf.record.section.problems"));
        pages.rows(storyboard.getProblems(), pr -> join(SEPARATOR,
            pr.getProblemDisplay(), code(pr.getProblemCode()), label(t, pr.getStatus()),
            pr.isChronic() ? t.get("pdf.record.problem.chronic") : null,
            pr.getOnsetDate() != null ? t.get("pdf.record.problem.since", t.fmt(pr.getOnsetDate())) : null));

        pages.heading(t.get("pdf.record.section.activeEncounter"));
        PatientStoryboardDTO.ActiveEncounterDTO enc = storyboard.getActiveEncounter();
        if (enc == null) {
            pages.text(t.get("pdf.record.encounter.none"));
        } else {
            pages.text(join(SEPARATOR, label(t, enc.getEncounterType()), label(t, enc.getStatus()), t.fmt(enc.getEncounterDate()),
                enc.getDepartmentName(), enc.getStaffFullName(), enc.getRoomAssignment()));
            if (enc.getChiefComplaint() != null) {
                pages.wrapped(t.get("pdf.record.encounter.chiefComplaint", enc.getChiefComplaint()));
            }
        }

        pages.heading(t.get("pdf.record.section.codeStatus"));
        PatientStoryboardDTO.CodeStatusDTO code = storyboard.getCodeStatus();
        if (code == null || code.getStatus() == null) {
            pages.text(t.get("pdf.record.codeStatus.none"));
        } else {
            pages.text(label(t, code.getStatus()));
            pages.rows(code.getDirectives(), d -> join(SEPARATOR, label(t, d.getDirectiveType()), label(t, d.getStatus()),
                d.getEffectiveDate() != null ? t.get("pdf.record.directive.from", t.fmt(d.getEffectiveDate())) : null,
                d.getDescription()));
        }
    }

    private static void writeChart(Pages pages, ChartReviewDTO chart, PdfLabels t) throws IOException {
        pages.heading(t.get("pdf.record.section.encounters"));
        pages.rows(chart.getEncounters(), e -> join(SEPARATOR, t.fmt(e.getEncounterDate()), label(t, e.getEncounterType()),
            label(t, e.getStatus()), e.getDepartmentName(), e.getStaffFullName(), e.getChiefComplaint()));

        pages.heading(t.get("pdf.record.section.notes"));
        // QA D1076: the ward's nursing notes print too, said to be nursing
        // notes, and the template by its name (never "DAR").
        pages.rows(chart.getNotes(), n -> noteRow(n, t));

        pages.heading(t.get("pdf.record.section.results"));
        pages.rows(chart.getResults(), r -> resultRow(r, t));

        pages.heading(t.get("pdf.record.section.medications"));
        pages.rows(chart.getMedications(), m -> join(SEPARATOR, t.fmt(m.getCreatedAt()), m.getMedicationName(),
            m.getDosage(), join(" / ", m.getFrequency(),
                com.example.hms.service.emar.MedicationRoutes.label(m.getRoute(), t.locale()), m.getDuration()), label(t, m.getStatus()),
            m.getPrescriberName(), m.isControlledSubstance() ? t.get("pdf.record.medication.controlled") : null));

        pages.heading(t.get("pdf.record.section.imaging"));
        pages.rows(chart.getImaging(), i -> join(SEPARATOR, t.fmt(i.getOrderedAt()),
            join(" ", label(t, i.getModality()), i.getStudyType(), i.getBodyRegion(), label(t, i.getLaterality())), label(t, i.getStatus()),
            i.getReportImpression() != null ? t.get("pdf.record.imaging.impression", i.getReportImpression()) : null));

        pages.heading(t.get("pdf.record.section.procedures"));
        pages.rows(chart.getProcedures(), pr -> join(SEPARATOR, t.fmt(pr.getOrderedAt()), pr.getProcedureName(),
            label(t, pr.getProcedureCategory()), label(t, pr.getUrgency()), label(t, pr.getStatus()), pr.getOrderingProviderName(),
            t.get(pr.isConsentObtained() ? "pdf.record.procedure.consentObtained"
                : "pdf.record.procedure.consentNotRecorded")));
    }

    /** QA D1076: a nursing note prints as one, with its template by name (never "DAR"). */
    private static String noteRow(ChartReviewDTO.NoteEntryDTO n, PdfLabels t) {
        return join(SEPARATOR, t.fmt(n.getDocumentedAt()),
            "NURSING".equals(n.getSource()) ? t.get("pdf.record.note.nursing") : null,
            label(t, n.getTemplate()),
            n.getAuthorName(), t.get(n.isSigned() ? "pdf.record.note.signed" : "pdf.record.note.draft"),
            n.getPreview());
    }

    private static String resultRow(ChartReviewDTO.ResultEntryDTO r, PdfLabels t) {
        return join(SEPARATOR, t.fmt(r.getResultDate()),
            safe(r.getTestName()) + code(r.getTestCode()),
            // QA D740: an unreleased row prints as pending, never with a value.
            r.isReleased() ? join(" ", com.example.hms.utility.DisplayNumbers.labValue(r.getResultValue(), t.locale()), r.getResultUnit()) : t.get("pdf.record.result.pending"),
            // QA D1016: a correction says so on the printed record too.
            r.isCorrected() ? t.get("pdf.record.result.corrected") : null,
            r.isReleased() ? label(t, r.getAbnormalFlag()) : null, r.getOrderingStaffName());
    }

    // ---------------------------------------------------------------- audit

    private void emitAudit(Patient patient, UUID hospitalId, ChartReviewDTO chart) {
        try {
            String username = SecurityUtils.getCurrentUsername();
            User actor = username == null ? null : userRepository.findByUsername(username).orElse(null);
            int entries = size(chart.getEncounters()) + size(chart.getNotes()) + size(chart.getResults())
                + size(chart.getMedications()) + size(chart.getImaging()) + size(chart.getProcedures());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_EXPORT)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY_TYPE)
                .hospitalId(hospitalId) // QA D533: anchored at the exporting hospital
                .userId(actor != null ? actor.getId() : null)
                .userName(actor != null ? actor.getUsername() : username)
                .resourceId(patient.getId().toString())
                // No PHI in the description: the id is the resource, the name is not.
                .eventDescription("Patient record PDF downloaded for Patient/" + patient.getId()
                    + " at hospital " + hospitalId + " (" + entries + " chart entries)")
                .build());
        } catch (RuntimeException ex) {
            log.warn("audit emission failed for the patient record PDF of {}: {}", patient.getId(), ex.toString());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    /**
     * D85: a stored code (SIGNED, WAITING_FOR_PHYSICIAN, SEVERE...) printed in
     * the reader's language: the PDF bundle, then the shared clinical enum
     * bundle, then the code made readable - never the raw constant.
     */
    static String label(PdfLabels t, String code) {
        if (code == null || code.isBlank()) return null;
        String readable = code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        String shared = t.getOrCode("cds.enum." + code, readable);
        return t.getOrCode("pdf.record.enum." + code, shared);
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String code(String v) {
        return v == null || v.isBlank() ? "" : " (" + v + ")";
    }

    /** Joins the non-blank parts; a row never shows "null" or a dangling separator. */
    static String join(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) kept.add(part.trim());
        }
        return String.join(separator, kept);
    }

    /**
     * A flowing page cursor over PDFBox: headings, single lines and wrapped paragraphs, with a
     * new page whenever the next line would cross the bottom margin. Closes the open content
     * stream on every page break and on {@link #finish()}.
     */
    static final class Pages implements AutoCloseable {
        private static final float MARGIN = 50f;
        private static final float LINE = 12f;
        private static final int WRAP_AT = 100;
        private static final PDFont BODY = PDType1Font.HELVETICA;
        private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;

        private final PDDocument doc = new PDDocument();
        private final PdfLabels labels;
        private PDPageContentStream cs;
        private float y;
        private int pageNumber;

        Pages(PdfLabels labels) throws IOException {
            this.labels = labels;
            newPage();
        }

        void title(String text) throws IOException {
            line(BOLD, 16, text);
            y -= 6;
        }

        void heading(String text) throws IOException {
            ensure(LINE * 3);
            y -= 8;
            line(BOLD, 11, text);
            cs.moveTo(MARGIN, y + 3);
            cs.lineTo(PDRectangle.LETTER.getWidth() - MARGIN, y + 3);
            cs.stroke();
            y -= 4;
        }

        void field(String label, String value) throws IOException {
            if (value != null && !value.isBlank()) wrapped(labels.get("pdf.record.field", label, value));
        }

        void text(String text) throws IOException {
            wrapped(text);
        }

        <T> void rows(List<T> rows, Function<T, String> render) throws IOException {
            if (rows == null || rows.isEmpty()) {
                text(labels.get("pdf.record.none"));
                return;
            }
            for (T row : rows) {
                String rendered = render.apply(row);
                if (rendered != null && !rendered.isBlank()) wrapped("- " + rendered);
            }
        }

        void wrapped(String text) throws IOException {
            String rest = text == null ? "" : text;
            boolean first = true;
            while (!rest.isEmpty()) {
                int cut = rest.length() <= WRAP_AT ? rest.length() : breakPoint(rest);
                line(BODY, 9, (first ? "" : "    ") + rest.substring(0, cut).trim());
                rest = rest.substring(cut).trim();
                first = false;
            }
        }

        void gap() {
            y -= LINE;
        }

        byte[] finish() throws IOException {
            closeStream();
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }

        @Override
        public void close() throws IOException {
            closeStream();
            doc.close();
        }

        private static int breakPoint(String rest) {
            int space = rest.lastIndexOf(' ', WRAP_AT);
            return space > WRAP_AT / 2 ? space : WRAP_AT;
        }

        private void line(PDFont font, int size, String text) throws IOException {
            ensure(LINE);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(sanitize(font, text));
            cs.endText();
            y -= LINE;
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) newPage();
        }

        private void newPage() throws IOException {
            closeStream();
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNumber++;
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        private void closeStream() throws IOException {
            if (cs == null) return;
            cs.beginText();
            cs.setFont(BODY, 8);
            cs.newLineAtOffset(MARGIN, MARGIN / 2);
            cs.showText(sanitize(BODY, labels.get("pdf.record.page", String.valueOf(pageNumber))));
            cs.endText();
            cs.close();
            cs = null;
        }

        /** Standard-14 fonts carry WinAnsi only; a glyph outside it becomes '?', not an exception. */
        private static String sanitize(PDFont font, String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r' || c == '\t') {
                    out.append(' ');
                    continue;
                }
                try {
                    font.encode(String.valueOf(c));
                    out.append(c);
                } catch (IllegalArgumentException | IOException e) {
                    out.append('?');
                }
            }
            return out.toString();
        }
    }
}
