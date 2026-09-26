import XCTest
@testable import MediHubPatient

/// G16 — the prescription list is where a patient reads the pharmacy's
/// decision about their medication. The wire contract is
/// `PrescriptionResponseDTO`, served by `GET /me/patient/prescriptions`.
final class PrescriptionWireContractTests: XCTestCase {

    private func decodePrescription(_ json: String) throws -> PrescriptionDTO {
        try JSONDecoder().decode(PrescriptionDTO.self, from: Data(json.utf8))
    }

    func testPrescriptionDecodesTheNamesTheDtoActuallyServes() throws {
        let rx = try decodePrescription("""
        {
          "id": "3f0a1c22-0000-4000-8000-000000000001",
          "medicationName": "Amoxicillin",
          "medicationDisplayName": "Amoxicillin 500 mg capsule",
          "dosage": "500 mg",
          "frequency": "Three times a day",
          "duration": "7 days",
          "route": "Oral",
          "status": "PENDING_STOCK",
          "staffFullName": "Dr Awa Traore",
          "createdAt": "2026-09-18T09:00:00",
          "pharmacyName": "Pharmacie du Fleuve",
          "instructions": "Take with food"
        }
        """)

        XCTAssertEqual(rx.medicationName, "Amoxicillin")
        XCTAssertEqual(rx.displayName, "Amoxicillin 500 mg capsule")
        XCTAssertEqual(rx.duration, "7 days")
        XCTAssertEqual(rx.route, "Oral")
        XCTAssertEqual(rx.staffFullName, "Dr Awa Traore")
        XCTAssertEqual(rx.createdAt, "2026-09-18T09:00:00")
        XCTAssertEqual(rx.pharmacyName, "Pharmacie du Fleuve")
        XCTAssertEqual(rx.statusEnum, .pendingStock)
        XCTAssertEqual(rx.statusEnum.tone, .attention)
    }

    func testDisplayNameFallsBackToTheRawMedicationName() throws {
        let rx = try decodePrescription("""
        { "id": "a", "medicationName": "Paracetamol" }
        """)
        XCTAssertEqual(rx.displayName, "Paracetamol")
    }

    func testUnknownOrMissingStatusFallsBackInsteadOfRenderingTheRawName() {
        XCTAssertEqual(PrescriptionStatus(wire: nil), .unknown)
        XCTAssertEqual(PrescriptionStatus(wire: ""), .unknown)
        XCTAssertEqual(PrescriptionStatus(wire: "BRAND_NEW_STATE"), .unknown)
        XCTAssertEqual(PrescriptionStatus(wire: " partner_rejected "), .partnerRejected)
        XCTAssertEqual(RefillStatus(wire: nil), .unknown)
        XCTAssertEqual(RefillStatus(wire: "paused"), .paused)
    }

    /// Every constant of `com.example.hms.enums.PrescriptionStatus` as of this
    /// change. This is a hand-copied set inside the iOS target — it does NOT
    /// compile against the Java enum, so a constant added on the backend will
    /// not fail here; it reaches the app as `.unknown` and renders "Status
    /// unavailable" until someone updates both sides. What this pins is the
    /// other direction: an app-side edit that drops or renames a case.
    func testTheEnumCoversEveryBackendPrescriptionStatus() {
        let backend: Set<String> = [
            "DRAFT", "PENDING_SIGNATURE", "SIGNED", "TRANSMITTED", "TRANSMISSION_FAILED",
            "CANCELLED", "DISCONTINUED", "PENDING_CLARIFICATION", "DISPENSED",
            "PARTIALLY_FILLED", "PENDING_STOCK", "REQUIRES_EXTERNAL_FILL", "SENT_TO_PARTNER",
            "PARTNER_ACCEPTED", "PARTNER_REJECTED", "PARTNER_DISPENSED", "PRINTED_FOR_PATIENT"
        ]
        XCTAssertEqual(Set(PrescriptionStatus.wireCases.map(\.rawValue)), backend)
    }

    /// `PatientMedicationServiceImpl.resolveStatus` — the views used to print
    /// `status?.capitalized`, so `ON_HOLD` reached the patient as "On_hold".
    func testTheEnumCoversEveryBackendMedicationStatus() {
        let backend: Set<String> = ["ACTIVE", "COMPLETED", "DISCONTINUED", "ON_HOLD"]
        XCTAssertEqual(Set(MedicationStatus.wireCases.map(\.rawValue)), backend)
        XCTAssertEqual(MedicationStatus(wire: nil), .unknown)
        XCTAssertEqual(MedicationStatus(wire: ""), .unknown)
        XCTAssertEqual(MedicationStatus(wire: "on_hold"), .onHold)
        XCTAssertEqual(MedicationStatus(wire: "SOMETHING_NEW"), .unknown)
    }

    func testTheEnumCoversEveryBackendRefillStatus() {
        let backend: Set<String> = [
            "REQUESTED", "PAUSED", "APPROVED", "DENIED", "DISPENSED", "CANCELLED"
        ]
        XCTAssertEqual(Set(RefillStatus.wireCases.map(\.rawValue)), backend)
    }

    /// Mirrors `PrescriptionStatus.isRefillable()`. The prescriptions tab used
    /// to gate its Request-refill button on a `refillsRemaining` counter the
    /// DTO has never carried, so the button never appeared at all.
    func testRefillabilityMatchesTheBackendRule() {
        let notRefillable: Set<PrescriptionStatus> = [
            .draft, .pendingSignature, .cancelled, .discontinued
        ]
        for status in PrescriptionStatus.allCases {
            XCTAssertEqual(status.isRefillable, !notRefillable.contains(status),
                           "refillability of \(status.rawValue)")
        }
    }

    func testOnlyRequestedAndPausedRefillsCanBeWithdrawn() throws {
        let cancellable: Set<RefillStatus> = [.requested, .paused]
        for status in RefillStatus.allCases {
            XCTAssertEqual(status.isCancellable, cancellable.contains(status),
                           "cancellability of \(status.rawValue)")
        }

        let paused = try JSONDecoder().decode(
            RefillDTO.self, from: Data(#"{"id":"r1","status":"PAUSED"}"#.utf8))
        XCTAssertTrue(paused.statusEnum.isCancellable)

        let dispensed = try JSONDecoder().decode(
            RefillDTO.self, from: Data(#"{"id":"r2","status":"DISPENSED"}"#.utf8))
        XCTAssertFalse(dispensed.statusEnum.isCancellable)
    }

    /// `OPEN_REFILL_STATUSES` in `PatientPortalServiceImpl` — the set that
    /// makes a second request for the same prescription a 400.
    func testOnlyRequestedAndPausedRefillsBlockANewRequest() {
        let open: Set<RefillStatus> = [.requested, .paused]
        for status in RefillStatus.allCases {
            XCTAssertEqual(status.isOpen, open.contains(status),
                           "openness of \(status.rawValue)")
        }
    }

    func testEveryStatusMapsToItsOwnLabelKey() {
        XCTAssertEqual(Set(PrescriptionStatus.allCases.map(\.labelKey)).count,
                       PrescriptionStatus.allCases.count)
        XCTAssertEqual(Set(RefillStatus.allCases.map(\.labelKey)).count,
                       RefillStatus.allCases.count)
        XCTAssertEqual(Set(LabResultStatus.allCases.map(\.labelKey)).count,
                       LabResultStatus.allCases.count)
    }
}
