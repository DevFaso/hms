package com.example.hms.validation;

import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.repository.DeliveryRecordRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link RecentDeliveryOverrideGiven} check. The patient (and, for a
 * delivery, the episode) come from the request path the body is read for;
 * outside a request, or without the repository (a test slice, a validator
 * built by hand), it says nothing and the service check decides. Same window
 * and same queries as {@code LaborServiceImpl#resolveRecentDeliveryOverride}.
 */
public class RecentDeliveryOverrideGivenValidator implements ConstraintValidator<RecentDeliveryOverrideGiven, Object> {

    /** QA D214: twenty weeks; {@code LaborServiceImpl} reads the same constant. */
    public static final int MIN_DAYS_BETWEEN_DELIVERIES = 140;
    static final String FIELD = "recentDeliveryOverrideReason";

    private final ObjectProvider<DeliveryRecordRepository> deliveries;

    /** Built by Spring's validator factory, with the repository when the context has one. */
    @org.springframework.beans.factory.annotation.Autowired
    public RecentDeliveryOverrideGivenValidator(ObjectProvider<DeliveryRecordRepository> deliveries) {
        this.deliveries = deliveries;
    }

    /** Built by a plain Bean Validation factory: no repository, so the service check decides. */
    public RecentDeliveryOverrideGivenValidator() {
        this.deliveries = null;
    }

    @Override
    public boolean isValid(Object body, ConstraintValidatorContext context) {
        if (!(body instanceof LaborEpisodeRequestDTO) && !(body instanceof DeliveryRecordRequestDTO)) {
            return true;
        }
        String reason = body instanceof LaborEpisodeRequestDTO episode
            ? episode.getRecentDeliveryOverrideReason()
            : ((DeliveryRecordRequestDTO) body).getRecentDeliveryOverrideReason();
        if (reason != null && !reason.isBlank()) {
            return true;
        }
        DeliveryRecordRepository repository = deliveries == null ? null : deliveries.getIfAvailable();
        Map<String, String> path = pathVariables();
        UUID patientId = uuid(path.get("patientId"));
        if (repository == null || patientId == null || !recentBirthOnRecord(body, repository, patientId, path)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
            .addPropertyNode(FIELD)
            .addConstraintViolation();
        return false;
    }

    private static boolean recentBirthOnRecord(Object body, DeliveryRecordRepository repository, UUID patientId,
                                               Map<String, String> path) {
        if (body instanceof LaborEpisodeRequestDTO) {
            LocalDateTime since = LocalDateTime.now(ZoneId.systemDefault()).minusDays(MIN_DAYS_BETWEEN_DELIVERIES);
            return repository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(patientId, since);
        }
        LocalDateTime birthAt = ((DeliveryRecordRequestDTO) body).getBirthDateTime();
        UUID episodeId = uuid(path.get("episodeId"));
        if (birthAt == null || episodeId == null) {
            return false;
        }
        return repository.existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(patientId, episodeId,
            birthAt.minusDays(MIN_DAYS_BETWEEN_DELIVERIES), birthAt.plusDays(MIN_DAYS_BETWEEN_DELIVERIES));
    }

    private static Map<String, String> pathVariables() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object variables = attributes == null ? null
            : attributes.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (!(variables instanceof Map<?, ?> map)) {
            return Map.of();
        }
        java.util.HashMap<String, String> out = new java.util.HashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out;
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
