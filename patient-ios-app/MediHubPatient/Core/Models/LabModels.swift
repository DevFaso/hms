import Foundation

// MARK: - Lab Result Models

/// B4 — the wire contract is `PatientLabResultResponseDTO`
/// (`hospital-core/.../payload/dto/lab/PatientLabResultResponseDTO.java`),
/// served by every patient-facing lab surface:
///
/// * `GET /me/patient/lab-results`
/// * `GET /me/patient/proxy-access/{patientId}/lab-results`
/// * `HealthSummaryDTO.recentLabResults` (dashboard / health records)
///
/// The previous model decoded `result`, `collectedDate`, `orderedDate`,
/// `resultDate`, `labName`, `isAbnormal` and `critical` — names the API has
/// never sent — so every value, every date and the laboratory were blank on
/// every row, and `abnormal` was permanently false.
///
/// `hospitalId` is deliberately not mapped: it is the provenance UUID the
/// portal uses for cross-hospital accounting and no patient screen can render
/// it. `hospitalName` is the display form.
struct LabResultDTO: Codable, Identifiable {
    let id: String?
    let testName: String?
    let testCode: String?
    let value: String?
    let unit: String?
    let referenceRange: String?
    let status: String?
    /// Whether the laboratory has released the result. The patient path
    /// redacts an unreleased row down to its identity, so this is what tells
    /// a blank value apart from a result that genuinely has none.
    let released: Bool?
    let collectedAt: String?
    let resultedAt: String?
    let orderedBy: String?
    let performedBy: String?
    /// Decoded but not displayed: LabTestDefinition normalises this to
    /// trim().toUpperCase() and the set is hospital-configured, so there is no
    /// closed list to localize it against.
    let category: String?
    let notes: String?
    let hospitalName: String?

    var statusEnum: LabResultStatus { LabResultStatus(wire: status) }

    /// An unreleased row, or one the backend marked PENDING. It carries no
    /// value, no unit, no reference range and no interpretation: rendering it
    /// as a green "normal" result with a blank value was a real defect on the
    /// patient portal and must not be reproduced here.
    var isPending: Bool { released != true || statusEnum == .pending }

    var isCritical: Bool { !isPending && statusEnum == .critical }

    var isAbnormal: Bool { !isPending && statusEnum.isAbnormal }

    var isNormal: Bool { !isPending && statusEnum == .normal }

    /// NORMAL *with a range it could have been inside*. `resolveStatus` falls
    /// through to `statusOf(result.getAbnormalFlag())` and `statusOf(null)` is
    /// also NORMAL, so on the wire "graded normal" and "nothing graded this"
    /// are the same word. The green tick and the green badge are the app's own
    /// reassurance rather than anything the backend asserted, so they are
    /// withheld when there was no range to be inside — a positive qualitative
    /// serology must not read as an all-clear.
    var isGradedNormal: Bool {
        guard isNormal else { return false }
        // A range alone is not proof it was applied: the range comes off the
        // test DEFINITION, while `LabResultMapper.determineSeverityFlag`
        // returns UNSPECIFIED whenever `Double.parseDouble(resultValue)`
        // throws — a censored "<0.5", a qualitative "Positive", or a DECIMAL
        // COMMA, on a test that happens to have numeric limits. The comma is
        // deliberately NOT normalised: the question is not whether the value
        // is a number to a human, it is whether the SERVER could parse it,
        // and `Double.parseDouble` cannot. Normalising would hand a
        // francophone site's "4,2" a green tick for a comparison that never
        // happened.
        //
        // Known cost, accepted: a QUALITATIVE result the analyst did grade —
        // a negative malaria RDT carrying `AbnormalFlag.NORMAL` — is a real
        // all-clear and still loses the green line here, because the DTO does
        // not serve `abnormalFlag` and the app cannot tell it apart from
        // `statusOf(null)`. Under-reassuring is the safe direction, and
        // exposing the flag is reported as backend debt rather than guessed
        // at from this side.
        let range = (referenceRange ?? "").trimmingCharacters(in: .whitespaces)
        guard !range.isEmpty else { return false }
        guard let raw = value?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else { return false }
        guard Double(raw) != nil else { return false }
        return referenceRangeApplies
    }

