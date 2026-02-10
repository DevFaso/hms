package com.example.hms;

import com.example.hms.patient.dto.AddressDto;
import com.example.hms.patient.dto.MedicalHistoryDto;
import com.example.hms.patient.dto.PatientInsuranceDto;
import com.example.hms.patient.dto.PatientRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
    "spring.liquibase.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:hms_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver"
})
class PatientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createAndSearchPatient() throws Exception {
        PatientRequest request = new PatientRequest();
        request.setMrn("MRN-1001");
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setDateOfBirth(LocalDate.of(1990, 1, 10));
        request.setGender("Male");
        request.setPhone("+221700000000");
        request.setEmail("john.doe@example.com");

        AddressDto address = new AddressDto();
        address.setLine1("123 Main St");
        address.setCity("Dakar");
        address.setCountry("SN");
        request.setAddress(address);

        MedicalHistoryDto medicalHistory = new MedicalHistoryDto();
        medicalHistory.setAllergies("Peanuts");
        medicalHistory.setConditions("Hypertension");
        request.setMedicalHistory(medicalHistory);

        PatientInsuranceDto insurance = new PatientInsuranceDto();
        insurance.setProviderName("CNAM");
        insurance.setPolicyNumber("POL-12345");
        insurance.setPrimaryPlan(true);
        request.setInsurances(List.of(insurance));

    mockMvc.perform(post("/api/patients-v2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.mrn").value("MRN-1001"));

    mockMvc.perform(get("/api/patients-v2")
                .param("q", "john"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].firstName").value("John"))
            .andExpect(jsonPath("$[0].lastName").value("Doe"));
    }
}
