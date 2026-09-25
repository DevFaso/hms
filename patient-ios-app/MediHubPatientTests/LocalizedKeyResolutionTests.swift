import XCTest
@testable import MediHubPatient

/// The gate that would have caught 70 keys shipping unresolved.
///
/// `StatusLabelLocalizationTests` asserts that a set of keys the test itself
/// names resolve in both bundles, and a parity check asserts that `en.lproj`
/// and `fr.lproj` define the same keys. Neither can see a key that is missing
/// from **both** bundles: parity is a perfect 534/534 while the Disclosures,
/// Sharing-opt-out and Medical-History screens render `disclosures_page_title`
/// and `sharing_optout_enable` as literal text to the patient. A parity check
/// cannot see a key that is missing everywhere.
///
/// So this test starts from the source, not from the bundles: it reads the
/// app's own `.swift` files and asserts that every key they use resolves.
final class LocalizedKeyResolutionTests: XCTestCase {

    // MARK: - Where the sources are

    /// The app source directory, derived from this file's own path at compile
    /// time. Build and test run on the same machine in CI and locally, so the
    /// path is valid wherever the suite runs; if it ever is not, the test says
    /// so rather than passing vacuously.
    private static var appSourceDirectory: URL {
        URL(fileURLWithPath: #filePath)      // …/MediHubPatientTests/LocalizedKeyResolutionTests.swift
            .deletingLastPathComponent()     // …/MediHubPatientTests
            .deletingLastPathComponent()     // …/patient-ios-app
            .appendingPathComponent("MediHubPatient")
    }

    private func swiftSources() throws -> [URL] {
        let root = Self.appSourceDirectory
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: root.path, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            XCTFail("app sources not found at \(root.path) — this test cannot verify anything")
            return []
        }
        guard let walker = FileManager.default.enumerator(atPath: root.path) else { return [] }
        var found: [URL] = []
        for case let relative as String in walker where relative.hasSuffix(".swift") {
            found.append(root.appendingPathComponent(relative))
        }
        XCTAssertGreaterThan(found.count, 20, "suspiciously few sources found under \(root.path)")
        return found
    }

    // MARK: - Reading keys out of the source

    /// Comments are stripped first: a key named in a doc comment is
    /// documentation, not a use, and `/// see `history_title`` must not fail
    /// a build. Interpolated strings never match — the whole literal has to be
    /// key-shaped — which is why the constructed keys (`disclosures_category_`
    /// + the enum) are covered by naming their families in the test below
    /// rather than by this scan.
    private func stripComments(_ text: String) -> String {
        var out = text
        for pattern in ["/\\*[\\s\\S]*?\\*/", "//[^\\n]*"] {
            let regex = try? NSRegularExpression(pattern: pattern)
            out = regex?.stringByReplacingMatches(
                in: out, range: NSRange(out.startIndex..., in: out), withTemplate: "") ?? out
        }
        return out
    }

    private func matches(_ pattern: String, in text: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
            .compactMap { match in
                Range(match.range(at: 1), in: text).map { String(text[$0]) }
            }
    }

    // MARK: - Bundles

    private static let missing = "__MISSING__"

    private func bundle(_ language: String) throws -> Bundle {
        let candidates = [Bundle(for: LocalizationManager.self), Bundle.main]
        for candidate in candidates {
            if let path = candidate.path(forResource: language, ofType: "lproj"),
               let localized = Bundle(path: path) {
                return localized
            }
        }
        struct MissingBundle: Error, CustomStringConvertible {
            let language: String
            var description: String { "no \(language).lproj in the app bundle" }
        }
        throw MissingBundle(language: language)
    }

    private func unresolved(_ keys: Set<String>, in bundle: Bundle) -> [String] {
        keys.filter { key in
            let value = bundle.localizedString(forKey: key, value: Self.missing, table: nil)
            return value == Self.missing || value.isEmpty
        }.sorted()
    }

    // MARK: - 1. Every `"key".localized` literal resolves

    func testEveryLocalizedLiteralResolvesInBothBundles() throws {
        var keys = Set<String>()
        for source in try swiftSources() {
            let text = stripComments(try String(contentsOf: source, encoding: .utf8))
            keys.formUnion(matches("\"([A-Za-z0-9_]+)\"\\.localized", in: text))
        }
        XCTAssertGreaterThan(keys.count, 100, "the scan found almost nothing — check the regex")

        for language in ["en", "fr"] {
            let missing = unresolved(keys, in: try bundle(language))
            XCTAssertTrue(missing.isEmpty,
                          "\(missing.count) key(s) used with .localized resolve to nothing in "
                            + "\(language).lproj: \(missing.joined(separator: ", "))")
        }
    }

