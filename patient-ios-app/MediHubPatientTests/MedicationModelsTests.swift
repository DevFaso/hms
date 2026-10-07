import XCTest
@testable import MediHubPatient

/// G15 AC-12: the medication and prescription DTOs decode readiness, and
/// a payload without it decodes unchanged.
final class MedicationModelsTests: XCTestCase {

    func testPrescriptionDecodesReadiness() throws {
        let json = """
        {"id": "rx-1", "medicationName": "Amoxicillin", "status": "SIGNED",
         "readyForCollectionAt": "2026-10-06T15:30:00",
         "readyForCollectionPharmacyName": "Pharmacie Centrale"}
        """
        let rx = try JSONDecoder().decode(PrescriptionDTO.self, from: Data(json.utf8))
        XCTAssertEqual(rx.readyForCollectionAt, "2026-10-06T15:30:00")
        XCTAssertEqual(rx.readyForCollectionPharmacyName, "Pharmacie Centrale")
    }

    func testMedicationDecodesReadinessAndToleratesItsAbsence() throws {
        let ready = """
        {"id": "m-1", "medicationName": "Amoxicillin", "status": "ACTIVE",
         "readyForCollectionAt": "2026-10-06T15:30:00",
         "readyForCollectionPharmacyName": "Pharmacie Centrale"}
        """
        let plain = """
        {"id": "m-2", "medicationName": "Metformin", "status": "ACTIVE"}
        """
        let med = try JSONDecoder().decode(MedicationDTO.self, from: Data(ready.utf8))
        let other = try JSONDecoder().decode(MedicationDTO.self, from: Data(plain.utf8))
        XCTAssertEqual(med.readyForCollectionPharmacyName, "Pharmacie Centrale")
        XCTAssertEqual(med.readyForCollectionAt, "2026-10-06T15:30:00")
        XCTAssertNil(other.readyForCollectionAt)
        XCTAssertNil(other.readyForCollectionPharmacyName)
    }
}
