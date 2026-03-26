import Foundation

// MARK: - Vitals Models (matches VitalSignSummary)

struct VitalSignDTO: Codable, Identifiable {
    let id: String?
    let type: String?
    let value: String?
    let unit: String?
    let recordedAt: String?
    let source: String?

    // Legacy compat — for detailed vitals
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
        let u = temperatureUnit ?? "°C"
        return String(format: "%.1f %@", t, u)
    }

    var oxygenDisplay: String {
        oxygenSaturation.map { String(format: "%.0f%%", $0) } ?? "—"
    }

    /// Icon for the vital type
    var typeIcon: String {
        switch type?.lowercased() {
        case "blood_pressure", "blood pressure":  return "waveform.path.ecg"
        case "heart_rate", "heart rate":           return "heart.fill"
        case "temperature":                        return "thermometer"
        case "spo2", "oxygen", "o2":               return "lungs.fill"
        case "weight":                             return "scalemass.fill"
        case "respiratory_rate", "respiratory rate": return "wind"
        case "blood_glucose", "glucose":           return "drop.fill"
        default:                                   return "heart.text.square"
        }
    }

    /// Display value: prefer type/value pair, fall back to individual fields
    var displayValue: String {
        if let v = value, let u = unit { return "\(v) \(u)" }
        if let v = value { return v }
        return "—"
    }
}

// MARK: - Record home vital request (matches HomeVitalReading)

struct RecordVitalRequest: Encodable {
    let systolicBpMmHg: Int?
    let diastolicBpMmHg: Int?
    let heartRateBpm: Int?
    let temperatureCelsius: Double?
    let spo2Percent: Double?
    let respiratoryRateBpm: Int?
    let bloodGlucoseMgDl: Double?
    let weightKg: Double?
    let bodyPosition: String?
    let notes: String?
}
