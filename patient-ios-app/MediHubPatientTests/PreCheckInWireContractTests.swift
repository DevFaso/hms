import XCTest

@testable import MediHubPatient

final class PreCheckInWireContractTests: XCTestCase {
  private func fields(_ request: PreCheckInRequest) throws -> [String: Any] {
    let data = try JSONEncoder().encode(request)
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }

  func testUnchangedDemographicsAreOmittedAndConsentIsExplicit() throws {
    let request = PreCheckInRequest(
      appointmentId: "cdx-appointment", questionnaireResponses: [], consentAcknowledged: false
    )
    let encoded = try fields(request)
    XCTAssertEqual(encoded["appointmentId"] as? String, "cdx-appointment")
    XCTAssertEqual(encoded["consentAcknowledged"] as? Bool, false)
    XCTAssertEqual((encoded["questionnaireResponses"] as? [Any])?.count, 0)
    XCTAssertNil(encoded["phoneNumber"])
    XCTAssertNil(encoded["emergencyContactName"])
    XCTAssertNil(encoded["insuranceMemberId"])
  }

  func testQuestionnaireAnswersRemainScopedAndKeepFalseAndZero() throws {
    let answers = #"{"cdx-yes":false,"cdx-number":0,"cdx-text":"cdx Testeur"}"#
    let request = PreCheckInRequest(
      appointmentId: "cdx-appointment",
      questionnaireResponses: [
        QuestionnaireSubmission(questionnaireId: "cdx-questionnaire", responses: answers)
      ],
      consentAcknowledged: true
    )
    let encoded = try fields(request)
    let submissions = try XCTUnwrap(encoded["questionnaireResponses"] as? [[String: Any]])
    let submission = try XCTUnwrap(submissions.first)
    XCTAssertEqual(encoded["consentAcknowledged"] as? Bool, true)
    XCTAssertEqual(submission["questionnaireId"] as? String, "cdx-questionnaire")
    XCTAssertEqual(submission["responses"] as? String, answers)
    XCTAssertNil(encoded["cdx-yes"])
    let decoded = try XCTUnwrap(
      JSONSerialization.jsonObject(with: Data(answers.utf8)) as? [String: Any])
    XCTAssertEqual(decoded["cdx-yes"] as? Bool, false)
    XCTAssertEqual(decoded["cdx-number"] as? Int, 0)
  }

  func testResponseSeparatesAppointmentStateFromDemographicUpdateOutcome() throws {
    let json =
      #"{"appointmentId":"cdx-appointment","appointmentStatus":"CONFIRMED","preCheckedIn":true,"preCheckinTimestamp":"2026-11-11T12:00:00","questionnaireResponsesSubmitted":2,"demographicsUpdated":false}"#
    let response = try JSONDecoder().decode(PreCheckInResponse.self, from: Data(json.utf8))
    XCTAssertEqual(response.appointmentId, "cdx-appointment")
    XCTAssertEqual(response.appointmentStatus, "CONFIRMED")
    XCTAssertEqual(response.preCheckedIn, true)
    XCTAssertEqual(response.demographicsUpdated, false)
    XCTAssertEqual(response.questionnaireResponsesSubmitted, 2)
    XCTAssertEqual(response.preCheckinTimestamp, "2026-11-11T12:00:00")
  }
}
