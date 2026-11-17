import XCTest
@testable import MediHubPatient

/// Chat, phase 4: attachments decode (photo, voice note, attachment-only),
/// the inbox shows a localised time instead of the raw LocalDateTime, and a
/// thread is marked read on the path the backend exposes.
final class ChatSupportTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    // MARK: - Attachments

    func testAttachmentOnlyMessageDecodesItsAttachments() throws {
        // Shaped like ChatMessageResponseDTO + ChatAttachmentDTO: content null,
        // sizeBytes a long, durationSeconds null for the photo.
        let json = """
        {
          "id": "m-1", "timestamp": "2026-09-19T10:30:00",
          "senderId": "doc-1", "recipientId": "pat-1",
          "content": null, "read": false,
          "attachments": [
            {"id": "11111111-2222-3333-4444-555555555555", "storageKey": "k1",
             "displayName": "plaie.jpg", "contentType": "image/jpeg",
             "sizeBytes": 245760, "sha256": "ab", "kind": "PHOTO", "durationSeconds": null},
            {"id": "66666666-7777-8888-9999-000000000000", "storageKey": "k2",
             "displayName": "note.m4a", "contentType": "audio/mp4",
             "sizeBytes": 30000, "kind": "AUDIO", "durationSeconds": 42}
          ]
        }
        """

        let message = try decode(ChatMessageDTO.self, json)

        XCTAssertNil(message.displayText, "an attachment-only message must not draw an empty bubble")
        XCTAssertEqual(message.attachmentList.count, 2)
        XCTAssertEqual(message.attachmentList[0].kindEnum, .photo)
        XCTAssertEqual(message.attachmentList[0].sizeBytes, 245_760)
        XCTAssertNil(message.attachmentList[0].durationSeconds)
        XCTAssertEqual(message.attachmentList[1].kindEnum, .audio)
        XCTAssertEqual(message.attachmentList[1].durationSeconds, 42)
        XCTAssertEqual(message.attachmentList[1].id, "66666666-7777-8888-9999-000000000000")
    }

    func testMessageWithoutAttachmentsStillDecodes() throws {
        let message = try decode(ChatMessageDTO.self, #"{"id": "m-2", "content": "Bonjour"}"#)
        XCTAssertTrue(message.attachmentList.isEmpty)
        XCTAssertEqual(message.displayText, "Bonjour")
    }

    func testBlankContentIsNoText() throws {
        let message = try decode(ChatMessageDTO.self, #"{"id": "m-3", "content": "   "}"#)
        XCTAssertNil(message.displayText)
    }

    func testUnknownKindIsAFileNotAFailure() throws {
        let attachment = try decode(ChatAttachmentDTO.self, #"{"id": "a", "kind": "DOCUMENT"}"#)
        XCTAssertEqual(attachment.kindEnum, .other)
    }

    @MainActor
    func testCachedFileNameCannotEscapeTheCacheDirectory() {
        let hostile = ChatAttachmentDTO(id: "../../etc/passwd", displayName: nil, contentType: nil,
                                        sizeBytes: nil, kind: "PHOTO", durationSeconds: nil)
        XCTAssertEqual(ChatAttachmentCache.fileName(for: hostile), "etcpasswd")

        let named = ChatAttachmentDTO(id: "abc-123", displayName: "voice.M4A",
                                      contentType: "application/octet-stream",
                                      sizeBytes: nil, kind: "AUDIO", durationSeconds: nil)
        XCTAssertEqual(ChatAttachmentCache.fileName(for: named), "abc-123.m4a")

        let none = ChatAttachmentDTO(id: nil, displayName: nil, contentType: nil,
                                     sizeBytes: nil, kind: nil, durationSeconds: nil)
        XCTAssertNil(ChatAttachmentCache.fileName(for: none))
    }

    @MainActor
    func testClearEmptiesTheCacheDirectory() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("chat-cache-test-\(UUID().uuidString)", isDirectory: true)
        let cache = ChatAttachmentCache(directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: directory.appendingPathComponent("a.jpeg"))

        cache.clear()

        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }

    func testDurationFormatting() {
        XCTAssertEqual(ChatAttachmentFormat.duration(7), "0:07")
        XCTAssertEqual(ChatAttachmentFormat.duration(90), "1:30")
    }

    // MARK: - Mark read

    func testMarkReadPathPutsTheOtherPartyFirst() {
        XCTAssertEqual(APIEndpoints.chatMarkRead(senderId: "doctor", recipientId: "patient"),
                       "/chat/mark-read/doctor/patient")
        XCTAssertEqual(APIEndpoints.chatAttachmentDownload(id: "att-1"), "/chat/attachments/att-1/download")
    }

    // MARK: - Inbox timestamp

    private let utc = TimeZone(secondsFromGMT: 0)!

    /// Saturday 2026-09-19 15:00 UTC.
    private var now: Date {
        var components = DateComponents()
        components.year = 2026; components.month = 9; components.day = 19
        components.hour = 15; components.minute = 0
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(from: components)!
    }

    func testTimestampParsesWithAndWithoutFractionalSeconds() {
        XCTAssertNotNil(ChatTimestamp.parse("2026-09-19T10:30:00"))
        XCTAssertEqual(ChatTimestamp.parse("2026-09-19T10:30:00.123456"), ChatTimestamp.parse("2026-09-19T10:30:00"))
        XCTAssertNil(ChatTimestamp.parse("yesterday"))
        XCTAssertNil(ChatTimestamp.parse(nil))
    }

    func testTodayShowsTheTimeNotTheRawString() {
        let shown = ChatTimestamp.display("2026-09-19T10:30:00", now: now, language: "fr", timeZone: utc)
        XCTAssertEqual(shown, "10:30")
    }

    func testYesterdayIsNamedInTheAppLanguage() {
        let french = ChatTimestamp.display("2026-09-18T23:59:00", now: now, language: "fr", timeZone: utc)
        XCTAssertEqual(french?.lowercased(), "hier")
        let spanish = ChatTimestamp.display("2026-09-18T08:00:00", now: now, language: "es", timeZone: utc)
        XCTAssertEqual(spanish?.lowercased(), "ayer")
    }

    func testThisWeekShowsTheWeekday() {
        let shown = ChatTimestamp.display("2026-09-16T08:00:00", now: now, language: "fr", timeZone: utc)
        XCTAssertEqual(shown, "Mercredi")
    }

    func testOlderShowsADate() {
        let shown = ChatTimestamp.display("2026-09-01T08:00:00", now: now, language: "fr", timeZone: utc)
        XCTAssertEqual(shown, "01/09/2026")
    }

    func testUnparseableShowsNothing() {
        XCTAssertNil(ChatTimestamp.display("not a date", now: now, language: "en", timeZone: utc))
    }
}
