import XCTest

/// xcconfig treats `//` as the start of a comment ANYWHERE on a line, so
/// `MEDIHUB_KEYCLOAK_ISSUER = https://host/realms/hms` is silently read as
/// `https:`. Both Release configurations shipped that way; the API URL line
/// next to it had the `https:/$()/` escape. The PR job builds Debug, which
/// never layers these files, so nothing else would notice.
final class XcconfigValueTests: XCTestCase {

    private static var configDirectory: URL {
        URL(fileURLWithPath: #filePath)       // …/MediHubPatientTests/XcconfigValueTests.swift
            .deletingLastPathComponent()      // …/MediHubPatientTests
            .deletingLastPathComponent()      // …/patient-ios-app
            .appendingPathComponent("Config")
    }

    func testNoSettingValueContainsAnUnescapedDoubleSlash() throws {
        let files = try FileManager.default.contentsOfDirectory(at: Self.configDirectory,
                                                                includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "xcconfig" }
        XCTAssertGreaterThanOrEqual(files.count, 2, "Config/*.xcconfig not found — this test checked nothing")

        var offending: [String] = []
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            for (index, line) in text.components(separatedBy: .newlines).enumerated() {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                // A setting line is `NAME = value`; a line that starts with
                // `//` is a comment and may say anything.
                guard !trimmed.hasPrefix("//"), let equals = trimmed.firstIndex(of: "=") else { continue }
                let value = trimmed[trimmed.index(after: equals)...]
                if value.contains("//") {
                    offending.append("\(file.lastPathComponent):\(index + 1): \(trimmed)")
                }
            }
        }
        XCTAssertTrue(offending.isEmpty,
                      "write URLs as https:/$()/host — an unescaped // truncates the value: "
                        + offending.joined(separator: " | "))
    }
}
