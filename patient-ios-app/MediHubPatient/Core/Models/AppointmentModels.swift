import Foundation

// MARK: - Appointment Models

struct AppointmentDTO: Codable, Identifiable {
    let id: Int?
    let appointmentDate: String?
    let appointmentTime: String?
    let status: String?
    let reason: String?
    let notes: String?
    let doctorName: String?
    let doctorId: Int?
    let departmentName: String?
    let hospitalName: String?
    let type: String?

    var statusDisplay: String { status?.capitalized ?? "Unknown" }

    var statusColor: String {
        switch status?.uppercased() {
        case "SCHEDULED", "CONFIRMED": return "green"
        case "PENDING":                return "yellow"
        case "CANCELLED", "NO_SHOW":   return "red"
        case "COMPLETED":              return "gray"
        default:                       return "blue"
        }
    }
}

// MARK: - Cancel / Reschedule requests

struct CancelAppointmentRequest: Encodable {
    let appointmentId: Int
    let reason: String?
}

struct RescheduleAppointmentRequest: Encodable {
    let appointmentId: Int
    let newDate: String
    let newTime: String
    let reason: String?
}
