import Foundation

@MainActor
final class AppointmentViewModel: ObservableObject {
    @Published var upcoming: [AppointmentDTO] = []
    @Published var past: [AppointmentDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let all: [AppointmentDTO] = try await api.request(.appointments, type: [AppointmentDTO].self)
            let now = Date()
            upcoming = all.filter { $0.parsedDate ?? .distantPast >= now }
                .sorted { ($0.parsedDate ?? .distantPast) < ($1.parsedDate ?? .distantPast) }
            past = all.filter { $0.parsedDate ?? .distantPast < now }
                .sorted { ($0.parsedDate ?? .distantPast) > ($1.parsedDate ?? .distantPast) }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func cancel(appointmentId: String) async {
        do {
            try await api.request(.cancelAppointment, body: CancelRequest(appointmentId: appointmentId))
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func reschedule(appointmentId: String, newDateTime: String) async {
        do {
            try await api.request(.rescheduleAppointment, body: RescheduleRequest(appointmentId: appointmentId, newDateTime: newDateTime))
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - DTOs

struct AppointmentDTO: Decodable, Identifiable {
    let id: String
    let appointmentDate: String?
    let appointmentTime: String?
    let status: String?
    let appointmentType: String?
    let reason: String?
    let notes: String?
    let doctorName: String?
    let departmentName: String?
    let hospitalName: String?
    let location: String?

    var parsedDate: Date? {
        guard let dateStr = appointmentDate else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        if let time = appointmentTime {
            formatter.dateFormat = "yyyy-MM-dd HH:mm"
            return formatter.date(from: "\(dateStr) \(time)")
        }
        return formatter.date(from: dateStr)
    }

    var displayDate: String {
        guard let date = parsedDate else { return appointmentDate ?? "N/A" }
        let fmt = DateFormatter()
        fmt.dateStyle = .medium
        return fmt.string(from: date)
    }

    var displayTime: String {
        appointmentTime ?? "N/A"
    }

    var statusColor: String {
        switch status?.uppercased() {
        case "SCHEDULED", "CONFIRMED": return "hmsSuccess"
        case "COMPLETED": return "hmsInfo"
        case "CANCELLED", "NO_SHOW": return "hmsError"
        case "PENDING": return "hmsWarning"
        default: return "hmsTextSecondary"
        }
    }
}

struct CancelRequest: Encodable {
    let appointmentId: String
}

struct RescheduleRequest: Encodable {
    let appointmentId: String
    let newDateTime: String
}
