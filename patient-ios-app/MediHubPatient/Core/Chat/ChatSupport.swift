import Foundation
import UniformTypeIdentifiers

// MARK: - Attachment cache

/// Chat attachments a clinician sent, downloaded through the authenticated
/// client into the app's private Caches directory and wiped at sign-out.
///
/// The bytes have no public URL (`GET /chat/attachments/{id}/download` checks
/// the participant), so AsyncImage cannot fetch them and they are cached here
/// instead: once per attachment, so replaying a voice note does not download
/// it again.
@MainActor
final class ChatAttachmentCache {
    static let shared = ChatAttachmentCache()

    let directory: URL
    /// Bumped by `clear()`. A download that started before a sign-out must
    /// not write the previous patient's file after the wipe.
    private var generation = 0

    init(directory: URL? = nil) {
        self.directory = directory
            ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat-attachments", isDirectory: true)
    }

    /// The cached file, downloading it first when needed.
    func fileURL(for attachment: ChatAttachmentDTO, client: APIClient = .shared) async throws -> URL {
        guard let id = attachment.id, let name = Self.fileName(for: attachment) else {
            throw APIError.invalidURL
        }
        let url = directory.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: url.path) { return url }

        let startedIn = generation
        let (data, _) = try await client.downloadFile(APIEndpoints.chatAttachmentDownload(id: id))
        guard startedIn == generation else { throw CancellationError() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }

    /// Everything, for sign-out.
    func clear() {
        generation += 1
        try? FileManager.default.removeItem(at: directory)
    }

    /// `<id>.<ext>`: the id is a server UUID, filtered to letters, digits and
    /// dashes so it can never name a path outside the cache directory. The
    /// extension is what AVAudioPlayer and QuickLook pick a decoder by.
    static func fileName(for attachment: ChatAttachmentDTO) -> String? {
        let safeId = (attachment.id ?? "").filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        guard !safeId.isEmpty else { return nil }
        let ext = fileExtension(contentType: attachment.contentType, displayName: attachment.displayName)
        return ext.isEmpty ? safeId : safeId + "." + ext
    }

    static func fileExtension(contentType: String?, displayName: String?) -> String {
        let mime = contentType?
            .split(separator: ";").first
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
        if !mime.isEmpty, mime != "application/octet-stream",
           let ext = UTType(mimeType: mime)?.preferredFilenameExtension {
            return ext
        }
        let fromName = ((displayName ?? "") as NSString).pathExtension.lowercased()
        return fromName.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }
}

// MARK: - Inbox timestamp

/// `ChatConversationSummaryDTO.lastMessageTimestamp` is a Jackson
/// `LocalDateTime` — "2026-09-19T10:30:00", optionally with fractional
/// seconds, and no offset. The row showed that string verbatim.
///
/// It is read as UTC, the zone the rest of `APIClient` assumes for an
/// offset-less timestamp (and the server's zone in Burkina Faso, GMT+0).
enum ChatTimestamp {
    static func parse(_ raw: String?) -> Date? {
        guard let raw = raw?.trimmingCharacters(in: .whitespaces), raw.count >= 19 else { return nil }
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.timeZone = TimeZone(secondsFromGMT: 0)
        parser.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return parser.date(from: String(raw.prefix(19)))
    }

    /// Today: the time. Yesterday: "Yesterday" / "Hier" / "Ayer". Within the
    /// last week: the weekday. Older: a short date. All in the app's language
    /// and the device's time zone. Nil when the text does not parse, so the
    /// row shows nothing rather than a machine string.
    static func display(
        _ raw: String?,
        now: Date = Date(),
        language: String = LocalizationManager.shared.currentLanguage,
        timeZone: TimeZone = .current
    ) -> String? {
        guard let date = parse(raw) else { return nil }
        let locale = Locale(identifier: language)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = locale

        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.calendar = calendar

        let dayOfDate = calendar.startOfDay(for: date)
        let today = calendar.startOfDay(for: now)
        let days = calendar.dateComponents([.day], from: dayOfDate, to: today).day ?? 0

        switch days {
        case ..<1:
            // Today (or a clock skew that puts it slightly ahead).
            formatter.dateStyle = .none
            formatter.timeStyle = .short
            return formatter.string(from: date)
        case 1:
            let relative = RelativeDateTimeFormatter()
            relative.locale = locale
            relative.dateTimeStyle = .named
            relative.unitsStyle = .full
            relative.formattingContext = .beginningOfSentence
            return relative.localizedString(from: DateComponents(day: -1))
        case 2 ..< 7:
            formatter.setLocalizedDateFormatFromTemplate("EEEE")
            return formatter.string(from: date).capitalized(with: locale)
        default:
            formatter.dateStyle = .short
            formatter.timeStyle = .none
            return formatter.string(from: date)
        }
    }
}
