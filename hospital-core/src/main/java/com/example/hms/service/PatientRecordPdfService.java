package com.example.hms.service;

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
import com.example.hms.security.context.HospitalContextHolder;
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
        UUID hospitalId = HospitalContextHolder.getContextOrEmpty().getActiveHospitalId();
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

        byte[] pdf = write(storyboard, chart, labels);
        emitAudit(patient, hospitalId, chart);
        return pdf;
    }

    // ---------------------------------------------------------------- layout

    private static byte[] write(PatientStoryboardDTO storyboard, ChartReviewDTO chart, PdfLabels t) {
        try (Pages pages = new Pages(t)) {
            writeIdentity(pages, storyboard, t);
            writeStoryboard(pages, storyboard, t);
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
        pages.field(t.get("pdf.record.field.sex"), p.getGender());
        pages.field(t.get("pdf.record.field.bloodType"), p.getBloodType());
    }

    private static void writeStoryboard(Pages pages, PatientStoryboardDTO storyboard, PdfLabels t) throws IOException {
        pages.heading(t.get("pdf.record.section.allergies"));
        pages.rows(storyboard.getAllergies(), a -> join(SEPARATOR,
            a.getAllergenDisplay(), a.getSeverity(), a.getReaction(), a.getVerificationStatus()));

        pages.heading(t.get("pdf.record.section.problems"));
        pages.rows(storyboard.getProblems(), pr -> join(SEPARATOR,
            pr.getProblemDisplay(), code(pr.getProblemCode()), pr.getStatus(),
            pr.isChronic() ? t.get("pdf.record.problem.chronic") : null,
            pr.getOnsetDate() != null ? t.get("pdf.record.problem.since", t.fmt(pr.getOnsetDate())) : null));

        pages.heading(t.get("pdf.record.section.activeEncounter"));
        PatientStoryboardDTO.ActiveEncounterDTO enc = storyboard.getActiveEncounter();
        if (enc == null) {
            pages.text(t.get("pdf.record.encounter.none"));
        } else {
            pages.text(join(SEPARATOR, enc.getEncounterType(), enc.getStatus(), t.fmt(enc.getEncounterDate()),
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
            pages.text(code.getStatus());
            pages.rows(code.getDirectives(), d -> join(SEPARATOR, d.getDirectiveType(), d.getStatus(),
                d.getEffectiveDate() != null ? t.get("pdf.record.directive.from", t.fmt(d.getEffectiveDate())) : null,
                d.getDescription()));
        }
    }

    private static void writeChart(Pages pages, ChartReviewDTO chart, PdfLabels t) throws IOException {
        pages.heading(t.get("pdf.record.section.encounters"));
        pages.rows(chart.getEncounters(), e -> join(SEPARATOR, t.fmt(e.getEncounterDate()), e.getEncounterType(),
            e.getStatus(), e.getDepartmentName(), e.getStaffFullName(), e.getChiefComplaint()));

        pages.heading(t.get("pdf.record.section.notes"));
        pages.rows(chart.getNotes(), n -> join(SEPARATOR, t.fmt(n.getDocumentedAt()), n.getTemplate(),
            n.getAuthorName(), t.get(n.isSigned() ? "pdf.record.note.signed" : "pdf.record.note.draft"),
            n.getPreview()));

        pages.heading(t.get("pdf.record.section.results"));
        pages.rows(chart.getResults(), r -> join(SEPARATOR, t.fmt(r.getResultDate()),
            safe(r.getTestName()) + code(r.getTestCode()),
            join(" ", r.getResultValue(), r.getResultUnit()), r.getAbnormalFlag(), r.getOrderingStaffName()));

        pages.heading(t.get("pdf.record.section.medications"));
        pages.rows(chart.getMedications(), m -> join(SEPARATOR, t.fmt(m.getCreatedAt()), m.getMedicationName(),
            m.getDosage(), join(" / ", m.getFrequency(), m.getRoute(), m.getDuration()), m.getStatus(),
            m.getPrescriberName(), m.isControlledSubstance() ? t.get("pdf.record.medication.controlled") : null));

        pages.heading(t.get("pdf.record.section.imaging"));
        pages.rows(chart.getImaging(), i -> join(SEPARATOR, t.fmt(i.getOrderedAt()),
            join(" ", i.getModality(), i.getStudyType(), i.getBodyRegion(), i.getLaterality()), i.getStatus(),
            i.getReportImpression() != null ? t.get("pdf.record.imaging.impression", i.getReportImpression()) : null));

        pages.heading(t.get("pdf.record.section.procedures"));
        pages.rows(chart.getProcedures(), pr -> join(SEPARATOR, t.fmt(pr.getOrderedAt()), pr.getProcedureName(),
            pr.getProcedureCategory(), pr.getUrgency(), pr.getStatus(), pr.getOrderingProviderName(),
            t.get(pr.isConsentObtained() ? "pdf.record.procedure.consentObtained"
                : "pdf.record.procedure.consentNotRecorded")));
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
