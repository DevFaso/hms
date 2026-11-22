package com.example.hms.mapper;

import com.example.hms.model.PatientInsurance;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientInsuranceResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.billing.PayerCatalogueService;
import com.example.hms.service.support.UserDisplayNames;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D3150 (r70a): the chart's Couverture table says who verified a coverage, by name, and when. */
class PatientInsuranceMapperVerifiedTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PayerCatalogueService payers = mock(PayerCatalogueService.class);
    private final PatientInsuranceMapper mapper = new PatientInsuranceMapper(payers, new UserDisplayNames(users));

    private static PatientInsurance coverage(String verifiedBy, LocalDateTime at) {
        PatientInsurance insurance = new PatientInsurance();
        insurance.setId(UUID.randomUUID());
        insurance.setVerifiedBy(verifiedBy);
        insurance.setVerifiedAt(at);
        return insurance;
    }

    @Test
    void aVerifiedCoverageCarriesTheVerifiersNameAndTheDateNeverTheLogin() {
        User verifier = new User();
        verifier.setUsername("test.receptionist.t2.r67");
        verifier.setFirstName("Awa");
        verifier.setLastName("Ouedraogo");
        when(users.findByUsernameInIgnoreCase(any())).thenReturn(List.of(verifier));
        when(payers.labelsOf(any())).thenReturn(Map.of());
        LocalDateTime at = LocalDateTime.of(2026, 11, 22, 2, 52);

        List<PatientInsuranceResponseDTO> rows = mapper.toPatientInsuranceResponseDTOs(List.of(
            coverage("test.receptionist.t2.r67", at), coverage(null, null)));

        assertThat(rows.get(0).getVerifiedByName()).isEqualTo("Awa Ouedraogo");
        assertThat(rows.get(0).getVerifiedAt()).isEqualTo(at);
        assertThat(rows.get(1).getVerifiedByName()).isNull();
        assertThat(rows.get(1).getVerifiedAt()).isNull();
        // One read for the list, not one per row.
        verify(users, times(1)).findByUsernameInIgnoreCase(any());
        assertThat(mapper.toPatientInsuranceResponseDTO(coverage("test.receptionist.t2.r67", at))
            .getVerifiedByName()).isEqualTo("Awa Ouedraogo");
    }
}
