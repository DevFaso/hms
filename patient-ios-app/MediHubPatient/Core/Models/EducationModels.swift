import Foundation

// MARK: - Patient education (GET /me/patient/education, PUT .../{id}/progress, GET/POST .../questions)

/// One resource the care team assigned to the patient, joined with their
/// progress (PatientEducationItemDTO). Identified by the resource, not the
/// progress row: the progress table has no unique (patient, resource) row.
struct EducationItem: Decodable, Identifiable {
    let resourceId: String
    let progressId: String?
    let title: String?
    let description: String?
    let resourceType: String?
    let category: String?
    let comprehensionStatus: String?
    let progressPercentage: Int?
    let startedAt: String?
    let completedAt: String?
    let lastAccessedAt: String?
    let rating: Int?
    let feedback: String?
    let needsClarification: Bool?
    let clarificationRequest: String?
    let confirmedUnderstanding: Bool?
    let contentUrl: String?
    let textContent: String?
    let thumbnailUrl: String?
    let videoUrl: String?
    let estimatedDuration: Int?
    let tags: [String]?
    let primaryLanguage: String?
    let isWarningSignContent: Bool?

    var id: String { resourceId }
    /// Finished means a completion stamp, as on the web (`!!completedAt`).
    var isCompleted: Bool { !(completedAt ?? "").isEmpty }
    var hasText: Bool { !(textContent ?? "").isEmpty }
    var hasVideo: Bool { !(videoUrl ?? "").isEmpty }
    var hasLink: Bool { !(contentUrl ?? "").isEmpty }
    var hasContent: Bool { hasText || hasVideo || hasLink }
    var isWarningSign: Bool { isWarningSignContent == true }
}

/// PUT /me/patient/education/{id}/progress (PatientEducationProgressUpdateDTO):
/// every field optional; progress 0...100, rating 1...5.
struct EducationProgressUpdate: Encodable {
    var progressPercentage: Int?
    var rating: Int?
    var feedback: String?
    var confirmedUnderstanding: Bool?
    var needsClarification: Bool?
    var clarificationRequest: String?
}

/// GET/POST /me/patient/education/questions (PatientEducationQuestionResponseDTO).
struct EducationQuestion: Decodable, Identifiable {
    let id: String
    let resourceId: String?
    let questionText: String?
    let isUrgent: Bool?
    let isAnswered: Bool?
    let answerText: String?
    let answeredAt: String?
    let requiresInPersonDiscussion: Bool?
    let appointmentScheduled: Bool?
    let createdAt: String?

    var hasAnswer: Bool { isAnswered == true && !(answerText ?? "").isEmpty }
}

/// PatientEducationQuestionSubmitDTO: questionText 5...2000 characters,
/// resourceId optional (a general question).
struct EducationQuestionSubmit: Encodable {
    let resourceId: String?
    let questionText: String
    let isUrgent: Bool
}
