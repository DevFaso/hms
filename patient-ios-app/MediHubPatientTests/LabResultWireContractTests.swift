import XCTest
@testable import MediHubPatient

/// B4 — the wire contract for every patient-facing lab surface is
/// `PatientLabResultResponseDTO`. The JSON below is that DTO's own field
/// spelling; the model used to decode `result`, `collectedDate`,
/// `resultDate` and `labName`, so every one of these assertions failed as a
/// nil.
final class LabResultWireContractTests: XCTestCase {

    private func decode(_ json: String) throws -> LabResultDTO {
        let data = Data(json.utf8)
        return try JSONDecoder().decode(LabResultDTO.self, from: data)
    }

    func testReleasedResultDecodesEveryFieldTheDtoServes() throws {
        let lab = try decode("""
        {
          "id": "9c1a1f1e-0000-4000-8000-000000000001",
          "testName": "Haemoglobin",
          "testCode": "HGB",
          "value": "11.2",
          "unit": "g/dL",
          "referenceRange": "12 - 16 g/dL",
          "status": "ABNORMAL_LOW",
          "released": true,
          "collectedAt": "2026-09-20T08:30:00",
          "resultedAt": "2026-09-20T14:05:00",
          "orderedBy": "Dr Awa Traore",
          "performedBy": "Moussa Diarra",
          "category": "HAEMATOLOGY",
          "notes": "Repeat in two weeks",
          "hospitalId": "9c1a1f1e-0000-4000-8000-0000000000ff",
          "hospitalName": "Hopital Gabriel Toure"
        }
        """)

        XCTAssertEqual(lab.id, "9c1a1f1e-0000-4000-8000-000000000001")
        XCTAssertEqual(lab.testName, "Haemoglobin")
        XCTAssertEqual(lab.testCode, "HGB")
        XCTAssertEqual(lab.value, "11.2")
        XCTAssertEqual(lab.unit, "g/dL")
        XCTAssertEqual(lab.referenceRange, "12 - 16 g/dL")
        XCTAssertEqual(lab.released, true)
        XCTAssertEqual(lab.collectedAt, "2026-09-20T08:30:00")
        XCTAssertEqual(lab.resultedAt, "2026-09-20T14:05:00")
        XCTAssertEqual(lab.orderedBy, "Dr Awa Traore")
        XCTAssertEqual(lab.performedBy, "Moussa Diarra")
        XCTAssertEqual(lab.category, "HAEMATOLOGY")
        XCTAssertEqual(lab.notes, "Repeat in two weeks")
        XCTAssertEqual(lab.hospitalName, "Hopital Gabriel Toure")

        XCTAssertEqual(lab.valueWithUnit, "11.2 g/dL")
        XCTAssertEqual(lab.statusEnum, .abnormalLow)
        XCTAssertFalse(lab.isPending)
        XCTAssertTrue(lab.isAbnormal)
        XCTAssertFalse(lab.isCritical)
        XCTAssertFalse(lab.isNormal)
        XCTAssertEqual(lab.tone, .attention)
        XCTAssertEqual(lab.tone.badgeColor, "orange")
    }

    /// The patient path redacts an unreleased row: identity and PENDING only.
    /// Rendering that as a green "normal" result with a blank value was a real
    /// defect on the portal — the model must not let a screen do it here.
    func testUnreleasedResultReadsAsPendingAndCarriesNoValue() throws {
        let lab = try decode("""
        {
          "id": "9c1a1f1e-0000-4000-8000-000000000002",
          "testName": "Fasting glucose",
          "testCode": "GLU",
          "status": "PENDING",
          "released": false,
          "collectedAt": "2026-09-22T07:10:00",
          "hospitalName": "Hopital Gabriel Toure"
        }
        """)

        XCTAssertTrue(lab.isPending)
        XCTAssertNil(lab.value)
        XCTAssertNil(lab.valueWithUnit)
        XCTAssertNil(lab.referenceRange)
        XCTAssertFalse(lab.isNormal)
        XCTAssertFalse(lab.isAbnormal)
        XCTAssertFalse(lab.isCritical)
        XCTAssertEqual(lab.displayStatus, .pending)
        XCTAssertEqual(lab.tone, .neutral)
        XCTAssertEqual(lab.tone.badgeColor, "gray")
    }

