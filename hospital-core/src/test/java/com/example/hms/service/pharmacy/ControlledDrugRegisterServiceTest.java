package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.ControlledRegisterDTO;
import com.example.hms.payload.dto.pharmacy.ControlledRegisterDrugDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D967: the controlled-drug register - per drug, a running balance from
 * the ledger, the patient and prescriber of a dispense and of a return booked
 * against it, the recorder and the witness; scoped to the caller's hospital;
 * exported in French.
 */
class ControlledDrugRegisterServiceTest {

    private final MessageSource previous =
            (MessageSource) ReflectionTestUtils.getField(MessageUtil.class, "messageSource");
    private final PharmacyRepository pharmacies = mock(PharmacyRepository.class);
    private final InventoryItemRepository items = mock(InventoryItemRepository.class);
    private final StockTransactionRepository ledger = mock(StockTransactionRepository.class);
    private final DispenseRepository dispenses = mock(DispenseRepository.class);
    private final PrescriptionRepository prescriptions = mock(PrescriptionRepository.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final ControlledDrugRegisterService service = new ControlledDrugRegisterService(
            pharmacies, items, ledger, dispenses, prescriptions, roleValidator);

    private final UUID hospitalId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 10, 1);
    private final LocalDate to = LocalDate.of(2026, 10, 31);
    private Pharmacy pharmacy;
    private InventoryItem morphine;
    private InventoryItem paracetamol;
    private Prescription rx;
    private User pharmacist;
    private User witness;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        messageSource.setBasenames(com.example.hms.config.MessageBundles.basenames());
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setAlwaysUseMessageFormat(true);
        MessageUtil.setMessageSource(messageSource);
        LocaleContextHolder.setLocale(Locale.FRENCH);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        pharmacy = Pharmacy.builder().hospital(hospital).name("Pharmacie centrale").build();
        pharmacy.setId(UUID.randomUUID());
        morphine = item("Morphine", true);
        paracetamol = item("Paracétamol", false);

