package com.example.hms.mapper;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab requests drawer: the order carries its prescriber, and the patient's MRN
 * AT THE ORDER'S HOSPITAL and date of birth (the drawer shows the age), all
 * read from data the order already links to.
 */
class LabOrderMapperDrawerFieldsTest {

    private final LabOrderMapper mapper = new LabOrderMapper();
    private Hospital hospital;
    private Hospital otherHospital;
    private Patient patient;
    private LabOrder order;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());

        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Jane");
        patient.setLastName("Roe");
        patient.setDateOfBirth(LocalDate.of(1990, 4, 2));
        patient.getHospitalRegistrations().add(registration(otherHospital, "MRN-OTHER"));
        patient.getHospitalRegistrations().add(registration(hospital, "MRN-HERE"));

        User user = User.builder().firstName("Awa").lastName("Traoré").build();
        Staff staff = Staff.builder().user(user).build();

        LabTestDefinition def = new LabTestDefinition();
        def.setId(UUID.randomUUID());
        def.setName("Glycémie à jeun");

        order = LabOrder.builder()
            .patient(patient)
            .hospital(hospital)
            .orderingStaff(staff)
            .labTestDefinition(def)
            .status(LabOrderStatus.ORDERED)
            .build();
        order.setId(UUID.randomUUID());
    }

    private PatientHospitalRegistration registration(Hospital h, String mrn) {
        PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
            .hospital(h).patient(patient).mrn(mrn).build();
        reg.setId(UUID.randomUUID());
        return reg;
    }

    @Test
    void carriesThePrescriberTheMrnAtTheOrdersHospitalAndTheDateOfBirth() {
        LabOrderResponseDTO dto = mapper.toLabOrderResponseDTO(order);

        assertThat(dto.getOrderingStaffName()).isEqualTo("Awa Traoré");
        assertThat(dto.getPatientMrn()).isEqualTo("MRN-HERE");
        assertThat(dto.getPatientDateOfBirth()).isEqualTo(LocalDate.of(1990, 4, 2));
        // Resolved on the single-order read only, never by the list mapper.
        assertThat(dto.getPrimaryDiagnosisLabel()).isNull();
    }

    @Test
    void anOrderWithoutPrescriberOrRegistrationLeavesThoseBlank() {
        order.setOrderingStaff(null);
        patient.getHospitalRegistrations().clear();

        LabOrderResponseDTO dto = mapper.toLabOrderResponseDTO(order);

        assertThat(dto.getOrderingStaffName()).isNull();
        assertThat(dto.getPatientMrn()).isNull();
    }
}
