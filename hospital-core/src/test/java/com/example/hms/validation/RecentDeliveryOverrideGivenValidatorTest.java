package com.example.hms.validation;

import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.repository.DeliveryRecordRepository;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D1369 (round 31): the recent-delivery override reason (QA D214) is named
 * with every other refusal of a labour-episode or delivery body, not one
 * request later. The validator reads the patient and episode from the request
 * path, as Spring's validator factory builds it (with the repository).
 */
@DisplayName("D1369: a missing recent-delivery override reason is named at the edge, with the rest of the body")
class RecentDeliveryOverrideGivenValidatorTest {

    private final UUID patientId = UUID.randomUUID();
    private final UUID episodeId = UUID.randomUUID();
    private final DeliveryRecordRepository deliveries = mock(DeliveryRecordRepository.class);
    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("deliveryRecordRepository", deliveries);
        Configuration<?> configuration = Validation.byDefaultProvider().configure();
        ConstraintValidatorFactory fallback = configuration.getDefaultConstraintValidatorFactory();
        factory = configuration.constraintValidatorFactory(new ConstraintValidatorFactory() {
            @Override
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                if (key == RecentDeliveryOverrideGivenValidator.class) {
                    return key.cast(new RecentDeliveryOverrideGivenValidator(
                        beans.getBeanProvider(DeliveryRecordRepository.class)));
                }
                return fallback.getInstance(key);
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {
                fallback.releaseInstance(instance);
            }
        }).buildValidatorFactory();
        validator = factory.getValidator();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
            Map.of("patientId", patientId.toString(), "episodeId", episodeId.toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        factory.close();
    }

    @Test
    @DisplayName("an episode after a recent birth names the blank override reason with the missing onset")
    void anEpisodeNamesTheOverrideWithTheOnset() {
        when(deliveries.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(eq(patientId), any())).thenReturn(true);
        LaborEpisodeRequestDTO episode = new LaborEpisodeRequestDTO();
        episode.setRecentDeliveryOverrideReason("  ");
        assertThat(fields(episode)).containsExactlyInAnyOrder("laborOnsetAt", "recentDeliveryOverrideReason");
    }

    @Test
    @DisplayName("an override reason given, or no recent birth, is no refusal")
    void aReasonOrNoRecentBirthPasses() {
        LaborEpisodeRequestDTO episode = new LaborEpisodeRequestDTO();
        episode.setLaborOnsetAt(LocalDateTime.now().minusHours(2));
        when(deliveries.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(eq(patientId), any())).thenReturn(false);
        assertThat(fields(episode)).isEmpty();

        when(deliveries.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(eq(patientId), any())).thenReturn(true);
        episode.setRecentDeliveryOverrideReason("jumeau retenu");
        assertThat(fields(episode)).isEmpty();
    }

    @Test
    @DisplayName("a delivery within twenty weeks of another names the override, on the path's episode")
    void aDeliveryNamesTheOverride() {
        LocalDateTime birthAt = LocalDateTime.now().minusHours(1);
        when(deliveries.existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(patientId, episodeId,
            birthAt.minusDays(RecentDeliveryOverrideGivenValidator.MIN_DAYS_BETWEEN_DELIVERIES),
            birthAt.plusDays(RecentDeliveryOverrideGivenValidator.MIN_DAYS_BETWEEN_DELIVERIES))).thenReturn(true);
        DeliveryRecordRequestDTO delivery = new DeliveryRecordRequestDTO();
        delivery.setBirthDateTime(birthAt);
        assertThat(fields(delivery)).contains("recentDeliveryOverrideReason");
    }

    @Test
    @DisplayName("outside a request (no path), the validator says nothing and the service check decides")
    void outsideARequestTheServiceDecides() {
        RequestContextHolder.resetRequestAttributes();
        LaborEpisodeRequestDTO episode = new LaborEpisodeRequestDTO();
        episode.setLaborOnsetAt(LocalDateTime.now().minusHours(2));
        assertThat(fields(episode)).isEmpty();
        verify(deliveries, never()).existsByPatient_IdAndBirthDateTimeGreaterThanEqual(any(), any());
    }

    @Test
    @DisplayName("a plain Bean Validation factory (no repository) builds it and it says nothing")
    void aPlainFactoryBuildsIt() {
        try (ValidatorFactory plain = Validation.buildDefaultValidatorFactory()) {
            LaborEpisodeRequestDTO episode = new LaborEpisodeRequestDTO();
            episode.setLaborOnsetAt(LocalDateTime.now().minusHours(2));
            assertThat(plain.getValidator().validate(episode)).isEmpty();
        }
    }

    private Set<String> fields(Object body) {
        Set<String> out = new TreeSet<>();
        validator.validate(body).forEach(v -> out.add(v.getPropertyPath().toString()));
        return out;
    }
}