    /// Belt and braces: even if a row arrived unreleased but graded — which is
    /// what the STAFF projection does — nothing on a patient screen may show
    /// it as a finished result.
    func testUnreleasedRowIsPendingEvenWhenItCarriesAGrading() throws {
        let lab = try decode("""
        {
          "id": "x",
          "testName": "Potassium",
          "value": "6.9",
          "unit": "mmol/L",
          "status": "CRITICAL",
          "released": false
        }
        """)

        XCTAssertTrue(lab.isPending)
        XCTAssertFalse(lab.isCritical)
        XCTAssertNil(lab.valueWithUnit)
        XCTAssertEqual(lab.displayStatus, .pending)
    }

    func testMissingReleasedFlagIsTreatedAsPending() throws {
        let lab = try decode("""
        { "id": "y", "testName": "Sodium", "status": "NORMAL" }
        """)

        XCTAssertTrue(lab.isPending)
        XCTAssertFalse(lab.isNormal)
    }

    /// `statusOf(null)` is NORMAL, so "graded normal" and "nothing graded
    /// this" arrive as the same word. Only the first earns the green tick.
    func testNormalWithoutAReferenceRangeIsNotAnAllClear() throws {
        let ungraded = try decode("""
        {
          "id": "z", "testName": "Malaria RDT", "value": "Positive",
          "status": "NORMAL", "released": true
        }
        """)
        XCTAssertTrue(ungraded.isNormal)
        XCTAssertFalse(ungraded.isGradedNormal)
        XCTAssertEqual(ungraded.tone, .neutral)

        let graded = try decode("""
        {
          "id": "z2", "testName": "Sodium", "value": "140", "unit": "mmol/L",
          "referenceRange": "135 - 145 mmol/L", "status": "NORMAL", "released": true
        }
        """)
        XCTAssertTrue(graded.isGradedNormal)
        XCTAssertEqual(graded.tone, .positive)

        // A range on the test DEFINITION is not proof the value was compared:
        // determineSeverityFlag returns UNSPECIFIED when the value will not
        // parse, and resolveStatus then falls through to NORMAL anyway.
        for unparsable in ["Positive", "<0.5", ">12"] {
            let row = try decode("""
            {
              "id": "z3", "testName": "Serology", "value": "\(unparsable)",
              "referenceRange": "135 - 145", "status": "NORMAL", "released": true
            }
            """)
            XCTAssertFalse(row.isGradedNormal, "\(unparsable) must not read as graded")
            XCTAssertEqual(row.tone, .neutral)
        }

        // A decimal comma is a real value to a human, but NOT to
        // Double.parseDouble on the server — so the backend never compared it
        // either, and the app must not treat it as graded.
        let comma = try decode("""
        {
          "id": "z4", "testName": "Potassium", "value": "4,2",
          "referenceRange": "3.5 - 5.1", "status": "NORMAL", "released": true
        }
        """)
        XCTAssertFalse(comma.isGradedNormal)
        XCTAssertEqual(comma.tone, .neutral)

        // formatReferenceRange formats ranges[0] while determineSeverityFlag
        // grades against findMatchingRange(unit, …): a row resulted in mmol/L
        // against a first range in mg/dL is an all-clear beside limits it is
        // nowhere near.
        let wrongUnit = try decode("""
        {
          "id": "z5", "testName": "Glucose", "value": "5.4", "unit": "mmol/L",
          "referenceRange": "70 - 110 mg/dL", "status": "NORMAL", "released": true
        }
        """)
        XCTAssertFalse(wrongUnit.isGradedNormal)
        XCTAssertEqual(wrongUnit.tone, .neutral)

        let matchingUnit = try decode("""
        {
          "id": "z6", "testName": "Glucose", "value": "5.4", "unit": "mmol/L",
          "referenceRange": "3.9 - 6.1 mmol/L", "status": "NORMAL", "released": true
        }
        """)
        XCTAssertTrue(matchingUnit.isGradedNormal)

        // A substring test would pass all three of these: g/dL is inside
        // mg/dL, mol/L inside mmol/L, U/L inside mU/L.
        for (rowUnit, shownRange) in [("g/dL", "70 - 110 mg/dL"),
                                      ("mol/L", "3.9 - 6.1 mmol/L"),
                                      ("U/L", "10 - 40 mU/L")] {
            XCTAssertFalse(LabResultDTO.range(shownRange, isIn: rowUnit),
                           "\(rowUnit) must not match \(shownRange)")
        }

        // A unit that contains digits still matches itself; a unit that ENDS
        // in one is still compared rather than waved through.
        XCTAssertTrue(LabResultDTO.range("4 - 11 x10^9/L", isIn: "x10^9/L"))
        XCTAssertFalse(LabResultDTO.range("500 - 1500 cells/mm3", isIn: "10^9/L"))
        XCTAssertTrue(LabResultDTO.range("0.5 - 1.5 10^9/L", isIn: "10^9/L"))
        // Only a genuinely empty range is passed.
        XCTAssertTrue(LabResultDTO.range("", isIn: "mmol/L"))
        XCTAssertFalse(LabResultDTO.range("3.9 - 6.1", isIn: "mmol/L"))
    }