    /// Whether the reference range on the wire is the one THIS result was
    /// measured against.
    ///
    /// The range SHOWN and the range GRADED AGAINST are not necessarily the
    /// same: `formatReferenceRange` always formats `ranges[0]`, while
    /// `determineSeverityFlag` grades against `findMatchingRange(resultUnit,
    /// …)`. On a test configured with two unit-specific ranges, the row can be
    /// graded NORMAL in mmol/L and displayed against the mg/dL limits.
    ///
    /// Read the limits of this before relying on it. It is a heuristic over
    /// one formatted string, and the payload carries no signal for what it is
    /// really asking, so it is wrong in BOTH directions. All three gaps are
    /// server-side and filed as such; none is visible to the app.
    ///
    ///  * **It misses the headline case whenever `ranges[0]` has no unit.**
    ///    `formatReferenceRange` then stamps the RESULT's unit onto ranges[0]'s
    ///    numbers. A glucose with `ranges[0] = {70, 110, null}` and
    ///    `ranges[1] = {3.9, 6.1, "mmol/L"}` resulted at 5.4 mmol/L is graded
    ///    against ranges[1] but displayed as "70 - 110 mmol/L" — the check
    ///    passes, the tick stays, and the limits now carry a unit they were
    ///    never expressed in. This guard catches the two-range mismatch only
    ///    when ranges[0] carries its own, different, unit.
    ///  * **On the single-range configuration it is a false positive by
    ///    construction.** `findMatchingRange` falls back to `ranges[0]`, which
    ///    is what is displayed, so with one configured range the range shown
    ///    IS always the graded one whatever the unit text says. Every
    ///    notational pair outside the fold below — `U/L` vs `IU/L`,
    ///    `cells/mm3` vs `/mm3`, `mm/h` vs `mm/hr`, `K/uL` vs `10^3/uL`,
    ///    `ng/mL` vs `ug/L` — costs a healthy patient their "Within normal
    ///    range" line and gains them a unit warning.
    ///  * **It may compare the wrong unit.** The DTO's `unit` is
    ///    `resolveUnit()` — the result's unit ELSE the DEFINITION's — while
    ///    grading uses `result.getResultUnit()` alone.
    ///
    /// The fix is for the DTO to say which range was graded against (or to
    /// serve `abnormalFlag`); until then this is a best guess that errs
    /// towards under-reassuring.
    var referenceRangeApplies: Bool {
        let unit = (self.unit ?? "").trimmingCharacters(in: .whitespaces)
        guard !unit.isEmpty else { return true }
        let range = (referenceRange ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return Self.range(range, isIn: unit)
    }

    /// Whether a formatted reference range is expressed in `unit`.
    ///
    /// A SUBSTRING test is not enough: `g/dL` is a substring of `mg/dL`,
    /// `mol/L` of `mmol/L`, `U/L` of `mU/L` — so the very mismatch this guards
    /// against would pass it. `formatReferenceRange` appends `" " + unit`, so
    /// the unit is the suffix and the character before it is a separator;
    /// requiring that boundary also keeps units that contain digits
    /// (`x10^9/L`) working, which trailing-non-digit extraction would not.
    ///
    /// An empty range is accepted: there is no unit to disagree about. There
    /// is deliberately NO "ends in a digit, so it carries no unit" shortcut —
    /// `cells/mm3`, `10^9/L` and `mmol/24h` all end in one, and such a
    /// shortcut handed every CD4 count an unconditional pass. It would also be
    /// unreachable: `formatReferenceRange` falls back to the RESULT's unit
    /// when `ranges[0]` has none, so whenever the row has a unit the formatted
    /// range carries one too.
    ///
    /// Deliberately the same rule, in the same words, as
    /// `LabResultDto.rangeIsInUnit` on Android.
    static func range(_ range: String, isIn unit: String) -> Bool {
        guard !range.trimmingCharacters(in: .whitespaces).isEmpty else { return true }
        let haystack = Array(normalizedUnit(range))
        let needle = Array(normalizedUnit(unit))
        guard !needle.isEmpty else { return true }
        guard haystack.count >= needle.count,
              Array(haystack.suffix(needle.count)) == needle else { return false }
        let boundary = haystack.count - needle.count - 1
        guard boundary >= 0 else { return true }
        let character = haystack[boundary]
        // `formatReferenceRange` emits "<numbers> <unit>", so once the spaces
        // are folded away the character before a WHOLE unit is always the last
        // digit of the numbers. Anything else means the suffix cut a longer
        // unit in half — a letter for `g/dl` inside `mg/dl`, a `/` for `l`
        // inside `mmol/l`, which an "only reject letters" rule waved through.
        // The exceptions are the multiplication markers of `x10^9/L` and
        // `*10^9/L`, which `normalizedUnit` strips off the needle but leaves
        // in the range; no real unit ends `…xg/dL`.
        return character.isNumber || character == "x" || character == "*"
    }

    /// Enough normalisation that a purely COSMETIC difference between the
    /// configured range's unit and the result's does not read as a real one.
    ///
    /// `findMatchingRange` compares `trim().equalsIgnoreCase(...)` and falls
    /// back to `ranges[0]`, so `mm Hg` vs `mmHg`, `µmol/L` vs `umol/L` and
    /// `x10^9/L` vs `10^9/L` all end up grading against the range on screen —
    /// the app must not caveat those. Case, whitespace, the two micro signs
    /// and a leading multiplication marker are therefore folded away. What is
    /// deliberately NOT folded is an SI prefix: `mg` and `g` are a
    /// thousandfold apart and that is the disagreement worth flagging.
    ///
    /// Deliberately the same rule, in the same words, as
    /// `LabResultDto.normalizedUnit` on Android.
    static func normalizedUnit(_ raw: String) -> String {
        var folded = raw.lowercased()
            .replacingOccurrences(of: "\u{00B5}", with: "u") // MICRO SIGN
            .replacingOccurrences(of: "\u{03BC}", with: "u") // GREEK SMALL LETTER MU
            .filter { !$0.isWhitespace }
            // "mcg" is the safety-preferred spelling of µg — the same unit,
            // and common on hand-entered ranges.
            .replacingOccurrences(of: "mcg", with: "ug")
            // "UI" is the French spelling of IU. Bounded so it cannot eat the
            // middle of another token, but with room for ONE SI prefix: a bare
            // `(?<![a-z])ui` refused anything prefixed, so `mUI/L` vs `mIU/L`
            // — the standard units for TSH, FSH, LH and insulin — read as a
            // real mismatch while the unprefixed pair folded.
            .replacingOccurrences(of: "(?<![a-z])([mkndpcuh]?)ui(?![a-z])",
                                  with: "$1iu",
                                  options: .regularExpression)
        if folded.hasPrefix("x") || folded.hasPrefix("*") {
            folded.removeFirst()
        }
        return folded
    }

    /// The reference range to put in front of the patient.
    var displayReferenceRange: String? {
        guard !isPending,
              let range = referenceRange,
              !range.trimmingCharacters(in: .whitespaces).isEmpty
        else { return nil }
        return range
    }

    /// True when the displayed limits MAY not be in the result's units, so the
    /// UI can caveat them.
    ///
    /// Deliberately a caveat rather than a suppression. `findMatchingRange`
    /// falls back to `referenceRanges.get(0)` when no configured range matches
    /// the result unit — and that is exactly the range `formatReferenceRange`
    /// displays — so on the ordinary single-range test whose configured unit
    /// string merely differs cosmetically from the result's (`µmol/L` vs
    /// `umol/L`, `x10^9/L` vs `10^9/L`, `mm Hg` vs `mmHg`), the range shown IS
    /// the range graded against and there is nothing wrong at all. The app
    /// cannot tell that apart from the real multi-range mismatch, so hiding
    /// the limits would blank correct data on what is probably the common
    /// case. The green tick is still withheld either way: under-reassuring is
    /// free, deleting a patient's reference range is not.
    /// NORMAL rows only — and that gate is the whole justification. "Losing a
    /// reassurance line" is a safe way to be wrong; telling a patient whose
    /// potassium is 6.8 that the limits beside their CRITICAL badge "may not
    /// be in the same units" hands them a reason to discount it, which is the
    /// opposite trade. A mmol/L row against a definition configured mEq/L —
    /// numerically identical, textually unfoldable — is exactly that case.
    var referenceRangeUnitUncertain: Bool {
        guard isNormal,
              let range = referenceRange,
              !range.trimmingCharacters(in: .whitespaces).isEmpty
        else { return false }
        return !referenceRangeApplies
    }

    /// What the badge shows: a pending row never borrows a grading.
    var displayStatus: LabResultStatus { isPending ? .pending : statusEnum }

    var statusDisplay: String {
        // Withholding the green but keeping the word "Normal" would leave the
        // claim in place. An ungraded row is reported, not normal.
        if isNormal, !isGradedNormal { return "lab_status_reported".localized }
        return displayStatus.localizedLabel
    }

    var tone: StatusTone {
        if isNormal, !isGradedNormal { return .neutral }
        return displayStatus.tone
    }

    /// The SF Symbol every lab surface draws for this row. It lives here, not
    /// in a view, because the lab list and the dashboard card had identical
    /// copies of it and their comments had already drifted apart — the next
    /// change to the pending/graded rules has to land on both at once.
    ///
    /// A pending row is never a green tick: the lab has not released it and
    /// there is nothing to be reassured by. Neither is a released row whose
    /// status this build cannot name, nor one that nothing graded — see
    /// `isGradedNormal`.
    var symbolName: String {
        if isPending { return "hourglass" }
        if displayStatus == .unknown { return "questionmark.circle" }
        if isAbnormal || isCritical { return "exclamationmark.triangle.fill" }
        return isGradedNormal ? "checkmark.circle.fill" : "testtube.2"
    }

    /// The value with its unit, or nil while the result is pending.
    var valueWithUnit: String? {
        guard !isPending,
              let raw = value?.trimmingCharacters(in: .whitespaces), !raw.isEmpty
        else { return nil }
        if let unit = unit?.trimmingCharacters(in: .whitespaces), !unit.isEmpty {
            return "\(raw) \(unit)"
        }
        return raw
    }
}

/// The statuses `PatientLabResultServiceImpl` can put on the wire — NORMAL,
/// ABNORMAL, ABNORMAL_LOW, ABNORMAL_HIGH, CRITICAL, and PENDING while the row
/// is unreleased. `unknown` is the app's own fallback for a value the backend
/// adds later; every `switch` below is exhaustive and carries no `default`,
/// so the compiler refuses to build once a case is added without a label.
enum LabResultStatus: String, CaseIterable {
    case pending = "PENDING"
    case normal = "NORMAL"
    case abnormal = "ABNORMAL"
    case abnormalLow = "ABNORMAL_LOW"
    case abnormalHigh = "ABNORMAL_HIGH"
    case critical = "CRITICAL"
    case unknown = "__UNKNOWN__"

