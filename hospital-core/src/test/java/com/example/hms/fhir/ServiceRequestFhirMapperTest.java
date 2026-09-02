package com.example.hms.fhir;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.fhir.mapper.ServiceRequestFhirMapper;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ServiceRequest mapping (Tier 2 item 42): the two order domains. */
class ServiceRequestFhirMapperTest {

    private final ServiceRequestFhirMapper mapper = new ServiceRequestFhirMapper();

    private static LabOrder labOrder(LabOrderStatus status, String priority) {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setStatus(status);
        order.setPriority(priority);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        order.setPatient(patient);
        return order;
    }

    @Test
    @DisplayName("a lab order in flight is active; resulted and beyond is completed; cancelled is revoked")
    void labStatusCollapse() {
        assertThat(mapper.toFhir(labOrder(LabOrderStatus.COLLECTED, "ROUTINE")).getStatus())
            .isEqualTo(ServiceRequest.ServiceRequestStatus.ACTIVE);
        assertThat(mapper.toFhir(labOrder(LabOrderStatus.VERIFIED, "ROUTINE")).getStatus())
            .isEqualTo(ServiceRequest.ServiceRequestStatus.COMPLETED);
        assertThat(mapper.toFhir(labOrder(LabOrderStatus.CANCELLED, "ROUTINE")).getStatus())
            .isEqualTo(ServiceRequest.ServiceRequestStatus.REVOKED);
    }

    @Test
    @DisplayName("STAT survives the mapping; an unknown priority string falls back to routine")
    void labPriority() {
        assertThat(mapper.toFhir(labOrder(LabOrderStatus.ORDERED, "STAT")).getPriority())
            .isEqualTo(ServiceRequest.ServiceRequestPriority.STAT);
        assertThat(mapper.toFhir(labOrder(LabOrderStatus.ORDERED, "whatever")).getPriority())
            .isEqualTo(ServiceRequest.ServiceRequestPriority.ROUTINE);
    }

    @Test
    @DisplayName("LOINC is the primary coding when the definition carries one")
    void labLoincPrimary() {
        LabOrder order = labOrder(LabOrderStatus.ORDERED, "ROUTINE");
        LabTestDefinition def = new LabTestDefinition();
        def.setName("Hemoglobin");
        def.setTestCode("HGB");
        def.setLoincCode("718-7");
        def.setLoincDisplay("Hemoglobin [Mass/volume] in Blood");
        order.setLabTestDefinition(def);

        ServiceRequest sr = mapper.toFhir(order);

        assertThat(sr.getIdElement().getIdPart()).isEqualTo("laborder-" + order.getId());
        assertThat(sr.getCode().getCoding().get(0).getSystem()).isEqualTo("http://loinc.org");
        assertThat(sr.getCode().getCoding().get(0).getCode()).isEqualTo("718-7");
        assertThat(sr.getCode().getCoding().get(1).getSystem()).isEqualTo("urn:hms:lab:test-code");
    }

    @Test
    @DisplayName("an imaging order carries modality, body site, priority and the clinical question")
    void imagingOrder() {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setStatus(ImagingOrderStatus.SCHEDULED);
        order.setPriority(ImagingOrderPriority.URGENT);
        order.setModality(ImagingModality.values()[0]);
        order.setStudyType("Chest X-ray PA");
        order.setBodyRegion("Chest");
        order.setClinicalQuestion("Rule out pneumonia");
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        order.setPatient(patient);

        ServiceRequest sr = mapper.toFhir(order);

        assertThat(sr.getIdElement().getIdPart()).isEqualTo("imgorder-" + order.getId());
        assertThat(sr.getStatus()).isEqualTo(ServiceRequest.ServiceRequestStatus.ACTIVE);
        assertThat(sr.getPriority()).isEqualTo(ServiceRequest.ServiceRequestPriority.URGENT);
        assertThat(sr.getCode().getText()).isEqualTo("Chest X-ray PA");
        assertThat(sr.getBodySiteFirstRep().getText()).isEqualTo("Chest");
        assertThat(sr.getReasonCodeFirstRep().getText()).isEqualTo("Rule out pneumonia");
        assertThat(sr.getSubject().getReference()).isEqualTo("Patient/" + patient.getId());
    }

    @Test
    @DisplayName("RESULTS_AVAILABLE is a completed request - the report side takes over from there")
    void imagingResultsAvailableIsCompleted() {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setStatus(ImagingOrderStatus.RESULTS_AVAILABLE);
        assertThat(mapper.toFhir(order).getStatus())
            .isEqualTo(ServiceRequest.ServiceRequestStatus.COMPLETED);
    }
}
