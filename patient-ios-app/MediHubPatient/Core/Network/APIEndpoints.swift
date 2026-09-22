import Foundation

// MARK: - All patient portal API endpoints

// Mirrors patient-mobile-app/src/services/portalService.js

enum APIEndpoints {
    // MARK: Auth

    static let login = "/auth/login"
    static let logout = "/auth/logout"
    static let tokenRefresh = "/auth/token/refresh"
    static let verifyPassword = "/auth/verify-password"
    static let register = "/auth/register"
    static let requestPasswordReset = "/auth/password/request-reset"
    static let resetPassword = "/auth/password/reset-password"
    static let resendVerification = "/auth/resend-verification"

    // MARK: Patient Portal — /me/patient/*

    static let profile = "/me/patient/profile"
    static let updateProfile = "/me/patient/profile" // PUT
    static let healthSummary = "/me/patient/health-summary"
    static let appointments = "/me/patient/appointments"
    static let cancelAppointment = "/me/patient/appointments/cancel"
    // PRO self-screenings: bare DTOs, not the usual wrapper (the client tries both).
    static let myScreenings = "/me/patient/pro-screenings"
    static func screeningInstrument(code: String) -> String {
        "/me/patient/pro-instruments/" + (code.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? code)
    }
    // Pre-check-in, the same two calls as the web's form.
    static func appointmentQuestionnaires(id: String) -> String { "/me/patient/appointments/\(id)/questionnaires" }
    static func preCheckIn(id: String) -> String { "/me/patient/appointments/\(id)/pre-checkin" }
    static let rescheduleAppointment = "/me/patient/appointments/reschedule"
    static let labResults = "/me/patient/lab-results"
    static let medications = "/me/patient/medications"
    static let prescriptions = "/me/patient/prescriptions"
    static let refills = "/me/patient/refills"
    static let invoices = "/me/patient/billing/invoices"
    static let pharmacyPayments = "/me/patient/pharmacy/payments"
    static let pharmacyClaims = "/me/patient/pharmacy/claims"
    static let encounters = "/me/patient/encounters"
    static let afterVisitSummaries = "/me/patient/after-visit-summaries"
    static let careTeam = "/me/patient/care-team"
    static let vitals = "/me/patient/vitals"
    static let immunizations = "/me/patient/immunizations"
    static let consultations = "/me/patient/consultations"
    static let consents = "/me/patient/consents"
    static let accessLog = "/me/patient/access-log"
    static let referrals = "/me/patient/referrals"
    static let treatmentPlans = "/me/patient/treatment-plans"
    static let documents = "/me/patient/documents"
    /// Authenticated, owner-checked stream; document bytes have no public URL.
    static func documentDownload(id: String) -> String { "/me/patient/documents/\(id)/download" }

    // MARK: Notifications

    static let notifications = "/me/patient/notifications"
    static let notificationUnreadCount = "/me/patient/notifications/unread-count"
    static let markAllNotificationsRead = "/me/patient/notifications/read-all"
    static func markNotificationRead(id: String) -> String {
        "/me/patient/notifications/\(id)/read"
    }

    // MARK: Chat / Messages

    // ChatController is @RequestMapping("/chat") and is keyed by USER ids,
    // not by a thread id. The previous "/me/chat/threads" paths matched no
    // controller at all, so the Messages tab always 404'd — and the call
    // sites swallowed the error with `try?`, which is why it looked empty
    // rather than broken.
    static func chatConversations(userId: String) -> String {
        "/chat/conversations/\(userId)"
    }
    static func chatHistory(userId: String, otherUserId: String) -> String {
        // The endpoint defaults to size=20; Android asks for 100 on the same
        // path. Without it a thread is silently truncated to its 20 newest
        // messages with no way to scroll further back.
        "/chat/history/\(userId)/\(otherUserId)?page=0&size=100"
    }
    static let chatSend = "/chat/send"

    // MARK: Billing actions

    static func payInvoice(id: String) -> String {
        "/me/patient/billing/invoices/\(id)/pay"
    }

    // MARK: Proxy / Family Access

    static let proxies = "/me/patient/proxies"
    static let proxyAccess = "/me/patient/proxy-access"
    static func revokeProxy(id: String) -> String {
        "/me/patient/proxies/\(id)"
    }

    // What a proxy may read on the grantor's behalf, one endpoint per
    // permission. Android and the web have called these since the feature
    // shipped; iOS listed the grant and could open none of it.
    static func proxyAppointments(patientId: String) -> String { "/me/patient/proxy-access/\(patientId)/appointments" }
    static func proxyMedications(patientId: String) -> String { "/me/patient/proxy-access/\(patientId)/medications" }
    static func proxyLabResults(patientId: String) -> String { "/me/patient/proxy-access/\(patientId)/lab-results" }
    static func proxyBilling(patientId: String) -> String { "/me/patient/proxy-access/\(patientId)/billing" }
    static func proxyRecords(patientId: String) -> String { "/me/patient/proxy-access/\(patientId)/records" }

    // MARK: Consent actions

    static func revokeConsent(fromHospitalId: String, toHospitalId: String) -> String {
        "/me/patient/consents?fromHospitalId=\(fromHospitalId)&toHospitalId=\(toHospitalId)"
    }

    // MARK: Refills helpers

    static func cancelRefill(id: String) -> String {
        "/me/patient/refills/\(id)/cancel"
    }

    // MARK: Appointment booking — the patient portal's own endpoint, which
    // verifies the registration and the hospital/department pairing (the
    // staff POST /appointments skips both), plus the wizard's three lists.

    static let bookAppointment = "/me/patient/appointments"
    static let bookingHospitals = "/me/patient/booking/hospitals"
    static func bookingDepartments(hospitalId: String) -> String {
        "/me/patient/booking/hospitals/\(hospitalId)/departments"
    }
    static func bookingProviders(hospitalId: String, departmentId: String) -> String {
        "/me/patient/booking/hospitals/\(hospitalId)/departments/\(departmentId)/providers"
    }

    // MARK: File upload

    static let uploadProfileImage = "/files/profile-image"
    static let deleteProfileImage = "/files/profile-image"
}
