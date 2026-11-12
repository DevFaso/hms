package com.example.hms.architecture;

import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.PatientTreatmentConsent;
import com.example.hms.model.discharge.DischargeSummary;
import com.example.hms.payload.dto.AdmissionDischargeRequestDTO;
import com.example.hms.payload.dto.AdmissionRequestDTO;
import com.example.hms.payload.dto.AdmissionUpdateRequestDTO;
import com.example.hms.payload.dto.CheckInRequestDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1652 class: the discharge dialog let a doctor type 3 000 characters of
 * instructions, the column holds 2 000, and the 2 001st character came back
 * as a generic "data constraint" refusal that named no field. The diagnosis,
 * meanwhile, was refused at 2 000 with a message that said 5 000.
 *
 * <p>For the admission, discharge and visit (check-in, checkout) dialogs, one
 * limit per field, everywhere:
 * <ul>
 *   <li>the portal {@code maxlength} equals the request DTO's {@code @Size};</li>
 *   <li>the {@code @Size} is no larger than the column the text is stored in;</li>
 *   <li>the {@code @Size} has its own message key, present in EN/FR/ES, that
 *       states that same number (so the refusal names the field and the limit).</li>
 * </ul>
 * And, for each request DTO of those flows, every text field stored in a
 * same-named sized column carries a {@code @Size} no larger than the column.
 */
class DialogTextLimitGuardTest {

    private static final String ADMISSIONS = "hospital-portal/src/app/admissions/admissions.html";
    private static final String CHECKIN = "hospital-portal/src/app/reception/checkin-dialog/checkin-dialog.component.html";
    private static final String CHECKOUT = "hospital-portal/src/app/checkout/checkout-dialog/checkout-dialog.component.html";

    /** One dialog text field: where it is typed, validated and stored. */
    private record Limit(String html, String elementId, Class<?> dto, String dtoField,
                         Class<?> entity, String entityField, String bundle) { }

    private static final List<Limit> LIMITS = List.of(
        new Limit(ADMISSIONS, "discharge-instructions", AdmissionDischargeRequestDTO.class, "dischargeInstructions",
            Admission.class, "dischargeInstructions", "clinical"),
        new Limit(ADMISSIONS, "discharge-summary", AdmissionDischargeRequestDTO.class, "dischargeSummary",
            Admission.class, "dischargeSummary", "clinical"),
        // Stored on the stay's encounter, truncated there to 2000 (AdmissionStayEncounterService).
        new Limit(ADMISSIONS, "discharge-diagnosis", AdmissionDischargeRequestDTO.class, "dischargeDiagnosis",
            null, null, "clinical"),
        new Limit(ADMISSIONS, "adm-complaint", AdmissionRequestDTO.class, "chiefComplaint",
            Admission.class, "chiefComplaint", "clinical"),
        new Limit(ADMISSIONS, "adm-notes", AdmissionRequestDTO.class, "admissionNotes",
            Admission.class, "admissionNotes", "clinical"),
        new Limit(ADMISSIONS, "adm-roomBed", AdmissionRequestDTO.class, "roomBed",
            Admission.class, "roomBed", "clinical"),
        new Limit(CHECKIN, "chiefComplaint", CheckInRequestDTO.class, "chiefComplaint",
            Encounter.class, "chiefComplaint", "patient"),
        new Limit(CHECKIN, "checkinNotes", CheckInRequestDTO.class, "notes",
            Encounter.class, "notes", "patient"),
        new Limit(CHECKIN, "consentSignedName", CheckInRequestDTO.class, "consentSignedName",
            PatientTreatmentConsent.class, "signedName", "patient"),
        new Limit(CHECKIN, "copayReference", CheckInRequestDTO.class, "copayReference",
            null, null, "patient"),
        new Limit(CHECKOUT, "checkout-rx", CheckOutRequestDTO.class, "prescriptionSummary",
            DischargeSummary.class, "prescriptionSummary", "clinical"),
        new Limit(CHECKOUT, "checkout-referrals", CheckOutRequestDTO.class, "referralSummary",
            DischargeSummary.class, "referralSummary", "clinical"),
        new Limit(CHECKOUT, "checkout-education", CheckOutRequestDTO.class, "patientEducationMaterials",
            DischargeSummary.class, "patientEducationProvided", "clinical"));

