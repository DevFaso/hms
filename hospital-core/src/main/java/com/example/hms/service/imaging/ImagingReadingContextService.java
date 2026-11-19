package com.example.hms.service.imaging;

import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.LabResult;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.imaging.ImagingReadingContextDTO;
import com.example.hms.payload.dto.imaging.ImagingReadingContextDTO.Creatinine;
import com.example.hms.payload.dto.imaging.ImagingReadingContextDTO.Pregnancy;
import com.example.hms.payload.dto.imaging.ImagingReadingContextDTO.PriorStudy;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.service.lab.ClinicalLabResultView;
import com.example.hms.service.lab.LabResultCorrections;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * r63 (imaging completion, "what's missing" item 3): the radiologist read
 * blind. The order drawer and the report editor now show, read-only, what the
 * record already holds about the patient of the order:
 * <ul>
 *   <li>age (from the date of birth) and recorded sex;</li>
 *   <li>the latest maternal history at this hospital (due date, last period),
 *       and the order's own "pregnancy test required" flag;</li>
 *   <li>the latest released, current creatinine at this hospital;</li>
 *   <li>every other imaging study of the patient at this hospital.</li>
 * </ul>
 * No value is graded, no threshold applied: a clinical rule (contrast and
 * renal function, pregnancy and radiation) is the radiologist's, not this
 * screen's.
 *
 * <p><b>Tenancy.</b> The order is read in the caller's active hospital (another
 * hospital's answers 404, as a missing id), and every other row is read at the
 * order's hospital only.
 */
@Service
@RequiredArgsConstructor
public class ImagingReadingContextService {

    private static final int CREATININE_CANDIDATES = 20;
    private static final String MSG_ORDER_NOT_FOUND = "imaging.order.notFound";

    /** Serum / plasma / blood creatinine (mass and molar). */
    static final Set<String> CREATININE_LOINC = Set.of("2160-0", "38483-4", "14682-9", "59826-8");

    /** A name that reads like creatinine but is another test: a clearance, a urine value, a ratio, an eGFR. */
    private static final List<String> NOT_SERUM_CREATININE =
        List.of("clair", "clear", "urin", "ratio", "rapport", "dfg", "gfr");

    private final ImagingOrderRepository orderRepository;
    private final ImagingReportRepository reportRepository;
    private final LabResultRepository labResultRepository;
    private final MaternalHistoryRepository maternalHistoryRepository;
    private final RoleValidator roleValidator;

    @Transactional(readOnly = true)
    public ImagingReadingContextDTO contextOf(UUID orderId) {
        ImagingOrder order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, orderId));
        UUID scope = roleValidator.requireActiveHospitalId();
        UUID hospitalId = order.getHospital() != null ? order.getHospital().getId() : null;
        if (scope != null && !scope.equals(hospitalId)) {
            throw new ResourceNotFoundException(MSG_ORDER_NOT_FOUND, orderId);
        }
        Patient patient = order.getPatient();
        if (patient == null || hospitalId == null) {
            return new ImagingReadingContextDTO(order.getId(), patient != null ? patient.getId() : null,
                null, null, null, null, order.getRequiresPregnancyTest(), null, List.of());
        }
        LocalDate today = LocalDate.now();
        LocalDate dob = patient.getDateOfBirth();
        Integer age = dob != null && !dob.isAfter(today) ? Period.between(dob, today).getYears() : null;
        return new ImagingReadingContextDTO(order.getId(), patient.getId(), dob, age, patient.getGender(),
            pregnancyOf(patient.getId(), hospitalId, today), order.getRequiresPregnancyTest(),
            creatinineOf(patient.getId(), hospitalId), priorStudiesOf(order, patient.getId(), hospitalId));
    }

    private Pregnancy pregnancyOf(UUID patientId, UUID hospitalId, LocalDate today) {
        return maternalHistoryRepository
            .findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(patientId, List.of(hospitalId))
            .filter(history -> dueDateOf(history) != null || history.getLastMenstrualPeriod() != null)
            .map(history -> {
                LocalDate due = dueDateOf(history);
                return new Pregnancy(due, history.getLastMenstrualPeriod(), history.getRecordedDate(),
                    due != null && !due.isBefore(today));
            })
            .orElse(null);
    }

    /** The ultrasound-dated term when one is recorded, the dates-based one otherwise. */
    private static LocalDate dueDateOf(MaternalHistory history) {
        return history.getEstimatedDueDateByUltrasound() != null
            ? history.getEstimatedDueDateByUltrasound()
            : history.getEstimatedDueDate();
    }

    private Creatinine creatinineOf(UUID patientId, UUID hospitalId) {
        // The clinical view (QA D740/D741): released and current rows only, in the query's order.
        return ClinicalLabResultView.releasedAndCurrent(labResultRepository.findReleasedCreatinineCandidates(
                patientId, hospitalId, CREATININE_LOINC, PageRequest.of(0, CREATININE_CANDIDATES))).stream()
            .filter(ImagingReadingContextService::isSerumCreatinine)
            .findFirst()
            .map(result -> new Creatinine(testNameOf(result), result.getResultValue(), LabResultMapper.unitOf(result),
                result.getResultDate(), result.getAbnormalFlag(), LabResultCorrections.isCorrection(result)))
            .orElse(null);
    }

    static boolean isSerumCreatinine(LabResult result) {
        String name = testNameOf(result);
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return NOT_SERUM_CREATININE.stream().noneMatch(lower::contains);
    }

    private static String testNameOf(LabResult result) {
        return result.getLabOrder() != null && result.getLabOrder().getLabTestDefinition() != null
            ? result.getLabOrder().getLabTestDefinition().getName()
            : null;
    }

    private List<PriorStudy> priorStudiesOf(ImagingOrder current, UUID patientId, UUID hospitalId) {
        // Every prior study here (QA CX-003: a server list is never cut at a fixed number).
        List<ImagingOrder> others = orderRepository
            .findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(patientId, List.of(hospitalId))
            .stream()
            .filter(order -> !Objects.equals(order.getId(), current.getId()))
            .toList();
        if (others.isEmpty()) {
            return List.of();
        }
        Map<UUID, ImagingReport> conclusion = reportRepository
            .findByImagingOrder_IdIn(others.stream().map(ImagingOrder::getId).toList()).stream()
            .filter(ImagingReport::isReleasedToPatient)
            .collect(Collectors.toMap(r -> r.getImagingOrder().getId(), r -> r,
                ImagingReadingContextService::conclusionOf));
        return others.stream().map(order -> {
            ImagingReport report = conclusion.get(order.getId());
            return new PriorStudy(order.getId(), order.getModality(), order.getStudyType(), order.getBodyRegion(),
                order.getOrderedAt(), order.getStatus(),
                report != null ? report.getId() : null,
                report != null ? report.getReportStatus() : null,
                report != null ? report.getSignedAt() : null,
                report != null ? report.getImpression() : null);
        }).toList();
    }

    /**
     * The study's conclusion among its signed reports: the latest signed read that is
     * not an addendum (QA D1930: the signed original stays the conclusion), else the latest one.
     */
    static ImagingReport conclusionOf(ImagingReport a, ImagingReport b) {
        Comparator<ImagingReport> order = Comparator
            .comparing((ImagingReport r) -> r.getReportStatus() != ImagingReportStatus.ADDENDUM)
            .thenComparing(r -> r.getReportVersion() == null ? 0 : r.getReportVersion());
        return order.compare(a, b) >= 0 ? a : b;
    }
}
