import Foundation

// MARK: - All patient portal API endpoints
// Mirrors patient-mobile-app/src/services/portalService.js

enum APIEndpoints {

    // MARK: Auth
    static let login                = "/auth/login"
    static let logout               = "/auth/logout"
    static let tokenRefresh         = "/auth/token/refresh"
    static let verifyPassword       = "/auth/verify-password"
    static let register             = "/auth/register"
    static let requestPasswordReset = "/auth/password/request-reset"
    static let resetPassword        = "/auth/password/reset-password"
    static let resendVerification   = "/auth/resend-verification"

    // MARK: Patient Portal — /me/patient/*
    static let profile              = "/me/patient/profile"
    static let healthSummary        = "/me/patient/health-summary"
    static let appointments         = "/me/patient/appointments"
    static let cancelAppointment    = "/me/patient/appointments/cancel"
    static let rescheduleAppointment = "/me/patient/appointments/reschedule"
    static let labResults           = "/me/patient/lab-results"
    static let medications          = "/me/patient/medications"
    static let prescriptions        = "/me/patient/prescriptions"
    static let refills              = "/me/patient/refills"
    static let invoices             = "/me/patient/billing/invoices"
    static let encounters           = "/me/patient/encounters"
    static let afterVisitSummaries  = "/me/patient/after-visit-summaries"
    static let careTeam             = "/me/patient/care-team"
    static let vitals               = "/me/patient/vitals"
    static let immunizations        = "/me/patient/immunizations"
    static let consultations        = "/me/patient/consultations"
    static let consents             = "/me/patient/consents"
    static let accessLog            = "/me/patient/access-log"
    static let referrals            = "/me/patient/referrals"
    static let treatmentPlans       = "/me/patient/treatment-plans"
    static let documents            = "/me/patient/documents"

    // MARK: Notifications
    static let notifications        = "/me/notifications"

    // MARK: Chat / Messages
    static let chatThreads          = "/me/chat/threads"
    static func chatMessages(threadId: String) -> String {
        "/me/chat/threads/\(threadId)/messages"
    }

    // MARK: Billing actions
    static func payInvoice(id: String) -> String { "/me/patient/billing/invoices/\(id)/pay" }

    // MARK: Proxy / Family Access
    static let proxies              = "/me/patient/proxies"
    static let proxyAccess          = "/me/patient/proxy-access"
    static func revokeProxy(id: String) -> String { "/me/patient/proxies/\(id)" }

    // MARK: Consent actions
    static func revokeConsent(fromHospitalId: String, toHospitalId: String) -> String {
        "/me/patient/consents?fromHospitalId=\(fromHospitalId)&toHospitalId=\(toHospitalId)"
    }

    // MARK: Refills helpers
    static func cancelRefill(id: String) -> String { "/me/patient/refills/\(id)/cancel" }
}
