package com.example.hms.controller;

import ca.uhn.fhir.context.FhirContext;
import com.example.hms.fhir.everything.PatientEverythingService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the record-download endpoint: the merged bundle streams as
 * a named {@code application/fhir+json} attachment. Tenancy and the flag
 * bypass are the service's contract (PatientEverythingServiceTenantGateTest);
 * this pins the HTTP shape the portal's blob download depends on.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = PatientRecordExportController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@Import(PatientRecordExportControllerTest.FhirTestConfig.class)
class PatientRecordExportControllerTest {

    @TestConfiguration
    static class FhirTestConfig {
        @Bean
        FhirContext fhirContext() {
            return FhirContext.forR4Cached();
        }
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PatientEverythingService everythingService;

    @Test
    void streamsTheBundleAsANamedFhirJsonAttachment() throws Exception {
        UUID patientId = UUID.randomUUID();
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.addEntry().setResource(new Patient());
        bundle.setTotal(1);
        when(everythingService.fullRecordForDownload(patientId)).thenReturn(bundle);

        mockMvc.perform(get("/patients/{id}/fhir-record", patientId)
                .with(SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "dr.awa", "pw", AuthorityUtils.createAuthorityList("ROLE_DOCTOR")))))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"patient-record-" + patientId + ".json\""))
            .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"resourceType\": \"Bundle\"")));
    }
}
