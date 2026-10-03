import XCTest
@testable import MediHubPatient

final class PharmacyInvoicesTests: XCTestCase {
    func testPharmacySelfServiceEndpointsUsePatientScope() {
        XCTAssertEqual(APIEndpoints.pharmacyPayments, "/me/patient/pharmacy/payments")
        XCTAssertEqual(APIEndpoints.pharmacyClaims, "/me/patient/pharmacy/claims")
    }

    func testPharmacyDisplayHelpersFormatBackendEnums() {
        // Labels come from the bundle now (PORTAL.ENUM.* wording), so pin
        // the language the expected strings are written in.
        let restorer = LanguageRestorer()
        defer { restorer.restore() }
        LocalizationManager.shared.setLanguage("en")

        let payment = PharmacyPaymentDTO(
            id: "p1",
            dispenseId: nil,
            patientId: nil,
            hospitalId: nil,
            paymentMethod: "MOBILE_MONEY",
            amount: 1250,
            currency: nil,
            referenceNumber: nil,
            receivedBy: nil,
            notes: nil,
            createdAt: nil,
            updatedAt: nil
        )
        let claim = PharmacyClaimDTO(
            id: "c1",
            dispenseId: nil,
            patientId: nil,
            hospitalId: nil,
            coverageReference: nil,
            // A real PharmacyClaimStatus value; the old fixture's
            // SUBMITTED_FOR_REVIEW is not one, and only read well because
            // the helper humanised whatever token it was given.
            claimStatus: "SUBMITTED",
            amount: 1250,
            currency: nil,
            submittedAt: nil,
            submittedBy: nil,
            rejectionReason: nil,
            notes: nil,
            createdAt: nil,
            updatedAt: nil
        )

        XCTAssertEqual(payment.displayMethod, "Mobile Money")
        XCTAssertEqual(payment.displayCurrency, "XOF")
        XCTAssertEqual(claim.displayStatus, "Submitted")
        XCTAssertEqual(claim.displayCurrency, "XOF")

        LocalizationManager.shared.setLanguage("fr")
        XCTAssertEqual(claim.displayStatus, "Soumise")
        XCTAssertEqual(payment.displayMethod, "Mobile Money")
    }

    func testAnUnknownClaimStatusIsNotPrintedRaw() {
        let restorer = LanguageRestorer()
        defer { restorer.restore() }
        LocalizationManager.shared.setLanguage("en")
        let claim = PharmacyClaimDTO(id: "c2", dispenseId: nil, patientId: nil, hospitalId: nil,
                                     coverageReference: nil, claimStatus: "SUBMITTED_FOR_REVIEW",
                                     amount: nil, currency: nil, submittedAt: nil, submittedBy: nil,
                                     rejectionReason: nil, notes: nil, createdAt: nil, updatedAt: nil)
        XCTAssertEqual(claim.displayStatus, "Unknown")
    }
}
