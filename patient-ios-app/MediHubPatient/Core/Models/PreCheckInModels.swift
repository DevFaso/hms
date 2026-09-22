import Foundation

// MARK: - Pre-check-in (GET …/questionnaires, POST …/pre-checkin)

/// A questionnaire the hospital assigned to the visit's department. `questions`
/// is a JSON array the hospital authored; see `QuestionnaireQuestion`.
struct QuestionnaireDTO: Decodable, Identifiable {
    let id: String
    let title: String?
    let description: String?
    let questions: String?
    let version: Int?
    let departmentId: String?
    let departmentName: String?
}

/// One entry of a questionnaire's `questions` JSON, the shape the web parses:
/// id, text, type (YES_NO, TEXT, NUMBER, SCALE, MULTI_CHOICE), required,
/// options, min, max. Parsed with JSONSerialization so a malformed
/// questionnaire degrades to "no questions", exactly as it does on the web.
struct QuestionnaireQuestion: Identifiable, Hashable {
    let id: String
    let text: String
    let type: String
    let required: Bool
    let options: [String]
    let min: Double?
    let max: Double?

    static func parse(_ json: String?) -> [QuestionnaireQuestion] {
        guard let data = json?.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return [] }
        return array.compactMap { o in
            let id = (o["id"] as? String) ?? (o["id"] as? NSNumber).map { $0.stringValue } ?? ""
            guard !id.isEmpty else { return nil }
            let text = (o["text"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id
            let type = ((o["type"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "TEXT").uppercased()
            return QuestionnaireQuestion(
                id: id, text: text, type: type,
                required: o["required"] as? Bool ?? false,
                options: (o["options"] as? [String] ?? []).filter { !$0.isEmpty },
                min: (o["min"] as? NSNumber)?.doubleValue,
                max: (o["max"] as? NSNumber)?.doubleValue
            )
        }
    }
}

/// One questionnaire's answers: a JSON object of questionId -> answer, as the web sends it.
struct QuestionnaireSubmission: Encodable {
    let questionnaireId: String
    let responses: String
}

/// PreCheckInRequestDTO. Every demographic field is optional: nil keeps the current value.
struct PreCheckInRequest: Encodable {
    let appointmentId: String
    var phoneNumber: String?
    var email: String?
    var addressLine1: String?
    var city: String?
    var state: String?
    var zipCode: String?
    var emergencyContactName: String?
    var emergencyContactPhone: String?
    var emergencyContactRelationship: String?
    var insuranceProvider: String?
    var insuranceMemberId: String?
    var insurancePlan: String?
    var questionnaireResponses: [QuestionnaireSubmission]
    var consentAcknowledged: Bool
}

struct PreCheckInResponse: Decodable {
    let appointmentId: String?
    let appointmentStatus: String?
    let preCheckedIn: Bool?
    let preCheckinTimestamp: String?
    let questionnaireResponsesSubmitted: Int?
    let demographicsUpdated: Bool?
}
