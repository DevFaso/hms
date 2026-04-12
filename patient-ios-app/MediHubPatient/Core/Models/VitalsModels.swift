import Foundation

// MARK: - Vitals Models (matches PatientVitalSignResponseDTO)

struct VitalSignDTO: Codable, Identifiable {
    let id: String?
    let patientId: String?
    let registrationId: String?
    let hospitalId: String?
    let recordedByStaffId: String?
    let recordedByAssignmentId: String?
    let recordedByName: String?
    let source: String?
    let temperatureCelsius: Double?
    let heartRateBpm: Int?
    let respiratoryRateBpm: Int?
    let systolicBpMmHg: Int?
    let diastolicBpMmHg: Int?
    let spo2Percent: Int?
    let bloodGlucoseMgDl: Int?
    let weightKg: Double?
    let bodyPosition: String?
    let notes: String?
    let clinicallySignificant: Bool?
    let recordedAt: String?
    let createdAt: String?
    let updatedAt: String?

    // Computed display helpers

    var sourceDisplay: String {
        guard let src = source, !src.isEmpty else { return "—" }
        return src.replacingOccurrences(of: "_", with: " ").capitalized
    }

    var bloodPressureDisplay: String {
        guard let s = systolicBpMmHg, let d = diastolicBpMmHg else { return "—" }
        return "\(s)/\(d) mmHg"
    }

    var heartRateDisplay: String {
        heartRateBpm.map { "\($0) bpm" } ?? "—"
    }

    var temperatureDisplay: String {
        guard let t = temperatureCelsius else { return "—" }
        return String(format: "%.1f °C", t)
    }

    var oxygenDisplay: String {
        spo2Percent.map { "\($0)%" } ?? "—"
    }

    var respiratoryRateDisplay: String {
        respiratoryRateBpm.map { "\($0) breaths/min" } ?? "—"
    }

    var bloodGlucoseDisplay: String {
        bloodGlucoseMgDl.map { "\($0) mg/dL" } ?? "—"
    }

    var weightDisplay: String {
        guard let w = weightKg else { return "—" }
        return String(format: "%.1f kg", w)
    }

    /// Display value for the most prominent reading
    var displayValue: String {
        if let s = systolicBpMmHg, let d = diastolicBpMmHg { return "\(s)/\(d) mmHg" }
        if let hr = heartRateBpm { return "\(hr) bpm" }
        if let t = temperatureCelsius { return String(format: "%.1f °C", t) }
        if let o2 = spo2Percent { return "\(o2)%" }
        if let w = weightKg { return String(format: "%.1f kg", w) }
        if let rr = respiratoryRateBpm { return "\(rr) /min" }
        if let bg = bloodGlucoseMgDl { return "\(bg) mg/dL" }
        return "—"
    }

    /// All available readings for detail display
    var allReadings: [(label: String, value: String)] {
        var readings: [(String, String)] = []
        if let s = systolicBpMmHg, let d = diastolicBpMmHg { readings.append(("Blood Pressure", "\(s)/\(d) mmHg")) }
        if let hr = heartRateBpm { readings.append(("Heart Rate", "\(hr) bpm")) }
        if let rr = respiratoryRateBpm { readings.append(("Respiratory Rate", "\(rr) breaths/min")) }
        if let o2 = spo2Percent { readings.append(("SpO₂", "\(o2)%")) }
        if let t = temperatureCelsius { readings.append(("Temperature", String(format: "%.1f °C", t))) }
        if let bg = bloodGlucoseMgDl { readings.append(("Blood Glucose", "\(bg) mg/dL")) }
        if let w = weightKg { readings.append(("Weight", String(format: "%.1f kg", w))) }
        return readings
    }

    /// Formatted date for display
    var recordedDateDisplay: String {
        guard let raw = recordedAt else { return "—" }
        let isoFormatter = ISO8601DateFormatter()
        isoFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = isoFormatter.date(from: raw) {
            return date.formatted(date: .abbreviated, time: .shortened)
        }
        isoFormatter.formatOptions = [.withInternetDateTime]
        if let date = isoFormatter.date(from: raw) {
            return date.formatted(date: .abbreviated, time: .shortened)
        }
        return String(raw.prefix(10))
    }
}
