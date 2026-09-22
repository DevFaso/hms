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

    func testPhotoKindComesFromTheBytes() {
        XCTAssertEqual(DocumentUploadRules.imageKind(of: Data([0xFF, 0xD8, 0xFF, 0xE0]))?.ext, "jpg")
        XCTAssertEqual(DocumentUploadRules.imageKind(of: Data([0x89, 0x50, 0x4E, 0x47]))?.mimeType, "image/png")
        XCTAssertEqual(DocumentUploadRules.imageKind(of: Data([0x47, 0x49, 0x46, 0x38]))?.ext, "gif")
        XCTAssertEqual(DocumentUploadRules.imageKind(of: Data([0x49, 0x49, 0x2A, 0x00]))?.ext, "tiff")
        XCTAssertEqual(DocumentUploadRules.imageKind(of: Data([0x4D, 0x4D, 0x00, 0x2A]))?.ext, "tiff")
        XCTAssertEqual(DocumentUploadRules.imageKind(of: Data([0x42, 0x4D, 0x00, 0x00]))?.ext, "bmp")
        // HEIC ("....ftypheic") and a short blob: not accepted as-is, re-encoded.
        XCTAssertNil(DocumentUploadRules.imageKind(of: Data([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70])))
        XCTAssertNil(DocumentUploadRules.imageKind(of: Data([0xFF])))
    }

    func testPhotoOutputKeepsPngAndTurnsTheRestIntoJpeg() {
        XCTAssertEqual(DocumentUploadRules.photoOutput(forSource: Data([0x89, 0x50, 0x4E, 0x47])).ext, "png")
        XCTAssertEqual(DocumentUploadRules.photoOutput(forSource: Data([0xFF, 0xD8, 0xFF, 0xE0])).ext, "jpg")
        XCTAssertEqual(DocumentUploadRules.photoOutput(forSource: Data([0x49, 0x49, 0x2A, 0x00])).ext, "jpg")
        // HEIC and anything unrecognised: JPEG.
        XCTAssertEqual(DocumentUploadRules.photoOutput(forSource: Data([0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70])).mimeType, "image/jpeg")
    }

    func testNotesAreCountedInCodePointsLikeTheColumn() {
        // One grapheme cluster, several code points: what varchar(2048) counts.
        let family = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}"
        XCTAssertEqual(family.count, 1)
        XCTAssertEqual(DocumentUploadRules.notesLength(family), 5)
        XCTAssertEqual(DocumentUploadRules.notesLength("abc"), 3)
    }

    func testTifIsStagedAsTiff() {
        XCTAssertEqual(DocumentUploadRules.canonicalExtension("tif"), "tiff")
        XCTAssertEqual(DocumentUploadRules.canonicalExtension("TIF"), "tiff")
        XCTAssertEqual(DocumentUploadRules.canonicalExtension("PDF"), "pdf")
        XCTAssertEqual(DocumentUploadRules.canonicalExtension("md"), "md")
        XCTAssertFalse(DocumentUploadRules.allowedExtensions.contains("md"))
    }

    func testUploadRulesMirrorTheServer() {
        // FileUploadService.ALLOWED_ATTACHMENT_EXTENSIONS
        XCTAssertEqual(DocumentUploadRules.allowedExtensions,
                       ["pdf", "jpg", "jpeg", "png", "gif", "bmp", "tiff", "txt", "rtf", "doc", "docx"])
        // spring.servlet.multipart.max-request-size=10MB covers the whole
        // body, so the file cap keeps a margin for boundaries and text parts.
        XCTAssertEqual(DocumentUploadRules.maxBytes, 10 * 1024 * 1024 - 64 * 1024)
        XCTAssertEqual(DocumentUploadRules.maxNotesLength, 2048)
        XCTAssertEqual(DocumentUploadRules.mimeType(forExtension: "pdf"), "application/pdf")
        XCTAssertEqual(DocumentUploadRules.mimeType(forExtension: "jpg"), "image/jpeg")
        XCTAssertFalse(DocumentUploadRules.contentTypes.isEmpty)
    }
}