    // MARK: - 2. Every key-shaped literal in a feature resolves

    /// The `.localized` scan alone would have missed most of what shipped
    /// broken: `emptyRow("history_no_medical")` and `socialCard("history_alcohol")`
    /// hand a bare literal to a helper that localizes it, and
    /// `HistorySection.titleKey` returns one from a `switch`. So every
    /// snake_case literal under `Features/` must resolve too.
    ///
    /// Core is deliberately excluded: OIDC and Keychain code is full of
    /// snake_case wire names (`access_token`, `refresh_token`, `expires_in`)
    /// that are not keys and never will be. Feature code has none, which is
    /// what makes the rule affordable here — if a genuine non-key literal is
    /// ever needed in a feature, add it to `nonKeyLiterals` with a reason
    /// rather than weakening the rule.
    private static let nonKeyLiterals: Set<String> = []

    func testEveryKeyShapedLiteralInAFeatureResolvesInBothBundles() throws {
        var keys = Set<String>()
        for source in try swiftSources() where source.path.contains("Features") {
            let text = stripComments(try String(contentsOf: source, encoding: .utf8))
            keys.formUnion(matches("\"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\"", in: text))
        }
        keys.subtract(Self.nonKeyLiterals)
        XCTAssertGreaterThan(keys.count, 100, "the scan found almost nothing — check the regex")

        for language in ["en", "fr"] {
            let missing = unresolved(keys, in: try bundle(language))
            XCTAssertTrue(missing.isEmpty,
                          "\(missing.count) key-shaped literal(s) in Features/ resolve to nothing "
                            + "in \(language).lproj: \(missing.joined(separator: ", "))")
        }
    }

    // MARK: - 3. The constructed families, which no scan can see

    /// Keys built at runtime from a wire value. A scan cannot find these, so
    /// they are listed: the enum values come from the backend's own
    /// `DisclosureCategory`, and the role list from the Android app's
    /// `role_*` strings, which is where the French came from.
    func testConstructedKeyFamiliesResolveInBothBundles() throws {
        var keys = Set<String>()
        for category in ["emergency_access", "treatment_access", "shared_with_provider",
                         "insurance", "copy_released", "identity_change", "unknown"] {
            keys.insert("disclosures_category_" + category)
        }
        for role in ["accountant", "admin", "anesthesiologist", "billing_specialist",
                     "claims_reviewer", "doctor", "hospital_admin", "lab_director",
                     "lab_manager", "lab_scientist", "lab_technician", "midwife", "nurse",
                     "patient", "pharmacist", "pharmacy_verifier", "physician",
                     "physiotherapist", "quality_manager", "radiologist", "receptionist",
                     "staff", "super_admin", "surgeon", "technician", "therapist", "user"] {
            keys.insert("disclosures_role_" + role)
        }
        keys.formUnion(["sharing_optout_already_off", "sharing_optout_already_on"])

        for language in ["en", "fr"] {
            let missing = unresolved(keys, in: try bundle(language))
            XCTAssertTrue(missing.isEmpty,
                          "\(missing.count) constructed key(s) resolve to nothing in "
                            + "\(language).lproj: \(missing.joined(separator: ", "))")
        }
    }

    // MARK: - 4. A format string must take the same arguments in both languages

    /// `String(format:)` reads the LOCALIZED string: if the French copy of
    /// `disclosures_summary_emergency` loses its `%d`, or turns it into `%@`,
    /// the French build formats an Int into a `%@` slot and crashes — a bug
    /// that cannot happen in English and so never shows up in review.
    func testFormatSpecifiersMatchBetweenLanguages() throws {
        var keys = Set<String>()
        for source in try swiftSources() {
            let text = stripComments(try String(contentsOf: source, encoding: .utf8))
            keys.formUnion(matches("\"([A-Za-z0-9_]+)\"\\.localized", in: text))
        }
        let english = try bundle("en")
        let french = try bundle("fr")

        for key in keys.sorted() {
            let en = english.localizedString(forKey: key, value: Self.missing, table: nil)
            let fr = french.localizedString(forKey: key, value: Self.missing, table: nil)
            guard en != Self.missing, fr != Self.missing else { continue }
            XCTAssertEqual(matches("%(?:[0-9]+\\$)?([@df])", in: en),
                           matches("%(?:[0-9]+\\$)?([@df])", in: fr),
                           "\(key) takes different format arguments in en and fr: "
                            + "\"\(en)\" vs \"\(fr)\"")
        }
    }
}
