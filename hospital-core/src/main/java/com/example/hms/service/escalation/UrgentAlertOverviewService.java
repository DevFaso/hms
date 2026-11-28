package com.example.hms.service.escalation;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.mapper.InBasketMapper;
import com.example.hms.model.CriticalResultIncident;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Patient;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertIncidentDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertPatientMarkDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertsMineDTO;
import com.example.hms.repository.CriticalResultIncidentRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UrgentAlertIncidentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * QA D4100, D4101, D4102 (r81): what the escalation ladder is waiting on, read where staff look first. The ladder
 * itself worked (steps, incident, acknowledgement), but the screens hid it: a dashboard said « 0 alertes » beside
 * twenty unread urgent items, the patient's row and bed carried no mark, and the incident the last step opens
 * could be read by nobody.
 *
 * <p>Every read is of ONE hospital, the caller's acting one, and is bounded by the alert's own hospital column.
 * <ul>
 *   <li>{@link #mine}: the reader's own alerts to acknowledge, and those she documented that still wait.</li>
 *   <li>{@link #patientMarks}: per patient, how many alerts wait and since when. No clinical text.</li>
 *   <li>{@link #incidents}: the register of unresolved alerts. A hospital administrator reads when, how long,
 *       which kind of record, the steps taken, who acknowledged and when it closed, and never the patient: the
 *       administrator is off the chart. The médecin-chef reads the same with the patient.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UrgentAlertOverviewService {

    /** The register shows every open incident, and the closed ones of this many days. */
    static final int REGISTER_DAYS = 30;
    /** The label of the kind of record behind an incident: {@code urgentIncident.source.<SOURCE>}. */
    public static final String SOURCE_LABEL_PREFIX = "urgentIncident.source.";
    /** The register's source of a critical laboratory result (its own incident table, the same ladder). */
    public static final String SOURCE_LAB_RESULT = "LAB_RESULT";
    static final String REFUSAL_KEY = "urgentIncident.readers";

    /** Every source a line of the register may name: each has its label in every language. */
    public static final List<String> REGISTER_SOURCES = registerSources();

    private static List<String> registerSources() {
        List<String> sources = new ArrayList<>(
            com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_TYPES);
        sources.add(UrgentAlertIncident.SOURCE_URGENT_ALERT);
        sources.add(UrgentAlertIncident.SOURCE_IMAGING_REPORT);
        sources.add(UrgentAlertIncident.SOURCE_PRO_RESPONSE);
        sources.add(SOURCE_LAB_RESULT);
        return List.copyOf(sources);
    }

    private final ObstetricAlertEscalationRepository escalations;
    private final InBasketItemRepository inBasketItems;
    private final UrgentAlertIncidentRepository incidents;
    private final CriticalResultIncidentRepository labIncidents;
    private final PatientRepository patients;
    private final UserRepository users;
    private final UrgentEscalationSupport ladder;
    private final InBasketMapper inBasketMapper;
    private final MessageSource messageSource;
    private final Clock clock;

    /** The reader's urgent alerts at the hospital: those to acknowledge, and those she documented. */
    @Transactional(readOnly = true)
    public UrgentAlertsMineDTO mine(UUID userId, UUID hospitalId) {
        if (userId == null || hospitalId == null) {
            return new UrgentAlertsMineDTO(0, 0, null, List.of());
        }
        List<InBasketItem> open = inBasketItems.findOpenUrgentAlertItems(userId, hospitalId,
            ObstetricAlertEscalationWorker.REFERENCE_TYPE);
        List<ObstetricAlertEscalation> documented = escalations.findOpenDocumentedBy(hospitalId, userId);
        LocalDateTime oldest = null;
        for (InBasketItem item : open) {
            oldest = earlier(oldest, item.getCreatedAt());
        }
        for (ObstetricAlertEscalation row : documented) {
            oldest = earlier(oldest, row.getFirstNoticeFiledAt());
        }
        List<InBasketItemDTO> items = open.stream().map(inBasketMapper::toDto).toList();
        return new UrgentAlertsMineDTO(open.size(), documented.size(), oldest, items);
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isBefore(a) ? b : a;
    }

    /** Per patient of the hospital with an alert nobody has acknowledged: how many, and the oldest. */
    @Transactional(readOnly = true)
    public List<UrgentAlertPatientMarkDTO> patientMarks(UUID hospitalId) {
        if (hospitalId == null) {
            return List.of();
        }
        List<UrgentAlertPatientMarkDTO> marks = new ArrayList<>();
        for (Object[] row : escalations.findOpenPatientMarks(hospitalId)) {
            marks.add(new UrgentAlertPatientMarkDTO((UUID) row[0], ((Number) row[1]).longValue(),
                (LocalDateTime) row[2]));
        }
        return marks;
    }

    /**
     * The register of unresolved urgent alerts of the hospital, the newest first.
     *
     * @param administrator the caller holds the hospital administrator's role there
     * @param doctor        the caller holds a doctor's role there; only the médecin-chef among them reads it
     */
    @Transactional(readOnly = true)
    public List<UrgentAlertIncidentDTO> incidents(UUID userId, UUID hospitalId, boolean administrator,
                                                  boolean doctor) {
        boolean lead = doctor && isMedicalLead(userId, hospitalId);
        if (hospitalId == null || (!administrator && !lead)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(REFUSAL_KEY));
        }
        Locale locale = LocaleContextHolder.getLocale();
        LocalDateTime since = LocalDateTime.now(clock).minusDays(REGISTER_DAYS);
        List<UrgentAlertIncident> register = incidents.findRegister(hospitalId, since);
        Map<UUID, ObstetricAlertEscalation> rows = new HashMap<>();
        Set<UUID> alertIds = new LinkedHashSet<>();
        Set<UUID> userIds = new LinkedHashSet<>();
        for (UrgentAlertIncident incident : register) {
            if (UrgentAlertIncident.SOURCE_URGENT_ALERT.equals(incident.getSourceType())) {
                alertIds.add(incident.getSourceId());
            }
            if (incident.getResolvedByUserId() != null) {
                userIds.add(incident.getResolvedByUserId());
            }
        }
        for (ObstetricAlertEscalation row : escalations.findAllById(alertIds)) {
            // The hospital of the row itself, never only the incident's: nothing of another hospital is described.
            if (hospitalId.equals(row.getHospitalId())) {
                rows.put(row.getId(), row);
            }
        }
        Map<UUID, String> names = new HashMap<>();
        for (User user : users.findAllById(userIds)) {
            names.put(user.getId(), UrgentAlertReaders.displayName(user));
        }
        Map<UUID, String> patientNames = new HashMap<>();
        if (lead) {
            for (ObstetricAlertEscalation row : rows.values()) {
                patientNames.computeIfAbsent(row.getPatientId(), id -> patients.findByIdUnscoped(id)
                    .map(Patient::getFullName).orElse(null));
            }
        }
        List<UrgentAlertIncidentDTO> lines = new ArrayList<>();
        Map<String, String> caseKeys = new HashMap<>();
        for (UrgentAlertIncident incident : register) {
            ObstetricAlertEscalation row = rows.get(incident.getSourceId());
            String source = row != null ? row.getSourceType() : incident.getSourceType();
            UUID patientId = lead && row != null ? row.getPatientId() : null;
            // One case per patient and day, keyed by the id of the first incident of the case in this register:
            // an administrator reads which lines belong together, never whose they are. Review of #1393 (F3): the
            // key is never derived from the patient, so nothing outside the server can recompute it.
            String caseKey = row == null || incident.getOpenedAt() == null ? incident.getId().toString()
                : caseKeys.computeIfAbsent(row.getPatientId() + ":" + incident.getOpenedAt().toLocalDate(),
                    group -> incident.getId().toString());
            lines.add(new UrgentAlertIncidentDTO(incident.getId(), caseKey, source, sourceLabel(source, locale),
                patientId, patientId == null ? null : patientNames.get(patientId),
                row == null ? null : row.getFirstNoticeFiledAt(), incident.getOpenedAt(),
                incident.getMinutesUnacknowledged(), incident.getRecipientsTold(),
                row == null ? null : Integer.valueOf(row.getEscalationLevel()),
                row == null ? null : row.getLastEscalationAt(),
                incident.getResolvedByUserId() == null ? null : names.get(incident.getResolvedByUserId()),
                incident.getResolvedAt(), incident.getResolution()));
        }
        for (CriticalResultIncident incident : labIncidents.findRegister(hospitalId, since)) {
            lines.add(new UrgentAlertIncidentDTO(incident.getId(), incident.getId().toString(), SOURCE_LAB_RESULT,
                sourceLabel(SOURCE_LAB_RESULT, locale), null, null, null, incident.getOpenedAt(),
                incident.getMinutesUnacknowledged(), incident.getRecipientsTold(), null, null,
                incident.getResolvedByDisplay(), incident.getResolvedAt(), incident.getResolution()));
        }
        lines.sort(Comparator.comparing(UrgentAlertIncidentDTO::openedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return lines;
    }

    private String sourceLabel(String source, Locale locale) {
        return messageSource.getMessage(SOURCE_LABEL_PREFIX + source, null, source, locale);
    }

    /** The médecin-chef of the hospital, as the ladder names that person for its own second step. */
    private boolean isMedicalLead(UUID userId, UUID hospitalId) {
        if (userId == null || hospitalId == null) {
            return false;
        }
        String username = users.findById(userId).map(User::getUsername).orElse(null);
        return username != null && ladder.medicalLead(hospitalId).stream()
            .filter(Objects::nonNull).anyMatch(lead -> lead.equalsIgnoreCase(username));
    }
}
