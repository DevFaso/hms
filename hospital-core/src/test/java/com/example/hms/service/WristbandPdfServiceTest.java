package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wristband/label PDFs (P3 #23b). The load-bearing assertion is the QR
 * payload contract: the eMAR five-rights check does UUID.fromString on the
 * raw scan, so the wristband must encode the BARE patient UUID — any
 * prefix breaks bedside verification.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WristbandPdfServiceTest {

    @Mock private PatientRepository patientRepository;
    @Mock private LabSpecimenRepository specimenRepository;
    @Mock private StockLotRepository stockLotRepository;

    @InjectMocks private WristbandPdfService service;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        patientId = UUID.randomUUID();
        patient = Patient.builder()
            .firstName("Awa").lastName("Kaboré")
            .dateOfBirth(LocalDate.of(1990, 5, 1))
            .build();
        patient.setId(patientId);
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        registration.setMrn("OUA-1234");
        patient.setHospitalRegistrations(Set.of(registration));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
    }

    @Test
    void wristbandIsAPdf() {
        byte[] pdf = service.generateWristbandPdf(patientId, hospitalId);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(500);
    }

    @Test
    void wristbandQrEncodesTheBarePatientUuid() {
        // The eMAR contract: UUID.fromString(rawScan) must succeed and equal
        // patient.getId(). "PAT-" style prefixes would break five-rights.
        String payload = service.wristbandQrPayload(patient);

        assertThat(payload).isEqualTo(patientId.toString());
        assertThat(UUID.fromString(payload)).isEqualTo(patientId);
    }

    @Test
    void wristbandIs404ForAScopedCallerWithoutRegistration() {
        UUID foreignScope = UUID.randomUUID();

        assertThatThrownBy(() -> service.generateWristbandPdf(patientId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Patient not found");
    }

    @Test
    void specimenLabelIsAPdfAndUsesTheStoredBarcodeValue() {
        LabOrder order = LabOrder.builder().hospital(hospital).patient(patient).build();
        order.setId(UUID.randomUUID());
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(order)
            .accessionNumber("ACC-20260822-00001")
            .barcodeValue("LAB-ACC-20260822-00001")
            .specimenType("Blood")
            .build();
        specimen.setId(UUID.randomUUID());
        when(specimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));

        byte[] pdf = service.generateSpecimenLabelPdf(specimen.getId(), hospitalId);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void specimenLabelIs404ForAForeignHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        LabOrder order = LabOrder.builder().hospital(other).patient(patient).build();
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(order)
            .accessionNumber("ACC-X")
            .build();
        specimen.setId(UUID.randomUUID());
        when(specimenRepository.findById(specimen.getId())).thenReturn(Optional.of(specimen));
        UUID specimenId = specimen.getId();

        assertThatThrownBy(() -> service.generateSpecimenLabelPdf(specimenId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Specimen not found");
    }

    // ── Stock-lot label (Tier 2 item 34) ────────────────────────────────

    private StockLot stockLot(Hospital owner, String barcodeValue) {
        Pharmacy pharmacy = Pharmacy.builder().hospital(owner).name("Main Pharmacy").build();
        pharmacy.setId(UUID.randomUUID());
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setGenericName("Amoxicillin");
        item.setNameFr("Amoxicilline");
        item.setStrength("500");
        item.setStrengthUnit("mg");
        InventoryItem inventoryItem = InventoryItem.builder()
            .pharmacy(pharmacy).medicationCatalogItem(item).build();
        inventoryItem.setId(UUID.randomUUID());
        StockLot lot = StockLot.builder()
            .inventoryItem(inventoryItem)
            .lotNumber("AMX-2291")
            .expiryDate(LocalDate.of(2027, 3, 31))
            .barcodeValue(barcodeValue)
            .build();
        lot.setId(UUID.randomUUID());
        return lot;
    }

    @Test
    void stockLotLabelIsAPdf() {
        StockLot lot = stockLot(hospital, "LOT-4f2a91c07b3e");
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));

        byte[] pdf = service.generateStockLotLabelPdf(lot.getId(), hospitalId);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void printingALotReceivedBeforeV138MintsItsBarcode() {
        // Backfill-on-print rather than a backfill migration: only lots
        // somebody actually prints can ever be scanned, so those are the
        // only ones that need a value.
        StockLot legacy = stockLot(hospital, null);
        when(stockLotRepository.findById(legacy.getId())).thenReturn(Optional.of(legacy));

        service.generateStockLotLabelPdf(legacy.getId(), hospitalId);

        assertThat(legacy.getBarcodeValue()).startsWith("LOT-");
        verify(stockLotRepository).save(legacy);
    }

    @Test
    void reprintingALabelKeepsTheSameBarcode() {
        // A reminted barcode would orphan every pack already on the shelf
        // carrying the old label.
        StockLot lot = stockLot(hospital, "LOT-4f2a91c07b3e");
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));

        service.generateStockLotLabelPdf(lot.getId(), hospitalId);
        service.generateStockLotLabelPdf(lot.getId(), hospitalId);

        assertThat(lot.getBarcodeValue()).isEqualTo("LOT-4f2a91c07b3e");
        verify(stockLotRepository, never()).save(lot);
    }

    @Test
    void stockLotLabelIs404ForAForeignHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        StockLot lot = stockLot(other, "LOT-4f2a91c07b3e");
        when(stockLotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
        UUID lotId = lot.getId();

        assertThatThrownBy(() -> service.generateStockLotLabelPdf(lotId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Stock lot not found");
    }
}
