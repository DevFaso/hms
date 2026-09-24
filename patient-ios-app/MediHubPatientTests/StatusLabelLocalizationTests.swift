import XCTest
@testable import MediHubPatient

/// G16 — a patient must never read `PENDING_STOCK` or `PARTNER_REJECTED` off
/// their own prescription list, in either language the app ships.
///
/// The three enums map every case to a `Localizable.strings` key through an
/// exhaustive `switch` with no `default`, so the compiler already refuses a
/// case without a label. What the compiler cannot see is whether that key
/// exists in **both** `en.lproj` and `fr.lproj` — a missing French key falls
/// back silently at runtime. These tests resolve every case in both bundles
/// and assert the exact text, for the whole enum rather than a sample.
final class StatusLabelLocalizationTests: XCTestCase {

    private struct MissingLocalizationBundle: Error, CustomStringConvertible {
        let language: String
        var description: String { "no \(language).lproj in the app bundle" }
    }

    private static let missing = "__MISSING__"

    private func bundle(_ language: String) throws -> Bundle {
        // The app bundle: `Bundle(for:)` on a class compiled into the host
        // app, not the test bundle, which carries no .lproj of its own.
        let candidates = [Bundle(for: LocalizationManager.self), Bundle.main]
        for candidate in candidates {
            if let path = candidate.path(forResource: language, ofType: "lproj"),
               let localized = Bundle(path: path) {
                return localized
            }
        }
        throw MissingLocalizationBundle(language: language)
    }

    private func label(_ key: String, in bundle: Bundle) -> String {
        bundle.localizedString(forKey: key, value: Self.missing, table: nil)
    }

    // MARK: - Lab result statuses

    func testEveryLabStatusHasAnEnglishAndAFrenchLabel() throws {
        try assertLabels(
            keys: LabResultStatus.allCases.map { (wire: $0.rawValue, labelKey: $0.labelKey) },
            english: [
                "PENDING": "Pending",
                "NORMAL": "Normal",
                "ABNORMAL": "Abnormal",
                "ABNORMAL_LOW": "Abnormal \u{2014} low",
                "ABNORMAL_HIGH": "Abnormal \u{2014} high",
                "CRITICAL": "Critical",
                "__UNKNOWN__": "Status unavailable"
            ],
            french: [
                "PENDING": "En attente",
                "NORMAL": "Normal",
                "ABNORMAL": "Anormal",
                "ABNORMAL_LOW": "Anormal \u{2014} bas",
                "ABNORMAL_HIGH": "Anormal \u{2014} \u{00e9}lev\u{00e9}",
                "CRITICAL": "Critique",
                "__UNKNOWN__": "Statut indisponible"
            ]
        )
    }

    // MARK: - Prescription statuses

