import XCTest
@testable import MediHubPatient

final class PharmacyInvoicesTests: XCTestCase {
    func testPharmacySelfServiceEndpointsUsePatientScope() {
        XCTAssertEqual(APIEndpoints.pharmacyPayments, "/me/patient/pharmacy/payments")
        XCTAssertEqual(APIEndpoints.pharmacyClaims, "/me/patient/pharmacy/claims")
    }

    func testPharmacyDisplayHelpersFormatBackendEnums() {
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
            claimStatus: "SUBMITTED_FOR_REVIEW",
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
        XCTAssertEqual(claim.displayStatus, "Submitted For Review")
        XCTAssertEqual(claim.displayCurrency, "XOF")
    }
}
