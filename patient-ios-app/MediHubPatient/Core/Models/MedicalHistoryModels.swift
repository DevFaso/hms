import Foundation

// MARK: - My Medical History (matches the web's my-medical-history)

// Four read-only surfaces under /me/patient/*. The backend builds them for
// staff first, so each DTO carries far more than the patient page shows; only
// the fields the web reads are declared here, and everything but the id is
// optional so an omitted or null field never fails the decode.

/// PatientDiagnosisSummaryDTO — problems and diagnoses merged, newest first.
struct HistoryDiagnosis: Decodable, Identifiable {
    let id: String
    let description: String?
    let icdCode: String?
    let status: String?
    /// "yyyy-MM-dd'T'HH:mm:ssXXX"
    let diagnosedAt: String?
    let diagnosedByName: String?
}

/// PatientSurgicalHistoryResponseDTO (NON_NULL: absent fields are simply missing).
struct HistorySurgery: Decodable, Identifiable {
    let id: String
    let hospitalName: String?
    let procedureCode: String?
    let procedureDisplay: String?
    /// "yyyy-MM-dd"
    let procedureDate: String?
    let outcome: String?
    let performedBy: String?
    let location: String?
    let notes: String?
}

/// FamilyHistoryResponseDTO, the part the web lists.
struct HistoryFamilyEntry: Decodable, Identifiable {
    let id: String
    let relationship: String?
    let relativeName: String?
    let relativeGender: String?
    let relativeLiving: Bool?
    let relativeAge: Int?
    let relativeAgeAtDeath: Int?
    let causeOfDeath: String?
    let conditionCode: String?
    let conditionDisplay: String?
    let conditionCategory: String?
    let ageAtOnset: Int?
    let severity: String?
    let notes: String?
    let active: Bool?
}

/// SocialHistoryResponseDTO — the latest active record, or `data: null`
/// when none was ever recorded (the web shows "no social history" then).
struct HistorySocial: Decodable, Identifiable {
    let id: String
    let tobaccoUse: Bool?
    let tobaccoType: String?
    let tobaccoPacksPerDay: Double?
    let tobaccoYearsUsed: Int?
    /// "yyyy-MM-dd"
    let tobaccoQuitDate: String?
    let tobaccoNotes: String?
    let alcoholUse: Bool?
    let alcoholFrequency: String?
    let alcoholDrinksPerWeek: Int?
    let alcoholBingeDrinking: Bool?
    let alcoholNotes: String?
    let recreationalDrugUse: Bool?
    let exerciseFrequency: String?
    let exerciseType: String?
    let occupation: String?
    let employmentStatus: String?
    let maritalStatus: String?
    let active: Bool?
}