    /// Shown, not hidden: `findMatchingRange` falls back to `ranges[0]`, so
    /// the displayed range may well BE the graded one and the app cannot tell.
    /// The tick is withheld either way, which is the free half of the guard.
    func testAReferenceRangeInAnotherUnitIsShownWithACaveat() throws {
        let mismatched = try decode("""
        {
          "id": "m", "testName": "Glucose", "value": "5.4", "unit": "mmol/L",
          "referenceRange": "70 - 110 mg/dL", "status": "NORMAL", "released": true
        }
        """)
        XCTAssertEqual(mismatched.displayReferenceRange, "70 - 110 mg/dL")
        XCTAssertTrue(mismatched.referenceRangeUnitUncertain)
        XCTAssertFalse(mismatched.isGradedNormal)

        let matched = try decode("""
        {
          "id": "m2", "testName": "Glucose", "value": "5.4", "unit": "mmol/L",
          "referenceRange": "3.9 - 6.1 mmol/L", "status": "NORMAL", "released": true
        }
        """)
        XCTAssertEqual(matched.displayReferenceRange, "3.9 - 6.1 mmol/L")
        XCTAssertFalse(matched.referenceRangeUnitUncertain)
        XCTAssertTrue(matched.isGradedNormal)

        // A pending row has neither: the backend redacts the range, and there
        // is nothing to caveat.
        let pending = try decode("""
        {
          "id": "m3", "testName": "Glucose", "status": "PENDING", "released": false
        }
        """)
        XCTAssertNil(pending.displayReferenceRange)
        XCTAssertFalse(pending.referenceRangeUnitUncertain)
    }

    func testUnknownOrMissingStatusFallsBackInsteadOfRenderingTheRawName() {
        XCTAssertEqual(LabResultStatus(wire: nil), .unknown)
        XCTAssertEqual(LabResultStatus(wire: ""), .unknown)
        XCTAssertEqual(LabResultStatus(wire: "   "), .unknown)
        XCTAssertEqual(LabResultStatus(wire: "SOMETHING_NEW"), .unknown)
        XCTAssertEqual(LabResultStatus(wire: " critical "), .critical)
    }

    /// Every status `PatientLabResultServiceImpl` can put on the wire as of
    /// this change, not a sample. Hand-copied, like the prescription one: it
    /// pins the app enum against a contract a reader can check by eye, and
    /// catches an app-side edit that drops a case. A status added on the
    /// backend reaches the app as `.unknown`, not as a raw `ABNORMAL_HIGH`.
    func testEveryBackendLabStatusIsNamedByTheEnum() {
        let backend: Set<String> = [
            "NORMAL", "ABNORMAL", "ABNORMAL_LOW", "ABNORMAL_HIGH", "CRITICAL", "PENDING"
        ]
        XCTAssertEqual(Set(LabResultStatus.wireCases.map(\.rawValue)), backend)
    }

    /// The old `isAbnormal` was `status == "ABNORMAL"`, so `ABNORMAL_LOW` and
    /// `ABNORMAL_HIGH` both rendered green.
    func testBothDirectionalAbnormalsCountAsAbnormal() {
        XCTAssertTrue(LabResultStatus.abnormal.isAbnormal)
        XCTAssertTrue(LabResultStatus.abnormalLow.isAbnormal)
        XCTAssertTrue(LabResultStatus.abnormalHigh.isAbnormal)
        XCTAssertFalse(LabResultStatus.normal.isAbnormal)
        XCTAssertFalse(LabResultStatus.critical.isAbnormal)
        XCTAssertFalse(LabResultStatus.pending.isAbnormal)
    }
}