    /** Request DTO → the entity its text fields are stored on under the same names. */
    private static final List<Class<?>[]> STORED_AS = List.of(
        new Class<?>[] {AdmissionRequestDTO.class, Admission.class},
        new Class<?>[] {AdmissionUpdateRequestDTO.class, Admission.class},
        new Class<?>[] {AdmissionDischargeRequestDTO.class, Admission.class},
        new Class<?>[] {CheckInRequestDTO.class, Encounter.class});

    @Test
    void everyDialogTextFieldHasOneLimit() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Limit limit : LIMITS) {
            String where = limit.dto().getSimpleName() + "." + limit.dtoField();
            Size size = limit.dto().getDeclaredField(limit.dtoField()).getAnnotation(Size.class);
            if (size == null) {
                problems.add(where + ": no @Size");
                continue;
            }
            Integer maxlength = portalMaxlength(limit.html(), limit.elementId());
            if (maxlength == null || maxlength != size.max()) {
                problems.add(where + ": portal #" + limit.elementId() + " maxlength " + maxlength
                    + " but @Size max " + size.max());
            }
            if (limit.entity() != null) {
                int column = columnLength(limit.entity().getDeclaredField(limit.entityField()));
                if (column > 0 && size.max() > column) {
                    problems.add(where + ": @Size max " + size.max() + " exceeds the column ("
                        + column + ")");
                }
            }
            problems.addAll(messageProblems(where, size, limit.bundle()));
        }
        assertThat(problems)
            .as("a dialog text limit that the portal, the DTO, the column and the message disagree on (QA D1652)")
            .isEmpty();
    }

    @Test
    void everyStoredTextFieldIsSizedWithinItsColumn() {
        List<String> problems = new ArrayList<>();
        for (Class<?>[] pair : STORED_AS) {
            for (Field field : pair[0].getDeclaredFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                Field stored;
                try {
                    stored = pair[1].getDeclaredField(field.getName());
                } catch (NoSuchFieldException notStored) {
                    continue;
                }
                int column = columnLength(stored);
                if (column <= 0) {
                    continue;
                }
                Size size = field.getAnnotation(Size.class);
                if (size == null || size.max() > column) {
                    problems.add(pair[0].getSimpleName() + "." + field.getName() + " -> "
                        + pair[1].getSimpleName() + " column " + column + ", @Size "
                        + (size == null ? "missing" : size.max()));
                }
            }
        }
        assertThat(problems)
            .as("a request text field longer than its column: refused as an unnamed data error (QA D1652)")
            .isEmpty();
    }

    /** The declared column length, or 0 for an unsized ({@code TEXT}) column. */
    private static int columnLength(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column == null || !column.columnDefinition().isEmpty()) {
            return column == null ? 255 : 0;
        }
        return column.length();
    }

    private static Integer portalMaxlength(String html, String elementId) throws IOException {
        String source = Files.readString(Path.of("..").resolve(html), StandardCharsets.UTF_8);
        Matcher tag = Pattern.compile("<(?:textarea|input)\\b[^>]*?\\bid=\"" + Pattern.quote(elementId) + "\"[^>]*>",
            Pattern.DOTALL).matcher(source);
        if (!tag.find()) {
            return null;
        }
        Matcher max = Pattern.compile("\\bmaxlength=\"(\\d+)\"").matcher(tag.group());
        return max.find() ? Integer.valueOf(max.group(1)) : null;
    }

    private static List<String> messageProblems(String where, Size size, String bundle) throws IOException {
        List<String> problems = new ArrayList<>();
        String message = size.message();
        if (!message.startsWith("{") || !message.endsWith("}") || message.startsWith("{jakarta.")) {
            problems.add(where + ": @Size has no message key of its own");
            return problems;
        }
        String key = message.substring(1, message.length() - 1);
        for (String locale : List.of("", "_en", "_fr", "_es")) {
            String resource = "/i18n/messages-" + bundle + locale + ".properties";
            Properties props = new Properties();
            try (InputStream in = DialogTextLimitGuardTest.class.getResourceAsStream(resource)) {
                assertThat(in).as(resource).isNotNull();
                props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String text = props.getProperty(key);
            if (text == null) {
                problems.add(where + ": " + key + " missing from " + resource);
            } else if (!Pattern.compile("\\b" + size.max() + "\\b").matcher(text).find()) {
                problems.add(where + ": " + key + " in " + resource + " does not state " + size.max() + ": " + text);
            }
        }
        return problems;
    }
}
