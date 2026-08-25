package com.example.hms.service.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.DispenseCheck;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.utility.LotBarcode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The counter-side safety checks (Tier 2 item 34).
 *
 * <p>Fixed clock throughout: an expiry check that reads the wall clock is a
 * test that passes until the date it was written stops being true.
 */
class DispenseVerificationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Ouagadougou");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    private DispenseVerificationService service;
    private Patient patient;
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
            TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        service = new DispenseVerificationService(clock);

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        prescription = new Prescription();
        prescription.setId(UUID.randomUUID());
        prescription.setPatient(patient);
        prescription.setMedicationCode("AMOX500");
        prescription.setMedicationName("Amoxicillin");
    }

    /** A lot of the prescribed drug, in date, with a printed label. */
    private StockLot goodLot() {
        return lotOf("AMOX500", "Amoxicillin", TODAY.plusMonths(6));
    }

    private StockLot lotOf(String code, String genericName, LocalDate expiry) {
        MedicationCatalogItem item = new MedicationCatalogItem();
        item.setCode(code);
        item.setGenericName(genericName);
        item.setNameFr(genericName);

        InventoryItem inventoryItem = new InventoryItem();
        inventoryItem.setMedicationCatalogItem(item);

        StockLot lot = StockLot.builder()
            .inventoryItem(inventoryItem)
            .lotNumber("LOT-A1")
            .expiryDate(expiry)
            .barcodeValue(LotBarcode.mint())
            .build();
        lot.setId(UUID.randomUUID());
        return lot;
    }

    // ── The checks that run WITHOUT a scan ───────────────────────────────
    //
    // These are the reason this class exists at all. Before item 34 the
    // dispense path called stockLotRepository.findById directly, going
    // around the FEFO query that filters expired lots, so expiry_date was
    // written at goods-in and read by nothing anywhere in the application.

    @Test
    void anExpiredLotIsRefusedEvenThoughNobodyScannedAnything() {
        StockLot expired = lotOf("AMOX500", "Amoxicillin", TODAY.minusDays(1));

        DispenseVerificationResult result = service.verify(prescription, expired, null, null);

        assertThat(result.failedChecks()).contains(DispenseCheck.EXPIRY);
        assertThat(result.failureSummary()).contains("expired on");
    }

    @Test
    void aLotExpiringTodayIsStillDispensable() {
        // Medicines are labelled "use before end of", so the expiry date
        // itself is the last good day. Off-by-one here would quietly bin
        // a day of stock at every site.
        StockLot expiringToday = lotOf("AMOX500", "Amoxicillin", TODAY);

        DispenseVerificationResult result = service.verify(prescription, expiringToday, null, null);

        assertThat(result.failedChecks()).isEmpty();
        assertThat(result.getOutcomes()).containsEntry(DispenseCheck.EXPIRY, true);
    }

    @Test
    void aLotOfTheWrongDrugIsRefusedEvenThoughNobodyScannedAnything() {
        StockLot wrongDrug = lotOf("MTX25", "Methotrexate", TODAY.plusYears(1));

        DispenseVerificationResult result = service.verify(prescription, wrongDrug, null, null);

        assertThat(result.failedChecks()).contains(DispenseCheck.DRUG);
        // The pharmacist is told BOTH drugs — "wrong drug" alone leaves them
        // guessing which shelf they went to.
        assertThat(result.failureSummary())
            .contains("Methotrexate")
            .contains("Amoxicillin");
    }

    @Test
    void aLotWithNoExpiryRecordedIsRefusedRatherThanAssumedFresh() {
        StockLot noExpiry = lotOf("AMOX500", "Amoxicillin", null);

        DispenseVerificationResult result = service.verify(prescription, noExpiry, null, null);

        assertThat(result.failedChecks()).contains(DispenseCheck.EXPIRY);
    }

    @Test
    void aSiteWithNoLotTrackingEvaluatesNothingAndBlocksNothing() {
        // Dispensing without a stock lot is legitimate here. It must not
        // fail — but it must also not report a pass it has not earned.
        DispenseVerificationResult result = service.verify(prescription, null, null, null);

        assertThat(result.failedChecks()).isEmpty();
        assertThat(result.getOutcomes()).isEmpty();
    }

    // ── The patient scan ────────────────────────────────────────────────

    @Test
    void theRightWristbandPassesThePatientCheck() {
        DispenseVerificationResult result = service.verify(
            prescription, goodLot(), patient.getId().toString(), null);

        assertThat(result.getOutcomes()).containsEntry(DispenseCheck.PATIENT, true);
        assertThat(result.failedChecks()).isEmpty();
    }

    @Test
    void somebodyElsesWristbandFailsThePatientCheck() {
        DispenseVerificationResult result = service.verify(
            prescription, goodLot(), UUID.randomUUID().toString(), null);

        assertThat(result.failedChecks()).contains(DispenseCheck.PATIENT);
        assertThat(result.failureSummary()).contains("different patient");
    }

    @Test
    void aWristbandThatIsNotAnIdentifierFailsRatherThanBeingIgnored() {
        DispenseVerificationResult result = service.verify(
            prescription, goodLot(), "MRN-00417", null);

        assertThat(result.failedChecks()).contains(DispenseCheck.PATIENT);
    }

    @Test
    void notScanningTheWristbandLeavesThePatientCheckUNEVALUATEDNotPassed() {
        // The distinction this whole result type exists for. If "not
        // scanned" collapsed to "passed", the record would assert that the
        // right patient was confirmed when nobody confirmed anything.
        DispenseVerificationResult result = service.verify(prescription, goodLot(), "  ", null);

        assertThat(result.wasEvaluated(DispenseCheck.PATIENT)).isFalse();
        assertThat(result.failedChecks()).isEmpty();
    }

    // ── The product scan ────────────────────────────────────────────────

    @Test
    void scanningTheSelectedPackPasses() {
        StockLot lot = goodLot();

        DispenseVerificationResult result = service.verify(
            prescription, lot, patient.getId().toString(), lot.getBarcodeValue());

        assertThat(result.failedChecks()).isEmpty();
        assertThat(result.getOutcomes()).containsEntry(DispenseCheck.DRUG, true);
    }

    @Test
    void scanningTheWrongPackFailsTheDrugCheck() {
        DispenseVerificationResult result = service.verify(
            prescription, goodLot(), null, LotBarcode.mint());

        assertThat(result.failedChecks()).contains(DispenseCheck.DRUG);
        assertThat(result.failureSummary()).contains("not the stock lot selected");
    }

    @Test
    void scanningTheWristbandIntoTheProductBoxSaysSoRatherThanSayingNoMatch() {
        // Two scan boxes on one screen. "No match" sends the pharmacist
        // looking for a stock problem that does not exist.
        DispenseVerificationResult result = service.verify(
            prescription, goodLot(), null, patient.getId().toString());

        assertThat(result.failedChecks()).contains(DispenseCheck.DRUG);
        assertThat(result.failureSummary()).contains("wristband");
    }

    @Test
    void aLotWithNoPrintedLabelSaysToPrintOneRatherThanFailingObscurely() {
        StockLot unlabelled = goodLot();
        unlabelled.setBarcodeValue(null);

        DispenseVerificationResult result = service.verify(
            prescription, unlabelled, null, "LOT-abcdef123456");

        assertThat(result.failedChecks()).contains(DispenseCheck.DRUG);
        assertThat(result.failureSummary()).contains("print one first");
    }

    @Test
    void aFailedProductScanKeepsItsOwnReasonRatherThanTheGenericDrugOne() {
        // verifyDrug runs after verifyProductScan and would otherwise
        // overwrite the specific message with "the selected lot is X, which
        // is not the prescribed Y" — which is not what went wrong.
        StockLot lot = goodLot();

        DispenseVerificationResult result = service.verify(
            prescription, lot, null, LotBarcode.mint());

        assertThat(result.getFailureReasons().get(DispenseCheck.DRUG))
            .contains("not the stock lot selected")
            .doesNotContain("not the prescribed");
    }

    // ── Drug matching precedence ────────────────────────────────────────

    @Test
    void theCodeIsWhatMatches_notTheNameSpelling() {
        // "Amoxicilline" is how it is written on French-labelled stock.
        // Matching on name alone would refuse a correct dispense.
        StockLot lot = lotOf("AMOX500", "Amoxicilline", TODAY.plusMonths(3));

        DispenseVerificationResult result = service.verify(prescription, lot, null, null);

        assertThat(result.failedChecks()).isEmpty();
    }

    @Test
    void anUncodedPrescriptionFallsBackToMatchingOnName() {
        prescription.setMedicationCode(null);
        StockLot lot = lotOf(null, "Amoxicillin", TODAY.plusMonths(3));

        DispenseVerificationResult result = service.verify(prescription, lot, null, null);

        assertThat(result.failedChecks()).isEmpty();
    }

    @Test
    void anUncodedPrescriptionStillCatchesAPlainlyWrongDrug() {
        prescription.setMedicationCode(null);
        StockLot lot = lotOf(null, "Methotrexate", TODAY.plusMonths(3));

        DispenseVerificationResult result = service.verify(prescription, lot, null, null);

        assertThat(result.failedChecks()).contains(DispenseCheck.DRUG);
    }

    @Test
    void aPrescriptionCarryingNeitherCodeNorNameIsNotBlocked() {
        // Legacy rows that predate coded ordering. Refusing them would take
        // the pharmacy down for records nobody can retrospectively fix.
        prescription.setMedicationCode(null);
        prescription.setMedicationName(null);

        DispenseVerificationResult result = service.verify(prescription, goodLot(), null, null);

        assertThat(result.failedChecks()).isEmpty();
    }

    @Test
    void aLotNotLinkedToAnyMedicationIsRefused() {
        StockLot orphan = goodLot();
        orphan.getInventoryItem().setMedicationCatalogItem(null);

        DispenseVerificationResult result = service.verify(prescription, orphan, null, null);

        assertThat(result.failedChecks()).contains(DispenseCheck.DRUG);
    }

    @Test
    void theRefusalNeverRendersTheWordNullWhenNothingIsNamed() {
        // The failure shape that has bitten twice this week: a message
        // assembled from fields that may be absent, asserted on the half
        // that happened to be populated.
        prescription.setMedicationCode("XYZ");
        prescription.setMedicationName(null);
        prescription.setMedicationDisplayName(null);
        MedicationCatalogItem nameless = new MedicationCatalogItem();
        nameless.setCode("OTHER");
        StockLot lot = goodLot();
        lot.getInventoryItem().setMedicationCatalogItem(nameless);

        DispenseVerificationResult result = service.verify(prescription, lot, null, null);

        assertThat(result.failureSummary())
            .doesNotContain("null")
            .doesNotContain("%s");
    }
}