        Patient patient = new Patient();
        patient.setFirstName("Awa");
        patient.setLastName("Sanou");
        User doctor = user("Issa", "Kaboré");
        Staff staff = new Staff();
        staff.setUser(doctor);
        rx = new Prescription();
        rx.setId(UUID.randomUUID());
        rx.setPatient(patient);
        rx.setStaff(staff);
        pharmacist = user("Mariam", "Traoré");
        witness = user("Paul", "Zongo");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(pharmacies.findById(pharmacy.getId())).thenReturn(Optional.of(pharmacy));
        when(items.findAllWithCatalogItemByPharmacyId(pharmacy.getId())).thenReturn(List.of(morphine, paracetamol));
    }

    @AfterEach
    void tearDown() {
        MessageUtil.setMessageSource(previous);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void eachControlledDrugCarriesItsRunningBalanceAndThePeopleOfEveryMovement() {
        stubLedger();

        ControlledRegisterDTO register = service.register(pharmacy.getId(), null, from, to);

        assertThat(register.drugs()).extracting(ControlledRegisterDrugDTO::medicationName)
                .containsExactly("Morphine 10 mg");
        ControlledRegisterDrugDTO drug = register.drugs().get(0);
        assertThat(drug.openingBalance()).isEqualByComparingTo("70");
        assertThat(drug.entries()).extracting(e -> e.balanceAfter().stripTrailingZeros().toPlainString())
                .containsExactly("90", "88", "89");
        var dispense = drug.entries().get(1);
        assertThat(dispense.movementType()).isEqualTo("DISPENSE");
        assertThat(dispense.quantity()).isEqualByComparingTo("-2");
        assertThat(dispense.patientName()).isEqualTo("Awa Sanou");
        assertThat(dispense.prescriberName()).isEqualTo("Issa Kaboré");
        assertThat(dispense.recordedBy()).isEqualTo("Mariam Traoré");
        assertThat(dispense.witnessedBy()).isEqualTo("Paul Zongo");
        assertThat(dispense.lotNumber()).isEqualTo("R16-MOR-01");
        assertThat(dispense.reason()).isEqualTo("Délivrance sur ordonnance");
        var back = drug.entries().get(2);
        assertThat(back.patientName()).isEqualTo("Awa Sanou");
        assertThat(drug.closingBalance()).isEqualByComparingTo("89");
    }

    /**
     * Review of #975: the shelf count is today's, so it is reported only when
     * the period reaches today; a register of a past quarter compares nothing
     * and flags no false discrepancy.
     */
    @Test
    void theShelfCountIsReportedOnlyWhenThePeriodCoversToday() {
        stubLedger();

        ControlledRegisterDrugDTO current = service.register(pharmacy.getId(), null, from, to).drugs().get(0);
        assertThat(current.quantityOnHand()).isEqualByComparingTo("89");

        LocalDate pastFrom = from.minusMonths(6);
        LocalDate pastTo = to.minusMonths(6);
        ControlledRegisterDrugDTO past = service.register(pharmacy.getId(), null, pastFrom, pastTo).drugs().get(0);
        assertThat(past.quantityOnHand()).isNull();
        assertThat(past.closingBalance()).isNotNull();
    }

    @Test
    void anotherHospitalsPharmacyIsNotFoundAndItsLedgerIsNeverRead() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.register(pharmacy.getId(), null, from, to))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(ledger, never()).findRegisterRows(any(), any(), any(), any());
    }

    @Test
    void aDrugThatIsNotControlledHasNoRegister() {
        assertThatThrownBy(() -> service.register(pharmacy.getId(), paracetamol.getId(), from, to))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aBackwardsOrOverlongPeriodIsRefused() {
        assertThatThrownBy(() -> service.register(pharmacy.getId(), null, to, from))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.register.periodInvalid");
        assertThatThrownBy(() -> service.register(pharmacy.getId(), null, from, from.plusDays(400)))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.register.periodTooLong");
    }

    @Test
    void theCsvIsInFrenchWithOneLinePerMovement() {
        stubLedger();

        String csv = new String(service.exportCsv(pharmacy.getId(), null, from, to), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿Médicament;Date et heure;Mouvement;Quantité;Lot;Solde;Patient;"
                + "Prescripteur;Saisi par;Témoin;Motif");
        assertThat(csv).contains("Morphine 10 mg;;Solde d'ouverture;;;70")
                .contains(";Délivrance;-2;R16-MOR-01;88;Awa Sanou;Issa Kaboré;Mariam Traoré;Paul Zongo;");
        assertThat(csv.split("\r\n")).hasSize(5);
    }

    @Test
    void thePdfIsTheRegisterInFrench() throws Exception {
        stubLedger();

        byte[] pdf = service.exportPdf(pharmacy.getId(), null, from, to);

        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Registre des stupéfiants").contains("Pharmacie centrale")
                    .contains("Morphine 10 mg").contains("Awa Sanou").contains("Paul Zongo");
        }
    }

    @Test
    void aCellThatLooksLikeAFormulaIsNeutralised() {
        assertThat(ControlledDrugRegisterService.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ControlledDrugRegisterService.csvCell("-2")).isEqualTo("-2");
        assertThat(ControlledDrugRegisterService.csvCell("a;b")).isEqualTo("\"a;b\"");
    }

    @Test
    void theLedgersEnglishReasonsReadInFrench() {
        assertThat(ControlledDrugRegisterService.reasonText(Locale.FRENCH, "RETURN ref D-1234")).isEqualTo("Retour, réf. D-1234");
        assertThat(ControlledDrugRegisterService.reasonText(Locale.FRENCH, "DAMAGE: ampoule cassée"))
                .isEqualTo("Détérioré : ampoule cassée");
        assertThat(ControlledDrugRegisterService.reasonText(Locale.FRENCH, "Stock receipt: lot L1")).isEqualTo("Réception, lot L1");
        assertThat(ControlledDrugRegisterService.reasonText(Locale.FRENCH, "Comptage du soir")).isEqualTo("Comptage du soir");
    }

    private void stubLedger() {
        when(ledger.sumByItemAndTypeBefore(eq(List.of(morphine.getId())), any())).thenReturn(List.of(
                new Object[] {morphine.getId(), StockTransactionType.RECEIPT, new BigDecimal("100")},
                new Object[] {morphine.getId(), StockTransactionType.DISPENSE, new BigDecimal("30")}));
        StockLot lot = StockLot.builder().inventoryItem(morphine).lotNumber("R16-MOR-01").build();
        StockTransaction receipt = tx(StockTransactionType.RECEIPT, "20", "Stock receipt: lot R16-MOR-01", lot, 1);
        StockTransaction fill = tx(StockTransactionType.DISPENSE, "2", "Dispense for prescription " + rx.getId(), lot, 2);
        fill.setWitnessedByUser(witness);
        UUID dispenseId = UUID.randomUUID();
        StockTransaction back = tx(StockTransactionType.RETURN, "1", "RETURN ref D-abcdef12", lot, 3);
        back.setReferenceId(dispenseId);
        back.setWitnessedByUser(witness);
        when(ledger.findRegisterRows(eq(List.of(morphine.getId())), any(), any(), any()))
                .thenReturn(List.of(receipt, fill, back));
        when(prescriptions.findAllById(any())).thenReturn(List.of(rx));
        Dispense dispense = new Dispense();
        dispense.setId(dispenseId);
        dispense.setPrescription(rx);
        when(dispenses.findAllById(any())).thenReturn(List.of(dispense));
    }

    private StockTransaction tx(StockTransactionType type, String qty, String reason, StockLot lot, int hour) {
        StockTransaction t = StockTransaction.builder().inventoryItem(morphine).transactionType(type)
                .quantity(new BigDecimal(qty)).reason(reason).stockLot(lot).performedByUser(pharmacist).build();
        t.setId(UUID.randomUUID());
        t.setCreatedAt(LocalDateTime.of(2026, 10, 27, hour, 0));
        return t;
    }

    private InventoryItem item(String name, boolean controlled) {
        MedicationCatalogItem med = new MedicationCatalogItem();
        med.setId(UUID.randomUUID());
        med.setNameFr(name);
        med.setStrength("10");
        med.setStrengthUnit("mg");
        med.setControlled(controlled);
        InventoryItem item = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(med)
                .quantityOnHand(new BigDecimal("89")).unit("ampoule").build();
        item.setId(UUID.randomUUID());
        return item;
    }

    private static User user(String first, String last) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }
}
