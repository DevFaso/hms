import Foundation

// MARK: - PRO self-screenings (GET/POST /me/patient/pro-screenings, GET /me/patient/pro-instruments/{code})

/// What is open to the patient right now (while a postpartum plan is active)
/// and what they answered before. Carries no score by design: the care team
/// follows up in person.
struct ProSelfReport: Decodable {
    let available: [ProScreeningAvailable]?
    let history: [ProScreeningEntry]?
}

struct ProScreeningAvailable: Decodable, Identifiable, Hashable {
    let code: String
    let name: String?
    let languages: [String]?
    var id: String { code }
}

struct ProScreeningEntry: Decodable, Identifiable {
    let id: String
    let instrumentCode: String?
    let instrumentName: String?
    let administeredAt: String?
    let followUpPlanned: Bool?
    let careTeamAlerted: Bool?
}

/// Items and answer options in the served language, no scores.
struct ProInstrumentView: Decodable {
    let code: String
    let name: String?
    let version: String?
    let sourceCitation: String?
    let licenceNote: String?
    let language: String?
    let availableLanguages: [String]?
    let instruction: String?
    let items: [ProInstrumentItem]?
}

struct ProInstrumentItem: Decodable, Identifiable {
    let itemNo: Int
    let prompt: String?
    let options: [ProInstrumentOption]?
    var id: Int { itemNo }
}

struct ProInstrumentOption: Decodable, Identifiable {
    let optionNo: Int
    let label: String?
    var id: Int { optionNo }
}

/// ProResponseCreateDTO: answers are itemNo -> optionNo, with string keys
/// for Jackson's Map<Integer, Integer>. Hospital and time are pinned server-side.
struct ProResponseCreate: Encodable {
    let instrumentCode: String
    let language: String?
    let answers: [String: Int]
}