    func testEveryPrescriptionStatusHasAnEnglishAndAFrenchLabel() throws {
        try assertLabels(
            keys: PrescriptionStatus.allCases.map { (wire: $0.rawValue, labelKey: $0.labelKey) },
            english: [
                "DRAFT": "Draft",
                "PENDING_SIGNATURE": "Awaiting signature",
                "SIGNED": "Signed",
                "TRANSMITTED": "Sent to the pharmacy",
                "TRANSMISSION_FAILED": "Could not be sent",
                "CANCELLED": "Cancelled",
                "DISCONTINUED": "Discontinued",
                "PENDING_CLARIFICATION": "Awaiting your prescriber",
                "DISPENSED": "Dispensed",
                "PARTIALLY_FILLED": "Partly dispensed",
                "PENDING_STOCK": "Out of stock",
                "REQUIRES_EXTERNAL_FILL": "Outside pharmacy",
                "SENT_TO_PARTNER": "Sent to a partner pharmacy",
                "PARTNER_ACCEPTED": "Accepted by the pharmacy",
                "PARTNER_REJECTED": "Refused by the pharmacy",
                "PARTNER_DISPENSED": "Dispensed by the pharmacy",
                "PRINTED_FOR_PATIENT": "Printed for you",
                "__UNKNOWN__": "Status unavailable"
            ],
            french: [
                "DRAFT": "Brouillon",
                "PENDING_SIGNATURE": "En attente de signature",
                "SIGNED": "Sign\u{00e9}e",
                "TRANSMITTED": "Transmise \u{00e0} la pharmacie",
                "TRANSMISSION_FAILED": "Envoi impossible",
                "CANCELLED": "Annul\u{00e9}e",
                "DISCONTINUED": "Interrompue",
                "PENDING_CLARIFICATION": "En attente de votre prescripteur",
                "DISPENSED": "D\u{00e9}livr\u{00e9}e",
                "PARTIALLY_FILLED": "Partiellement d\u{00e9}livr\u{00e9}e",
                "PENDING_STOCK": "En rupture de stock",
                "REQUIRES_EXTERNAL_FILL": "Pharmacie externe",
                "SENT_TO_PARTNER": "Envoy\u{00e9}e \u{00e0} une pharmacie partenaire",
                "PARTNER_ACCEPTED": "Accept\u{00e9}e par la pharmacie",
                "PARTNER_REJECTED": "Refus\u{00e9}e par la pharmacie",
                "PARTNER_DISPENSED": "D\u{00e9}livr\u{00e9}e par la pharmacie",
                "PRINTED_FOR_PATIENT": "Imprim\u{00e9}e pour vous",
                "__UNKNOWN__": "Statut indisponible"
            ]
        )
    }

    // MARK: - Refill statuses

    func testEveryRefillStatusHasAnEnglishAndAFrenchLabel() throws {
        try assertLabels(
            keys: RefillStatus.allCases.map { (wire: $0.rawValue, labelKey: $0.labelKey) },
            english: [
                "REQUESTED": "Requested",
                "PAUSED": "On hold",
                "APPROVED": "Approved",
                "DENIED": "Denied",
                "DISPENSED": "Dispensed",
                "CANCELLED": "Cancelled",
                "__UNKNOWN__": "Status unavailable"
            ],
            french: [
                "REQUESTED": "Demand\u{00e9}",
                "PAUSED": "En attente",
                "APPROVED": "Approuv\u{00e9}",
                "DENIED": "Refus\u{00e9}",
                "DISPENSED": "D\u{00e9}livr\u{00e9}",
                "CANCELLED": "Annul\u{00e9}",
                "__UNKNOWN__": "Statut indisponible"
            ]
        )
    }

    func testNoStatusLabelIsTheRawWireName() throws {
        let keys = LabResultStatus.allCases.map(\.labelKey)
            + PrescriptionStatus.allCases.map(\.labelKey)
            + RefillStatus.allCases.map(\.labelKey)
        for language in ["en", "fr"] {
            let bundle = try self.bundle(language)
            for key in keys {
                let text = label(key, in: bundle)
                XCTAssertNotEqual(text, Self.missing, "\(language) is missing \(key)")
                XCTAssertFalse(text.contains("_"),
                               "\(language) label for \(key) still reads like a wire constant: \(text)")
                XCTAssertNotEqual(text, text.uppercased(),
                                  "\(language) label for \(key) still reads like a wire constant: \(text)")
            }
        }
    }

    // MARK: - Helper

    /// Whole enum, not a sample: a case added without an expectation fails
    /// right here, because the expectation tables are keyed by raw value.
    private func assertLabels(
        keys: [(wire: String, labelKey: String)],
        english: [String: String],
        french: [String: String]
    ) throws {
        let wireValues = Set(keys.map(\.wire))
        XCTAssertEqual(wireValues, Set(english.keys), "English expectations do not cover the enum")
        XCTAssertEqual(wireValues, Set(french.keys), "French expectations do not cover the enum")

        let en = try bundle("en")
        let fr = try bundle("fr")
        for (wire, labelKey) in keys {
            XCTAssertEqual(label(labelKey, in: en), english[wire], "English label for \(wire)")
            XCTAssertEqual(label(labelKey, in: fr), french[wire], "French label for \(wire)")
        }
    }
}
