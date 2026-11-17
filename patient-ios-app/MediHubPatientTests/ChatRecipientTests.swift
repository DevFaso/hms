import XCTest
@testable import MediHubPatient

/// The new-conversation picker offers the care team AND the appointment
/// clinicians, as the web portal's picker does, addressed by USER id.
final class ChatRecipientTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    func testCareTeamRecipientIsTheDoctorUserIdNotTheLinkId() throws {
        // Shaped like CareTeamDTO: `id` is the primary-care link row.
        let team = try decode(CareTeamDTO.self, """
        {
          "primaryCare": {"id": "link-1", "hospitalId": "h-1", "hospitalName": "CHU Yalgado",
                          "doctorUserId": "doctor-user-1", "doctorDisplay": "Dr Ouedraogo", "current": true},
          "primaryCareHistory": [
            {"id": "link-2", "doctorUserId": null, "doctorDisplay": "Dr Sans Compte", "current": false},
            {"id": "link-3", "doctorUserId": "doctor-user-2", "doctorDisplay": "Dr Kabore", "current": false}
          ]
        }
        """)

        let recipients = ChatRecipient.merge(careTeam: team.clinicianCandidates, appointments: [], excluding: nil)

        XCTAssertEqual(recipients.map(\.id), ["doctor-user-1", "doctor-user-2"])
        XCTAssertFalse(recipients.contains { $0.id.hasPrefix("link-") }, "a care-team link id is not a recipient")
        XCTAssertEqual(recipients.first?.name, "Dr Ouedraogo")
        XCTAssertEqual(recipients.first?.subtitle, "CHU Yalgado")
    }

    func testSourcesAreDeduplicatedByUserIdAndThePatientIsExcluded() {
        let careTeam = [ClinicianCandidate(userId: "doc-a", name: "Dr A", hospitalName: "H1")]
        let appointments = [
            ClinicianCandidate(userId: "doc-a", name: "Dr A (appointment)", hospitalName: "H2"),
            ClinicianCandidate(userId: "doc-b", name: "Dr B", hospitalName: nil),
            ClinicianCandidate(userId: "patient-self", name: "Me", hospitalName: nil),
            ClinicianCandidate(userId: "doc-b", name: "Dr B", hospitalName: nil),
            ClinicianCandidate(userId: "doc-c", name: nil, hospitalName: nil),
        ]

        let recipients = ChatRecipient.merge(careTeam: careTeam, appointments: appointments, excluding: "patient-self")

        XCTAssertEqual(recipients.map(\.id), ["doc-a", "doc-b"])
        // Care team first: its entry wins over the appointment's.
        XCTAssertEqual(recipients.first?.subtitle, "H1")
    }

    func testOneSourceAloneStillFillsTheList() {
        let appointments = [ClinicianCandidate(userId: "doc-x", name: "Dr X", hospitalName: nil)]
        XCTAssertEqual(ChatRecipient.merge(careTeam: [], appointments: appointments, excluding: nil).map(\.id),
                       ["doc-x"])
    }
}
