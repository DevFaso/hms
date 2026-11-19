package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.ImagingReportStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * r63 (imaging completion): what the radiologist needs beside the order, read
 * only from data the record already holds. Nothing here is graded or judged:
 * a value is shown as recorded, with its date, or is absent.
 *
 * @param ageYears              whole years at today's date, from the date of birth; null without one
 * @param sex                   the patient's recorded sex, as recorded
 * @param pregnancy             the latest maternal history at this hospital, or null when none
 * @param requiresPregnancyTest the order's own flag
 * @param creatinine            the latest released, current creatinine result at this hospital, or null
 * @param priorStudies          the patient's other imaging studies at this hospital, newest first
 */
public record ImagingReadingContextDTO(
    UUID imagingOrderId,
    UUID patientId,
    LocalDate dateOfBirth,
    Integer ageYears,
    String sex,
    Pregnancy pregnancy,
    Boolean requiresPregnancyTest,
    Creatinine creatinine,
    List<PriorStudy> priorStudies
) {

    /**
     * @param ongoing the recorded due date is today or later; a fact about the date, not a diagnosis
     */
    public record Pregnancy(
        LocalDate estimatedDueDate,
        LocalDate lastMenstrualPeriod,
        LocalDateTime recordedAt,
        boolean ongoing
    ) {
    }

    /**
     * @param corrected this value corrects an earlier released one (QA D1016: said wherever it is shown)
     */
    public record Creatinine(
        String testName,
        String value,
        String unit,
        LocalDateTime resultDate,
        AbnormalFlag abnormalFlag,
        boolean corrected
    ) {
    }

    /**
     * @param reportId the latest signed report of that study, or null when it has none
     */
    public record PriorStudy(
        UUID imagingOrderId,
        ImagingModality modality,
        String studyType,
        String bodyRegion,
        LocalDateTime orderedAt,
        ImagingOrderStatus status,
        UUID reportId,
        ImagingReportStatus reportStatus,
        LocalDateTime signedAt,
        String impression
    ) {
    }
}
