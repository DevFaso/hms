import Foundation

// MARK: - Appointment Models

struct AppointmentDTO: Codable, Identifiable, Hashable {
    let id: String?
    let patientId: String?
    let patientName: String?
    let patientEmail: String?
    let patientPhone: String?
    let staffId: String?
    let staffName: String?
    let staffEmail: String?
    let hospitalId: String?
    let hospitalName: String?
    let hospitalAddress: String?
    let treatmentId: String?
    let treatmentName: String?
    let treatmentDescription: String?
    let createdById: String?
    let createdByName: String?
    let reason: String?
    let notes: String?
    let appointmentDate: String?
    let startTime: String?
    let endTime: String?
    let departmentId: String?
    let status: String?
    let createdAt: String?
    let updatedAt: String?

    /// Convenience: display name of the doctor/staff
    var doctorName: String? { staffName }

    /// Formatted time range, e.g. "08:04 – 08:34"
    var timeRange: String? {
        guard let s = startTime, let e = endTime else { return startTime }
        let fmt: (String) -> String = { t in String(t.prefix(5)) } // "08:04:00" → "08:04"
        return "\(fmt(s)) – \(fmt(e))"
    }

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
    let appointmentId: String
    let reason: String?
}

struct RescheduleAppointmentRequest: Encodable {
    let appointmentId: String
    let newDate: String
    let newStartTime: String
    let newEndTime: String
    let reason: String?
}
