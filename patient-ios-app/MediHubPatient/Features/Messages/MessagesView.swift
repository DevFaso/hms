import SwiftUI

struct MessagesView: View {
    @StateObject private var vm = MessagesViewModel()

    var body: some View {
        NavigationStack {
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
                    .navigationDestination(for: ChatConversationDTO.self) { conversation in
                        MessageThreadView(conversation: conversation)
                    }
                }
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
        .task { await vm.load() }
    }
}

struct ThreadRowView: View {
    let conversation: ChatConversationDTO
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill")
                .font(.largeTitle).foregroundColor(.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conversation.conversationUserName ?? "Unknown").font(.headline)
                    Spacer()
                    if let unread = conversation.unreadCount, unread > 0 {
                        Text("\(unread)")
                            .font(.caption2).bold().foregroundColor(.white)
                            .padding(6).background(Color.accentColor).clipShape(Circle())
                    }
                }
                Text(conversation.lastMessageContent ?? "").font(.subheadline)
                    .foregroundColor(.secondary).lineLimit(1)
                Text(conversation.lastMessageTimestamp ?? "").font(.caption2)
                    .foregroundColor(.secondary)
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

        guard let userId = AuthManager.shared.currentUserId else {
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
            // unnoticed.
            errorMessage = error.localizedDescription
        }
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
                TextField("Message…", text: $vm.draft)
                    .padding(10)
                    .background(Color(.systemGray6))
                    .cornerRadius(20)
                Button(action: { Task { await vm.send() } }) {
                    Image(systemName: "paperplane.fill").foregroundColor(.accentColor)
                }
                .disabled(vm.draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding()
        }
        .navigationTitle(conversation.conversationUserName ?? "Message")
        .navigationBarTitleDisplayMode(.inline)
        .task { await vm.load() }
    }
}

struct MessageBubble: View {
    let message: ChatMessageDTO
    let isOwn: Bool
    var body: some View {
        HStack {
            if isOwn { Spacer() }
            Text(message.content ?? "")
                .padding(12)
                .background(isOwn ? Color.accentColor : Color(.systemGray5))
                .foregroundColor(isOwn ? .white : .primary)
                .cornerRadius(16)
            if !isOwn { Spacer() }
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

        guard let userId = currentUserId else {
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
        } catch {
            errorMessage = error.localizedDescription
        }
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
/// recipients are derived from recent appointments — the same fallback the
/// Android app uses. `/me/patient/care-team` is deliberately not used: it
/// returns primaryCare/primaryCareHistory entries that carry no user id, so
/// nothing in that payload can address a message.
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

@MainActor
final class ComposeMessageViewModel: ObservableObject {
    @Published var recipients: [ChatRecipient] = []
    @Published var isLoading = false
    @Published var isSending = false
    @Published var errorMessage: String?

    func loadRecipients() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let appointments: [AppointmentDTO] = try await APIClient.shared.get(
                APIEndpoints.appointments,
                queryItems: [URLQueryItem(name: "page", value: "0"),
                             URLQueryItem(name: "size", value: "50")]
            )
            var seen = Set<String>()
            recipients = appointments.compactMap { (appointment: AppointmentDTO) -> ChatRecipient? in
                guard let userId = appointment.staffUserId, !userId.isEmpty,
                      let name = appointment.staffName, !name.isEmpty,
                      !seen.contains(userId) else { return nil }
                seen.insert(userId)
                return ChatRecipient(id: userId, name: name,
                                     subtitle: appointment.hospitalName)
            }
        } catch {
            errorMessage = error.localizedDescription
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
