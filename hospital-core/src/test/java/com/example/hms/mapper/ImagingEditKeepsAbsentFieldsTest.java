package com.example.hms.mapper;

import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1822 class (r51): a write that maps a whole request onto the entity
 * erases every field the caller left out. The status write erased the
 * schedule; the same shape sat in the order edit (the portal's form carries
 * six fields, the mapper wrote thirty) and in a report revision (the PACS
 * UIDs, the performed time and the dose are on no form).
 *
 * <p>Read by reflection, so a field added to either request later is covered
 * without touching this test: it is either one of the form's own fields
 * (listed here, replaced, a blank one clears) or it is kept when absent.
 */
@DisplayName("D1822: an imaging edit keeps every field the request leaves out")
class ImagingEditKeepsAbsentFieldsTest {

    /** The portal edit form's fields: replaced, so clearing one works. */
    private static final Set<String> ORDER_FORM_FIELDS = Set.of(
        "patientId", "hospitalId", "modality", "studyType", "bodyRegion", "laterality", "priority",
        "clinicalQuestion");
    /** Request-only fields with no entity column of the same name. */
    private static final Set<String> ORDER_REQUEST_ONLY = Set.of("saveAsDraft", "duplicateLookbackDays",
        // set from the session at create, never by an edit (QA D302)
        "orderingProviderName", "orderingProviderNpi");

    /** The authoring form's fields: replaced, so clearing one works. */
    private static final Set<String> REPORT_FORM_FIELDS = Set.of(
        "accessionNumber", "modality", "bodyRegion", "reportTitle", "technique", "findings", "impression",
        "recommendations", "comparisonStudies", "contrastAdministered", "contrastDetails");
    /** Handled outside the mapper (associations, status, flag) or by their own merge (lists). */
    private static final Set<String> REPORT_HANDLED_ELSEWHERE = Set.of(
        "imagingOrderId", "departmentId", "performedByStaffId", "interpretingProviderId", "reportStatus",
        "criticalFinding", "measurements", "attachments",
        // kept when null since the mapper was written
        "reportNumber");

