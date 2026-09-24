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
        // throws — a decimal comma, a censored "<0.5", a qualitative
        // "Positive" on a test that happens to have numeric limits. The value
        // has to be something the backend could actually compare.
        guard !(referenceRange ?? "").trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        guard let raw = value?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else { return false }
        return Double(raw.replacingOccurrences(of: ",", with: ".")) != nil
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
