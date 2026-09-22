import XCTest
@testable import MediHubPatient

final class PatientDocumentsTests: XCTestCase {
    func testDocumentEndpointsUsePatientScope() {
        XCTAssertEqual(APIEndpoints.documents, "/me/patient/documents")
        XCTAssertEqual(APIEndpoints.documentById(id: "d1"), "/me/patient/documents/d1")
        XCTAssertEqual(APIEndpoints.documentDownload(id: "d1"), "/me/patient/documents/d1/download")
    }

    func testDocumentTypesMatchTheBackendEnum() {
        XCTAssertEqual(
            PatientDocumentType.allCases.map(\.rawValue),
            ["LAB_RESULT", "IMAGING_REPORT", "DISCHARGE_SUMMARY", "REFERRAL_LETTER", "PRESCRIPTION",
             "INSURANCE_DOCUMENT", "INVOICE", "IMMUNIZATION_RECORD", "OTHER"]
        )
        XCTAssertEqual(PatientDocumentType.label(for: "NOT_A_TYPE"), "NOT_A_TYPE")
        XCTAssertNil(PatientDocumentType.label(for: nil))
    }

    func testUploadRulesMirrorTheServer() {
        // FileUploadService.ALLOWED_ATTACHMENT_EXTENSIONS
        XCTAssertEqual(DocumentUploadRules.allowedExtensions,
                       ["pdf", "jpg", "jpeg", "png", "gif", "bmp", "tiff", "txt", "rtf", "doc", "docx"])
        // spring.servlet.multipart.max-file-size=10MB is the effective cap.
        XCTAssertEqual(DocumentUploadRules.maxBytes, 10 * 1024 * 1024)
        XCTAssertEqual(DocumentUploadRules.maxNotesLength, 2048)
        XCTAssertEqual(DocumentUploadRules.mimeType(forExtension: "pdf"), "application/pdf")
        XCTAssertEqual(DocumentUploadRules.mimeType(forExtension: "jpg"), "image/jpeg")
        XCTAssertFalse(DocumentUploadRules.contentTypes.isEmpty)
    }
}