    @Test
    void anOrderEditKeepsEveryFieldItsFormDoesNotCarry() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Field requestField : ImagingOrderRequestDTO.class.getDeclaredFields()) {
            String name = requestField.getName();
            if (java.lang.reflect.Modifier.isStatic(requestField.getModifiers())
                    || ORDER_FORM_FIELDS.contains(name) || ORDER_REQUEST_ONLY.contains(name)) {
                continue;
            }
            Field entityField = field(ImagingOrder.class, name);
            ImagingOrder order = new ImagingOrder();
            Object stored = sample(entityField.getType());
            entityField.set(order, stored);

            new ImagingOrderMapper().updateEntityFromRequest(order, orderForm());

            assertThat(entityField.get(order)).as("ImagingOrder.%s after an edit that left it out", name)
                .isEqualTo(stored);
            checked.add(name);
        }
        assertThat(checked).contains("scheduledDate", "scheduledTime", "appointmentLocation", "workflowNotes",
            "requiresAuthorization", "authorizationNumber", "hasContrastAllergy", "contrastAllergyDetails");
    }

    @Test
    void anOrderEditStillReplacesItsOwnFields() {
        ImagingOrder order = new ImagingOrder();
        order.setBodyRegion("Thorax");
        order.setClinicalQuestion("Toux");
        order.setLaterality(ImagingLaterality.LEFT);
        ImagingOrderRequestDTO edit = orderForm();
        edit.setBodyRegion(null);
        edit.setClinicalQuestion(null);
        edit.setLaterality(null);

        new ImagingOrderMapper().updateEntityFromRequest(order, edit);

        assertThat(order.getBodyRegion()).isNull();
        assertThat(order.getClinicalQuestion()).isNull();
        assertThat(order.getLaterality()).isNull();
        assertThat(order.getStudyType()).isEqualTo("Radiographie thorax");
    }

    @Test
    void anOrderEditAppliesAFieldItDoesSend() {
        ImagingOrder order = new ImagingOrder();
        order.setScheduledDate(LocalDate.of(2026, 11, 21));
        ImagingOrderRequestDTO edit = orderForm();
        edit.setScheduledDate(LocalDate.of(2026, 11, 28));

        new ImagingOrderMapper().updateEntityFromRequest(order, edit);

        assertThat(order.getScheduledDate()).isEqualTo(LocalDate.of(2026, 11, 28));
    }

    @Test
    void aReportRevisionKeepsEveryFieldItsFormDoesNotCarry() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Field requestField : ImagingReportUpsertRequestDTO.class.getDeclaredFields()) {
            String name = requestField.getName();
            if (java.lang.reflect.Modifier.isStatic(requestField.getModifiers())
                    || REPORT_FORM_FIELDS.contains(name) || REPORT_HANDLED_ELSEWHERE.contains(name)) {
                continue;
            }
            Field entityField = field(ImagingReport.class, name);
            ImagingReport report = new ImagingReport();
            Object stored = sample(entityField.getType());
            entityField.set(report, stored);

            new ImagingReportMapper().updateReportFromRequest(report, reportForm());

            assertThat(entityField.get(report)).as("ImagingReport.%s after a revision that left it out", name)
                .isEqualTo(stored);
            checked.add(name);
        }
        assertThat(checked).contains("studyInstanceUid", "seriesInstanceUid", "pacsViewerUrl", "performedAt",
            "completedAt", "radiationDoseMgy", "externalSystemName", "externalReportId");
    }

    @Test
    void aReportRevisionStillClearsAFormFieldLeftBlank() {
        ImagingReport report = new ImagingReport();
        report.setTechnique("PA");
        report.setRecommendations("Contrôle");
        ImagingReportUpsertRequestDTO revision = reportForm();

        new ImagingReportMapper().updateReportFromRequest(report, revision);

        assertThat(report.getTechnique()).isNull();
        assertThat(report.getRecommendations()).isNull();
        assertThat(report.getFindings()).isEqualTo("Pas d'anomalie");
    }

    /** What the portal's edit form sends: its own fields only. */
    private static ImagingOrderRequestDTO orderForm() {
        ImagingOrderRequestDTO dto = new ImagingOrderRequestDTO();
        dto.setPatientId(UUID.randomUUID());
        dto.setHospitalId(UUID.randomUUID());
        dto.setModality(ImagingModality.XRAY);
        dto.setStudyType("Radiographie thorax");
        dto.setBodyRegion("Thorax");
        dto.setPriority(ImagingOrderPriority.ROUTINE);
        dto.setLaterality(ImagingLaterality.NOT_APPLICABLE);
        dto.setClinicalQuestion("Toux");
        return dto;
    }

    /** What the portal's authoring form sends on a revision, blanks trimmed to absent. */
    private static ImagingReportUpsertRequestDTO reportForm() {
        ImagingReportUpsertRequestDTO dto = new ImagingReportUpsertRequestDTO();
        dto.setModality(ImagingModality.XRAY);
        dto.setReportTitle("Radiographie thorax");
        dto.setFindings("Pas d'anomalie");
        dto.setImpression("Normal");
        dto.setContrastAdministered(false);
        return dto;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(type.getSimpleName() + "." + name
            + ": a request field with no entity field of that name; list it with why");
    }

    private static Object sample(Class<?> type) {
        if (type == String.class) {
            return "stored value";
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 11, 21);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.of(2026, 11, 21, 10, 15);
        }
        if (type == UUID.class) {
            return UUID.fromString("00000000-0000-0000-0000-000000001822");
        }
        if (type == BigDecimal.class) {
            return new BigDecimal("1.5");
        }
        if (type == Integer.class || type == int.class) {
            return 7;
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new IllegalStateException("no sample for " + type + "; add one");
    }
}
