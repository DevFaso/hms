import Foundation

// MARK: - Appointment Models

struct AppointmentDTO: Codable, Identifiable, Hashable {
    let id: String?
    let patientId: String?
    let patientName: String?
    let patientEmail: String?
    let patientPhone: String?
    let staffId: String?
    /// The clinician's USER id — what /chat/send needs as a recipient.
    /// `staffId` is a Staff row id and is not interchangeable with it.
    /// Android has always decoded this; iOS did not, which is why the
    /// composer had no way to address anyone.
    let staffUserId: String?
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
    // Pre-check-in state, set by POST /me/patient/appointments/{id}/pre-checkin.
    let preCheckedIn: Bool?
    let preCheckinTimestamp: String?

    /// Convenience: display name of the doctor/staff
    var doctorName: String? {
        staffName
    }

    /// Formatted time range, e.g. "08:04 – 08:34"
    var timeRange: String? {
        guard let s = startTime, let e = endTime else { return startTime }
        let fmt: (String) -> String = { t in String(t.prefix(5)) } // "08:04:00" → "08:04"
        return "\(fmt(s)) – \(fmt(e))"
    }

    var statusDisplay: String {
        status?.capitalized ?? "Unknown"
    }

    var statusColor: String {
        switch status?.uppercased() {
        case "SCHEDULED", "CONFIRMED": "green"
        case "PENDING": "yellow"
        case "CANCELLED", "NO_SHOW": "red"
        case "COMPLETED": "gray"
        default: "blue"
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

// MARK: - Book Appointment request (POST /me/patient/appointments)

/// PortalBookAppointmentRequestDTO. The backend checks the registration,
/// that the department belongs to the hospital, and assigns the first
/// available provider when staffId is nil; endTime defaults to
/// startTime + 30 min. reason <= 500, notes <= 1000.
struct BookAppointmentRequest: Encodable {
    let hospitalId: String
    let departmentId: String
    let staffId: String?
    let date: String // yyyy-MM-dd
    let startTime: String // HH:mm
    let endTime: String? // HH:mm, nil = server default
    let reason: String?
    let notes: String?
}

// MARK: - Booking wizard lists (GET /me/patient/booking/…)

/// Where the patient holds an active registration.
struct BookingHospitalDTO: Decodable, Identifiable {
    let id: String
    let name: String?
    let address: String?
}

struct BookingDepartmentDTO: Decodable, Identifiable {
    let id: String
    let name: String?
}

struct BookingProviderDTO: Decodable, Identifiable {
    let id: String
    let name: String?
    let fullName: String?
    /// A raw ROLE_* constant with no translation; the sheet shows the name only, as the web does.
    let role: String?

    var displayName: String {
        if let fullName, !fullName.trimmingCharacters(in: .whitespaces).isEmpty { return fullName }
        return name ?? id
    }
}
