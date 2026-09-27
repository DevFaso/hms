import SwiftUI

struct MessagesView: View {
    @StateObject private var vm = MessagesViewModel()
    /// A tapped chat notification names the thread to open.
    @ObservedObject private var push = PushManager.shared
    /// Programmatic so a notification tap can open a thread, and so coming
    /// back from a thread (path emptied) refreshes the unread badges.
    @State private var path: [ChatConversationDTO] = []

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if vm.isLoading, vm.conversations.isEmpty {
                    ProgressView("loading".localized)
                } else if let error = vm.errorMessage, vm.conversations.isEmpty {
                    ContentUnavailableView("error".localized,
                                           systemImage: "exclamationmark.triangle",
                                           description: Text(error))
                } else if vm.conversations.isEmpty {
                    ContentUnavailableView("no_messages".localized,
                                           systemImage: "message",
                                           description: Text("no_messages_desc".localized))
                } else {
                    List(vm.conversations) { conversation in
                        NavigationLink(value: conversation) {
                            ThreadRowView(conversation: conversation)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationDestination(for: ChatConversationDTO.self) { conversation in
                MessageThreadView(conversation: conversation)
            }
            .navigationTitle("tab_messages".localized)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    // Restored. I removed this on the false premise that
                    // Android had no composer; it does — a FAB backed by an
                    // appointment-history fallback. Without it a patient with
                    // no existing thread cannot contact anyone.
                    NavigationLink(destination: ComposeMessageView()) {
                        Image(systemName: "square.and.pencil")
                    }
                }
            }
            .refreshable { await vm.load() }
        }
        .task {
            await vm.load()
            openRequestedThread()
            // Asked here, in context, once the inbox is on screen: chat is
            // what the notifications are for. Never at a cold start.
            await PushManager.shared.requestAuthorizationIfNeeded()
        }
        .onChange(of: path) { _, newPath in
            // Back from a thread, which was marked read when it opened.
            if newPath.isEmpty { Task { await vm.load() } }
        }
        .onChange(of: push.messagesRequest) { _, request in
            guard request != nil else { return }
            Task {
                await vm.load()
                openRequestedThread()
            }
        }
    }

    /// Opens the thread a tapped notification points at, when that sender is
    /// in the inbox. Either way the request is consumed.
    private func openRequestedThread() {
        guard let request = push.messagesRequest else { return }
        push.messagesRequest = nil
        guard let senderId = request.senderId,
              let conversation = vm.conversations.first(where: { $0.conversationUserId == senderId })
        else { return }
        path = [conversation]
    }
}

struct ThreadRowView: View {
    let conversation: ChatConversationDTO

