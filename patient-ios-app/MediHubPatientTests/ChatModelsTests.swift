import XCTest
@testable import MediHubPatient

/// Pins the chat wire contract.
///
/// The Messages tab shipped decoding a shape the backend never sent — a
/// `thread` concept that does not exist, and `sentAt` where the server sends
/// `timestamp`. Every call failed, `try?` swallowed it, and the tab simply
/// looked empty. These tests decode payloads shaped exactly like
/// `ChatConversationSummaryDTO` / `ChatMessageResponseDTO`, so a rename on
/// either side fails here rather than silently blanking the screen again.
final class ChatModelsTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    func testConversationDecodesBackendFieldNames() throws {
        let json = """
        {
          "conversationUserId": "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
          "conversationUserName": "Dr Ouedraogo",
          "lastMessageContent": "Vos resultats sont prets.",
          "lastMessageTimestamp": "2026-09-19T10:30:00",
          "hospitalId": "11111111-2222-3333-4444-555555555555",
          "lastMessageRead": false,
          "unreadCount": 3
        }
        """

        let dto = try decode(ChatConversationDTO.self, json)

        XCTAssertEqual(dto.conversationUserId, "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f")
        XCTAssertEqual(dto.conversationUserName, "Dr Ouedraogo")
        XCTAssertEqual(dto.unreadCount, 3)
        // A conversation is identified by the other participant, not a thread id.
        XCTAssertEqual(dto.id, dto.conversationUserId)
    }

    func testMessageDecodesTimestampNotSentAt() throws {
        let json = """
        {
          "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
          "timestamp": "2026-09-19T10:30:00",
          "senderId": "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
          "senderName": "Dr Ouedraogo",
          "senderRole": "ROLE_DOCTOR",
          "recipientId": "99999999-8888-7777-6666-555555555555",
          "recipientName": "Awa Traore",
          "content": "Vos resultats sont prets.",
          "read": false
        }
        """

        let dto = try decode(ChatMessageDTO.self, json)

        XCTAssertEqual(dto.timestamp, "2026-09-19T10:30:00")
        XCTAssertEqual(dto.senderId, "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f")
        XCTAssertEqual(dto.content, "Vos resultats sont prets.")
        XCTAssertEqual(dto.read, false)
    }

    func testAppointmentDecodesStaffUserId() throws {
        // staffUserId is what /chat/send needs as a recipient; staffId is a
        // Staff row id and is not interchangeable. iOS did not decode it at
        // all, which is why the composer had nobody to address.
        let json = """
        {
          "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
          "staffId": "22222222-3333-4444-5555-666666666666",
          "staffUserId": "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
          "staffName": "Dr Ouedraogo",
          "appointmentDate": "2026-09-19",
          "status": "SCHEDULED"
        }
        """

        let dto = try decode(AppointmentDTO.self, json)

        XCTAssertEqual(dto.staffUserId, "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f")
        XCTAssertNotEqual(dto.staffUserId, dto.staffId)
    }

    func testSendRequestCarriesRecipientId() throws {
        let body = SendChatMessageRequest(
            recipientId: "6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f",
            content: "Merci docteur."
        )
        let json = String(decoding: try JSONEncoder().encode(body), as: UTF8.self)

        XCTAssertTrue(json.contains("recipientId"))
        XCTAssertTrue(json.contains("6f1c0f3e-0a11-4c22-9f3e-1a2b3c4d5e6f"))
    }
}
