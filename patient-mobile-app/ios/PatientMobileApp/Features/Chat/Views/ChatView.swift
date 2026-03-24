import SwiftUI

struct ConversationListView: View {
    @StateObject private var viewModel = ChatViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.conversations.isEmpty {
                    HMSLoadingView(message: "Loading messages...")
                } else if let error = viewModel.errorMessage, viewModel.conversations.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadConversations() } }
                } else if viewModel.conversations.isEmpty {
                    HMSEmptyState(icon: "message", title: "No Messages", message: "Your conversations with care providers will appear here.")
                } else {
                    conversationList
                }
            }
            .navigationTitle("Messages")
            .task { await viewModel.loadConversations() }
            .refreshable { await viewModel.loadConversations() }
        }
    }

    private var conversationList: some View {
        List(viewModel.conversations) { conversation in
            NavigationLink {
                ChatThreadView(
                    participantId: conversation.participantId ?? 0,
                    participantName: conversation.participantName ?? "Provider"
                )
            } label: {
                conversationRow(conversation)
            }
        }
        .listStyle(.plain)
    }

    private func conversationRow(_ conv: ConversationDto) -> some View {
        HStack(spacing: 12) {
            // Avatar
            ZStack {
                Circle()
                    .fill(Color.hmsInfo.opacity(0.15))
                    .frame(width: 44, height: 44)
                Text(initials(conv.participantName))
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsInfo)
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(conv.participantName ?? "Unknown")
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsTextPrimary)
                    Spacer()
                    if let time = conv.lastMessageTime {
                        Text(time)
                            .font(.hmsOverline)
                            .foregroundColor(.hmsTextTertiary)
                    }
                }
                HStack {
                    Text(conv.lastMessage ?? "")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                        .lineLimit(1)
                    Spacer()
                    if let unread = conv.unreadCount, unread > 0 {
                        Text("\(unread)")
                            .font(.hmsOverline)
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.hmsPrimary)
                            .clipShape(Capsule())
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func initials(_ name: String?) -> String {
        guard let name else { return "?" }
        let parts = name.split(separator: " ")
        let f = parts.first?.prefix(1) ?? ""
        let l = parts.count > 1 ? parts.last!.prefix(1) : ""
        return "\(f)\(l)".uppercased()
    }
}

// MARK: - Chat Thread

struct ChatThreadView: View {
    let participantId: String
    let participantName: String

    @StateObject private var viewModel = ChatViewModel()
    @State private var messageText = ""

    var body: some View {
        VStack(spacing: 0) {
            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(viewModel.messages) { message in
                            ChatBubble(
                                message: message,
                                isMine: message.senderId == viewModel.currentUserId
                            )
                            .id(message.id)
                        }
                    }
                    .padding(16)
                }
                .onChange(of: viewModel.messages.count) { _, _ in
                    if let last = viewModel.messages.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }

            Divider()

            // Input bar
            HStack(spacing: 12) {
                TextField("Type a message...", text: $messageText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)

                Button {
                    let text = messageText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    messageText = ""
                    Task {
                        await viewModel.sendMessage(recipientId: participantId, content: text)
                    }
                } label: {
                    Image(systemName: "paperplane.fill")
                        .foregroundColor(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? .hmsTextTertiary : .hmsPrimary)
                }
                .disabled(messageText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || viewModel.isSending)
            }
            .padding(12)
            .background(Color.hmsSurface)
        }
        .navigationTitle(participantName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.loadMessages(otherUserId: participantId)
            await viewModel.markAsRead(senderId: participantId)
        }
    }
}

// MARK: - Chat Bubble

private struct ChatBubble: View {
    let message: ChatMessageDto
    let isMine: Bool

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 60) }

            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                Text(message.content ?? "")
                    .font(.hmsBody)
                    .foregroundColor(isMine ? .white : .hmsTextPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(isMine ? Color.hmsPrimary : Color.hmsBackground)
                    .cornerRadius(16)

                if let time = message.timestamp {
                    Text(time)
                        .font(.hmsOverline)
                        .foregroundColor(.hmsTextTertiary)
                }
            }

            if !isMine { Spacer(minLength: 60) }
        }
    }
}
