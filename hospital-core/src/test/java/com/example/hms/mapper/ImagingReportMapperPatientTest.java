package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.ImagingReport;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1923 (r53): a report names its patient, with the MRN of the report's
 * hospital, so the results list and the drawer say whose report is signed.
 */
class ImagingReportMapperPatientTest {

    private final ImagingReportMapper mapper = new ImagingReportMapper();

    @Test
    void aReportNamesItsPatientAndTheirMrnHere() {
        Hospital here = hospital();
        Hospital elsewhere = hospital();
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Boureima");
        patient.setLastName("Kiemde");
        Set<PatientHospitalRegistration> registrations = new HashSet<>();
        registrations.add(registration(elsewhere, "OTHER-1"));
        registrations.add(registration(here, "CHW2576"));
        patient.setHospitalRegistrations(registrations);
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setHospital(here);
        ImagingReport report = new ImagingReport();
        report.setImagingOrder(order);
        report.setHospital(here);

        ImagingReportResponseDTO dto = mapper.toResponseDTO(report);

        assertThat(dto.getPatientId()).isEqualTo(patient.getId());
        assertThat(dto.getPatientDisplayName()).isEqualTo("Boureima Kiemde");
        assertThat(dto.getPatientMrn()).isEqualTo("CHW2576");
    }

    @Test
    void aReportWithNoOrderNamesNobody() {
        ImagingReportResponseDTO dto = mapper.toResponseDTO(new ImagingReport());

        assertThat(dto.getPatientId()).isNull();
        assertThat(dto.getPatientDisplayName()).isNull();
        assertThat(dto.getPatientMrn()).isNull();
    }

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        return h;
    }

    private static PatientHospitalRegistration registration(Hospital hospital, String mrn) {
        PatientHospitalRegistration r = new PatientHospitalRegistration();
        r.setId(UUID.randomUUID());
        r.setHospital(hospital);
        r.setMrn(mrn);
        return r;
    }
}
