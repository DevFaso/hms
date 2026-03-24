import Foundation

@MainActor
final class VitalsViewModel: ObservableObject {
    @Published var vitals: [VitalDto] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var isRecording = false
    @Published var recordSuccess = false

    private let api = APIClient.shared

    func loadVitals() async {
        isLoading = true
        errorMessage = nil
        do {
            vitals = try await api.request(.vitals(limit: 50), type: [VitalDto].self)
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func recordVital(_ request: RecordVitalRequest) async {
        isRecording = true
        errorMessage = nil
        do {
            try await api.request(.recordVital, body: request)
            recordSuccess = true
            await loadVitals()
        } catch {
            errorMessage = error.localizedDescription
        }
        isRecording = false
    }
}

// MARK: - DTOs

struct VitalDto: Decodable, Identifiable {
    let id: String
    let type: String?
    let value: Double?
    let unit: String?
    let recordedAt: String?
    let recordedBy: String?
    let notes: String?
    let source: String?

    var displayValue: String {
        guard let value else { return "—" }
        let formatted = value.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0f", value)
            : String(format: "%.1f", value)
        return "\(formatted) \(unit ?? "")"
    }

    var icon: String {
        switch type?.lowercased() {
        case "blood_pressure", "bloodpressure": return "heart.fill"
        case "heart_rate", "heartrate", "pulse": return "waveform.path.ecg"
        case "temperature":                      return "thermometer"
        case "weight":                           return "scalemass"
        case "height":                           return "ruler"
        case "oxygen_saturation", "spo2":        return "lungs.fill"
        case "respiratory_rate":                 return "wind"
        case "blood_glucose", "glucose":         return "drop.fill"
        default:                                 return "heart.text.square"
        }
    }
}

struct RecordVitalRequest: Encodable {
    let type: String
    let value: Double
    let unit: String
    let notes: String?
}
