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
            // The compose button used to open a form whose Send action only
            // called dismiss() — it never reached the API. Removed rather than
            // left as a lie; the Android app has no new-message composer either,
            // so the two surfaces now match. Patients reply within a thread a
            // clinician started.
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

        guard let userId = AuthManager.shared.currentUser?.id else {
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
    var currentUserId: String? { AuthManager.shared.currentUser?.id }

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
            messages = page.reversed()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func send() async {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft = ""
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