    init(wire: String?) {
        let trimmed = (wire ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        self = LabResultStatus(rawValue: trimmed) ?? .unknown
    }

    /// The `Localizable.strings` key, in `values`/`values-fr` on Android too.
    var labelKey: String {
        switch self {
        case .pending: "lab_status_pending"
        case .normal: "lab_status_normal"
        case .abnormal: "lab_status_abnormal"
        case .abnormalLow: "lab_status_abnormal_low"
        case .abnormalHigh: "lab_status_abnormal_high"
        case .critical: "lab_status_critical"
        case .unknown: "lab_status_unknown"
        }
    }

    var localizedLabel: String { labelKey.localized }

    var isAbnormal: Bool {
        switch self {
        case .abnormal, .abnormalLow, .abnormalHigh: true
        case .pending, .normal, .critical, .unknown: false
        }
    }

    var tone: StatusTone {
        switch self {
        case .normal: .positive
        case .abnormal, .abnormalLow, .abnormalHigh: .attention
        case .critical: .negative
        case .pending, .unknown: .neutral
        }
    }

    /// Every status the backend can actually send — `unknown` is ours.
    static var wireCases: [LabResultStatus] { allCases.filter { $0 != .unknown } }
}

/// How a status should read at a glance, in the colour vocabulary
/// `StatusBadge` already speaks. Kept out of the views so the mapping is
/// testable and shared by every screen that shows a badge.
enum StatusTone: String, CaseIterable {
    case positive
    case attention
    case negative
    case neutral

    var badgeColor: String {
        switch self {
        case .positive: "green"
        case .attention: "orange"
        case .negative: "red"
        case .neutral: "gray"
        }
    }
}
