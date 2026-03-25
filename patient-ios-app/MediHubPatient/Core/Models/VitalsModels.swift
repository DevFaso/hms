import Foundation

// MARK: - Vitals Models

struct VitalSignDTO: Codable, Identifiable {
    let id: Int?
    let recordedAt: String?
    let systolicBP: Int?
    let diastolicBP: Int?
    let heartRate: Int?
    let temperature: Double?
    let temperatureUnit: String?
    let weight: Double?
    let weightUnit: String?
    let height: Double?
    let heightUnit: String?
    let oxygenSaturation: Double?
    let respiratoryRate: Int?
    let bloodGlucose: Double?
    let notes: String?
    let recordedBy: String?

    var bloodPressureDisplay: String {
        guard let s = systolicBP, let d = diastolicBP else { return "—" }
        return "\(s)/\(d) mmHg"
    }

    var heartRateDisplay: String {
        heartRate.map { "\($0) bpm" } ?? "—"
    }

    var temperatureDisplay: String {
        guard let t = temperature else { return "—" }
        let unit = temperatureUnit ?? "°C"
        return String(format: "%.1f %@", t, unit)
    }

    var oxygenDisplay: String {
        oxygenSaturation.map { String(format: "%.0f%%", $0) } ?? "—"
    }
}

// MARK: - Record home vital request

struct RecordVitalRequest: Encodable {
    let systolicBP: Int?
    let diastolicBP: Int?
    let heartRate: Int?
    let temperature: Double?
    let temperatureUnit: String?
    let weight: Double?
    let weightUnit: String?
    let oxygenSaturation: Double?
    let respiratoryRate: Int?
    let bloodGlucose: Double?
    let notes: String?
}