    /// An attachment-only message has no text to preview.
    private var preview: String {
        if let text = conversation.lastMessageContent?.trimmingCharacters(in: .whitespacesAndNewlines),
           !text.isEmpty {
            return text
        }
        return "chat_attachment_preview".localized
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill")
                .font(.largeTitle).foregroundColor(.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.conversationUserName ?? "chat_unknown_sender".localized).font(.headline)
                    Spacer()
                    // The backend's LocalDateTime, shown as a time, "Yesterday",
                    // a weekday or a date — never the raw string.
                    if let when = ChatTimestamp.display(conversation.lastMessageTimestamp) {
                        Text(when).font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
                HStack {
                    Text(preview).font(.subheadline)
                        .foregroundColor(.secondary).lineLimit(1)
                    Spacer()
                    if let unread = conversation.unreadCount, unread > 0 {
                        Text("\(unread)")
                            .font(.caption2).bold().foregroundColor(.white)
                            .padding(6).background(Color("BrandPrimary")).clipShape(Circle())
                            .accessibilityLabel(Text(String(format: "chat_unread_count_a11y".localized, unread)))
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class MessagesViewModel: ObservableObject {
    @Published var conversations: [ChatConversationDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        // Resolved from /auth/session/bootstrap when the session has no id
        // yet — an SSO session restored from an older build.
        guard let userId = await AuthManager.shared.ensureUserId() else {
            errorMessage = "error_not_signed_in".localized
            return
        }
        do {
            conversations = try await APIClient.shared.get(
                APIEndpoints.chatConversations(userId: userId)
            )
        } catch {
            // Surfaced rather than swallowed: the previous `try?` turned a
            // 404 into an empty inbox, which is how the broken endpoint went
            // unnoticed. Leaving the screen mid-load is not a failure.
            guard !Self.isCancellation(error) else { return }
            errorMessage = error.localizedDescription
        }
    }

    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let url = error as? URLError, url.code == .cancelled { return true }
        return false
    }
}

// MARK: - Message Thread

struct MessageThreadView: View {
    let conversation: ChatConversationDTO
    @StateObject private var vm: MessageThreadViewModel

    init(conversation: ChatConversationDTO) {
        self.conversation = conversation
        _vm = StateObject(wrappedValue: MessageThreadViewModel(
            otherUserId: conversation.conversationUserId
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(vm.messages) { msg in
                            MessageBubble(message: msg, isOwn: msg.senderId == vm.currentUserId)
                                .id(msg.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: vm.messages.count) { _, _ in
                    if let last = vm.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }

            if let error = vm.errorMessage {
                // Published but never rendered before: a failed send cleared
                // the draft, restored it, and said nothing — the same silent
                // failure this PR set out to remove from the inbox.
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.red.opacity(0.85))
            }

            Divider()

            HStack(spacing: 12) {
                TextField("chat_message_placeholder".localized, text: $vm.draft)
                    .padding(10)
                    .background(Color(.systemGray6))
                    .cornerRadius(20)
                Button(action: { Task { await vm.send() } }) {
                    Image(systemName: "paperplane.fill").foregroundColor(.accentColor)
                }
                .accessibilityLabel(Text("send".localized))
                .disabled(vm.draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding()
        }
        .navigationTitle(conversation.conversationUserName ?? "message".localized)
        .navigationBarTitleDisplayMode(.inline)
        .task { await vm.load() }
    }
}

struct MessageBubble: View {
    let message: ChatMessageDTO
    let isOwn: Bool

    var body: some View {
        HStack {
            if isOwn { Spacer(minLength: 40) }
            VStack(alignment: isOwn ? .trailing : .leading, spacing: 6) {
                // A clinician's wound photo or voice note used to render as
                // an empty bubble: the field was never decoded.
                ForEach(Array(message.attachmentList.enumerated()), id: \.offset) { _, attachment in
                    ChatAttachmentView(attachment: attachment)
                }
                // Content is null for an attachment-only message; no empty bubble.
                if let text = message.displayText {
                    Text(text)
                        .padding(12)
                        .background(isOwn ? Color("BrandPrimary") : Color(.systemGray5))
                        .foregroundColor(isOwn ? .white : .primary)
                        .cornerRadius(16)
                }
            }
            if !isOwn { Spacer(minLength: 40) }
        }
    }
}

@MainActor
final class MessageThreadViewModel: ObservableObject {
    @Published var messages: [ChatMessageDTO] = []
    @Published var draft: String = ""
    @Published var isLoading = false
    @Published var errorMessage: String?

    /// The other participant. The backend keys chat history by the two user
    /// ids, so this is what identifies the conversation.
    let otherUserId: String

    /// Was hard-coded to nil, so `isOwn` was false for every bubble and the
    /// patient could not tell their own messages from the clinician's.
    var currentUserId: String? { AuthManager.shared.currentUserId }

    init(otherUserId: String) {
        self.otherUserId = otherUserId
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        guard let userId = await AuthManager.shared.ensureUserId() else {
            errorMessage = "error_not_signed_in".localized
            return
        }
        do {
            let page: [ChatMessageDTO] = try await APIClient.shared.get(
                APIEndpoints.chatHistory(userId: userId, otherUserId: otherUserId)
            )
            // The endpoint returns newest first; the transcript reads oldest
            // first and scrolls to the bottom.
            messages = Array(page.reversed())
            await markRead(userId: userId)
        } catch {
            guard !MessagesViewModel.isCancellation(error) else { return }
            errorMessage = error.localizedDescription
        }
    }

    /// What the other party sent is now read. Without this the unread badge
    /// never cleared, and `chat/unread-count` — which also feeds the portal
    /// topbar — stayed inflated for this patient. Best effort: a failure
    /// leaves the badge, which is all it can cost.
    private func markRead(userId: String) async {
        try? await APIClient.shared.sendNoContent(
            .PUT,
            path: APIEndpoints.chatMarkRead(senderId: otherUserId, recipientId: userId)
        )
    }

    func send() async {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft = ""
        // Cleared on every attempt: otherwise one failed send pinned the red
        // banner above the composer for the rest of the session, including
        // over messages that then sent fine.
        errorMessage = nil
        do {
            let sent: ChatMessageDTO = try await APIClient.shared.post(
                APIEndpoints.chatSend,
                body: SendChatMessageRequest(recipientId: otherUserId, content: text)
            )
            messages.append(sent)
        } catch {
            // Put the text back so a failed send does not lose what was typed.
            draft = text
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Compose

/// A clinician the patient can start a conversation with.
// Internal, not private: `ComposeMessageViewModel.recipients` is an
// `@Published` property and `send(to:body:)` takes one, both internal,
// so a private type here is rejected with "property must be declared
// fileprivate because its type uses a private type".
struct ChatRecipient: Identifiable, Hashable {
    let id: String          // user id — the recipient /chat/send expects
    let name: String
    let subtitle: String?
}

/// Starting a NEW conversation.
///
/// There is no endpoint that lists "people this patient may message", so the
/// recipients are assembled the way the web portal's picker does: the care
/// team (`/me/patient/care-team`, where a clinician is `doctorUserId` — the
/// entry's own `id` is the care-team link and cannot address a message) plus
/// the clinicians of the patient's appointments (`staffUserId`). Deduplicated
/// by user id, without the patient themselves; each source may fail on its
/// own and the other still shows.
struct ComposeMessageView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = ComposeMessageViewModel()
    @State private var selected: ChatRecipient?
    @State private var messageBody = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("to".localized) {
                    if vm.isLoading {
                        HStack { ProgressView(); Text("loading".localized) }
                    } else if vm.recipients.isEmpty {
                        Text("no_message_recipients".localized)
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("to".localized, selection: $selected) {
                            Text("—").tag(ChatRecipient?.none)
                            ForEach(vm.recipients) { person in
                                Text(person.name).tag(ChatRecipient?.some(person))
                            }
                        }
                    }
                }
                Section("message".localized) {
                    TextEditor(text: $messageBody).frame(minHeight: 120)
                }
                if let warning = vm.recipientsWarning {
                    Section { Text(warning).foregroundStyle(.orange) }
                }
                if let error = vm.errorMessage {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("new_message".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("send".localized) {
                        Task {
                            // Only dismiss once the message is actually
                            // accepted. The previous version dismissed
                            // unconditionally and never called the API.
                            if await vm.send(to: selected, body: messageBody) {
                                dismiss()
                            }
                        }
                    }
                    .disabled(selected == nil
                              || messageBody.trimmingCharacters(in: .whitespaces).isEmpty
                              || vm.isSending)
                }
            }
            .task { await vm.loadRecipients() }
        }
    }
}

/// A clinician as a possible recipient, before merging.
struct ClinicianCandidate: Equatable {
    let userId: String?
    let name: String?
    let hospitalName: String?
}

extension ChatRecipient {
    /// Care team first, then appointments, as the portal's picker orders
    /// them. First occurrence of a user id wins; entries without a user id or
    /// a name are skipped, and so is the patient's own id.
    static func merge(careTeam: [ClinicianCandidate],
                      appointments: [ClinicianCandidate],
                      excluding ownUserId: String?) -> [ChatRecipient] {
        var seen = Set<String>()
        if let ownUserId, !ownUserId.isEmpty { seen.insert(ownUserId) }
        var out: [ChatRecipient] = []
        for candidate in careTeam + appointments {
            guard let userId = candidate.userId?.trimmingCharacters(in: .whitespaces), !userId.isEmpty,
                  let name = candidate.name?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
                  !seen.contains(userId) else { continue }
            seen.insert(userId)
            out.append(ChatRecipient(id: userId, name: name, subtitle: candidate.hospitalName))
        }
        return out
    }
}

extension CareTeamDTO {
    /// The current primary-care clinician and the history, as recipients.
    /// `doctorUserId` is the user id `/chat/send` needs; the entry `id` is
    /// the care-team link and is never used here.
    var clinicianCandidates: [ClinicianCandidate] {
        let entries = (primaryCare.map { [$0] } ?? []) + (primaryCareHistory ?? [])
        return entries.map {
            ClinicianCandidate(userId: $0.doctorUserId, name: $0.doctorDisplay, hospitalName: $0.hospitalName)
        }
    }
}

@MainActor
final class ComposeMessageViewModel: ObservableObject {
    @Published var recipients: [ChatRecipient] = []
    @Published var isLoading = false
    @Published var isSending = false
    @Published var errorMessage: String?
    /// One source failed: the list may be incomplete.
    @Published var recipientsWarning: String?

    func loadRecipients() async {
        isLoading = true
        errorMessage = nil
        recipientsWarning = nil
        defer { isLoading = false }

        async let careTeamResult = Self.careTeamClinicians()
        async let appointmentResult = Self.appointmentClinicians()
        let (careTeam, appointments) = await (careTeamResult, appointmentResult)

        let ownId = await AuthManager.shared.ensureUserId()
        recipients = ChatRecipient.merge(careTeam: careTeam ?? [],
                                         appointments: appointments ?? [],
                                         excluding: ownId)
        switch (careTeam == nil, appointments == nil) {
        case (true, true):
            errorMessage = "chat_recipients_load_failed".localized
        case (true, false), (false, true):
            recipientsWarning = "chat_recipients_partial_load".localized
        case (false, false):
            break
        }
    }

    /// Nil when the request failed, so the caller can tell "none" from "error".
    private static func careTeamClinicians() async -> [ClinicianCandidate]? {
        do {
            let team: CareTeamDTO = try await APIClient.shared.get(APIEndpoints.careTeam)
            return team.clinicianCandidates
        } catch {
            return nil
        }
    }

    private static func appointmentClinicians() async -> [ClinicianCandidate]? {
        do {
            let appointments: [AppointmentDTO] = try await APIClient.shared.get(
                APIEndpoints.appointments,
                queryItems: [URLQueryItem(name: "page", value: "0"),
                             URLQueryItem(name: "size", value: "50")]
            )
            return appointments.map {
                ClinicianCandidate(userId: $0.staffUserId, name: $0.staffName, hospitalName: $0.hospitalName)
            }
        } catch {
            return nil
        }
    }

    /// Returns true when the message was accepted by the server.
    func send(to recipient: ChatRecipient?, body: String) async -> Bool {
        let text = body.trimmingCharacters(in: .whitespaces)
        guard let recipient, !text.isEmpty else { return false }
        isSending = true
        errorMessage = nil
        defer { isSending = false }
        do {
            let _: ChatMessageDTO = try await APIClient.shared.post(
                APIEndpoints.chatSend,
                body: SendChatMessageRequest(recipientId: recipient.id, content: text)
            )
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}
