package com.example.hms.controller;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.ApplyLabSensitivityRecommendationsRequestDTO;
import com.example.hms.payload.dto.LabSensitivityRecommendationDTO;
import com.example.hms.service.LabTestDefinitionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy 2026-11-20 (provisional): the two endpoints behind « Appliquer les
 * catégories recommandées » - what they hand the service, and who may call
 * them (the lab director, or a super-admin; the service checks the hospital).
 */
@DisplayName("Policy 2026-11-20: the recommended sensitive categories endpoints")
class LabSensitivityRecommendationEndpointsTest {

    private final LabTestDefinitionService service = mock(LabTestDefinitionService.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final LabTestDefinitionController controller = new LabTestDefinitionController(service, validator);

    private final UUID definitionId = UUID.randomUUID();
    private final LabSensitivityRecommendationDTO hiv = new LabSensitivityRecommendationDTO(definitionId, "SRV",
        "Sérologie VIH", false, null, SensitivityCategory.HIV, "NAME", "Sérologie VIH", false, true);

    @Test
    @DisplayName("the list is the service's, marked provisional")
    void recommendations() {
        when(service.recommendedSensitivity()).thenReturn(List.of(hiv));

        ResponseEntity<ApiResponseWrapper<List<LabSensitivityRecommendationDTO>>> response =
            controller.sensitivityRecommendations();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).containsExactly(hiv);
        assertThat(response.getBody().getData().get(0).provisional()).isTrue();
    }

    @Test
    @DisplayName("applying hands the confirmed items to the service, untouched")
    void apply() {
        List<ApplyLabSensitivityRecommendationsRequestDTO.Item> confirmed = List.of(
            new ApplyLabSensitivityRecommendationsRequestDTO.Item(definitionId, SensitivityCategory.HIV));
        when(service.applyRecommendedSensitivity(confirmed)).thenReturn(List.of());

        ResponseEntity<ApiResponseWrapper<List<LabSensitivityRecommendationDTO>>> response =
            controller.applySensitivityRecommendations(new ApplyLabSensitivityRecommendationsRequestDTO(confirmed));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).applyRecommendedSensitivity(confirmed);
    }

    @Test
    @DisplayName("an empty confirmation, or an item with no test or no category, is invalid")
    void theRequestNamesWhatIsConfirmed() {
        assertThat(validator.validate(new ApplyLabSensitivityRecommendationsRequestDTO(List.of()))).isNotEmpty();
        assertThat(validator.validate(new ApplyLabSensitivityRecommendationsRequestDTO(null))).isNotEmpty();
        assertThat(validator.validate(new ApplyLabSensitivityRecommendationsRequestDTO(List.of(
            new ApplyLabSensitivityRecommendationsRequestDTO.Item(null, SensitivityCategory.HIV))))).isNotEmpty();
        assertThat(validator.validate(new ApplyLabSensitivityRecommendationsRequestDTO(List.of(
            new ApplyLabSensitivityRecommendationsRequestDTO.Item(definitionId, null))))).isNotEmpty();
        assertThat(validator.validate(new ApplyLabSensitivityRecommendationsRequestDTO(List.of(
            new ApplyLabSensitivityRecommendationsRequestDTO.Item(definitionId, SensitivityCategory.HIV))))).isEmpty();
    }

    @Test
    @DisplayName("both endpoints are the lab director's or a super-admin's")
    void declaredRoles() throws NoSuchMethodException {
        for (String handler : List.of("sensitivityRecommendations", "applySensitivityRecommendations")) {
            PreAuthorize gate = java.util.Arrays.stream(LabTestDefinitionController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(handler))
                .findFirst().orElseThrow(NoSuchMethodException::new)
                .getAnnotation(PreAuthorize.class);
            assertThat(gate.value()).as(handler).isEqualTo("hasAnyRole('LAB_DIRECTOR', 'SUPER_ADMIN')");
        }
    }
}
