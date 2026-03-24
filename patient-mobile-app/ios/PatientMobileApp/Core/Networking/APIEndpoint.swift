import Foundation

/// Defines all API endpoints used by the patient mobile app
enum APIEndpoint {
    // MARK: - Auth
    case login
    case logout
    case refreshToken
    case register
    case requestPasswordReset
    case resetPassword

    // MARK: - Patient Portal
    case profile
    case healthSummary
    case appointments
    case cancelAppointment
    case rescheduleAppointment
    case labResults(limit: Int)
    case medications(limit: Int)
    case prescriptions
    case refills(page: Int, size: Int)
    case requestRefill
    case cancelRefill(id: String)
    case invoices(page: Int, size: Int)
    case encounters
    case afterVisitSummaries
    case careTeam
    case vitals(limit: Int)
    case recordVital
    case immunizations
    case consultations
    case consents(page: Int, size: Int)
    case grantConsent
    case revokeConsent(fromHospitalId: String, toHospitalId: String)
    case accessLog(page: Int, size: Int)
    case referrals
    case treatmentPlans(page: Int, size: Int)
    case documents(page: Int, size: Int)

    // MARK: - Notifications
    case notifications
    case markNotificationRead(id: String)
    case registerDeviceToken

    // MARK: - Chat
    case conversations(userId: String)
    case messages(userId: String)
    case chatHistory(user1Id: String, user2Id: String, page: Int, size: Int)
    case sendMessage
    case markChatRead(senderId: String, recipientId: String)

    // MARK: - Scheduling
    case createAppointment
    case searchAppointments
    case checkAvailability(staffId: String, dateTime: String)
    case staff
    case departments

    var path: String {
        switch self {
        // Auth
        case .login:                    return "/auth/login"
        case .logout:                   return "/auth/logout"
        case .refreshToken:             return "/auth/token/refresh"
        case .register:                 return "/auth/register"
        case .requestPasswordReset:     return "/auth/password/request-reset"
        case .resetPassword:            return "/auth/password/reset-password"
        // Patient Portal
        case .profile:                  return "/me/patient/profile"
        case .healthSummary:            return "/me/patient/health-summary"
        case .appointments:             return "/me/patient/appointments"
        case .cancelAppointment:        return "/me/patient/appointments/cancel"
        case .rescheduleAppointment:    return "/me/patient/appointments/reschedule"
        case .labResults(let limit):    return "/me/patient/lab-results?limit=\(limit)"
        case .medications(let limit):   return "/me/patient/medications?limit=\(limit)"
        case .prescriptions:            return "/me/patient/prescriptions"
        case .refills(let p, let s):    return "/me/patient/refills?page=\(p)&size=\(s)"
        case .requestRefill:            return "/me/patient/refills"
        case .cancelRefill(let id):     return "/me/patient/refills/\(id)/cancel"
        case .invoices(let p, let s):   return "/me/patient/billing/invoices?page=\(p)&size=\(s)"
        case .encounters:               return "/me/patient/encounters"
        case .afterVisitSummaries:      return "/me/patient/after-visit-summaries"
        case .careTeam:                 return "/me/patient/care-team"
        case .vitals(let limit):        return "/me/patient/vitals?limit=\(limit)"
        case .recordVital:              return "/me/patient/vitals"
        case .immunizations:            return "/me/patient/immunizations"
        case .consultations:            return "/me/patient/consultations"
        case .consents(let p, let s):   return "/me/patient/consents?page=\(p)&size=\(s)"
        case .grantConsent:             return "/me/patient/consents"
        case .revokeConsent(let f, let t): return "/me/patient/consents?fromHospitalId=\(f)&toHospitalId=\(t)"
        case .accessLog(let p, let s):  return "/me/patient/access-log?page=\(p)&size=\(s)"
        case .referrals:                return "/me/patient/referrals"
        case .treatmentPlans(let p, let s): return "/me/patient/treatment-plans?page=\(p)&size=\(s)"
        case .documents(let p, let s):  return "/me/patient/documents?page=\(p)&size=\(s)"
        // Notifications
        case .notifications:            return "/notifications"
        case .markNotificationRead(let id): return "/notifications/\(id)/read"
        case .registerDeviceToken:      return "/notifications/device-token"
        // Chat
        case .conversations(let uid):   return "/chat/conversations/\(uid)"
        case .messages(let uid):        return "/chat/messages/\(uid)"
        case .chatHistory(let u1, let u2, let p, let s):
            return "/chat/history/\(u1)/\(u2)?page=\(p)&size=\(s)"
        case .sendMessage:              return "/chat/send"
        case .markChatRead(let s, let r): return "/chat/mark-read/\(s)/\(r)"
        // Scheduling
        case .createAppointment:        return "/appointments"
        case .searchAppointments:       return "/appointments/search"
        case .checkAvailability(let sid, let dt):
            return "/availability/check?staffId=\(sid)&dateTime=\(dt)"
        case .staff:                    return "/staff"
        case .departments:              return "/departments"
        }
    }

    var method: HTTPMethod {
        switch self {
        case .login, .logout, .refreshToken, .register, .requestPasswordReset, .resetPassword,
             .requestRefill, .grantConsent, .sendMessage, .createAppointment, .searchAppointments,
             .recordVital, .registerDeviceToken:
            return .POST
        case .cancelAppointment, .rescheduleAppointment, .cancelRefill, .markNotificationRead,
             .markChatRead:
            return .PUT
        case .revokeConsent:
            return .DELETE
        default:
            return .GET
        }
    }
}

enum HTTPMethod: String {
    case GET, POST, PUT, DELETE, PATCH
}
