import Foundation

@MainActor
final class ChatViewModel: ObservableObject {
    @Published var conversations: [ConversationDto] = []
    @Published var messages: [ChatMessageDto] = []
    @Published var isLoading = false
    @Published var isSending = false
    @Published var errorMessage: String?

    private let api = APIClient.shared

    var currentUserId: String {
        AuthManager.shared.currentUser?.id ?? ""
    }

    func loadConversations() async {
        isLoading = true
        errorMessage = nil
        do {
            conversations = try await api.request(
                .conversations(userId: currentUserId),
                type: [ConversationDto].self
            )
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func loadMessages(otherUserId: String) async {
        isLoading = true
        errorMessage = nil
        do {
            messages = try await api.request(
                .chatHistory(user1Id: currentUserId, user2Id: otherUserId, page: 0, size: 50),
                type: [ChatMessageDto].self
            )
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func sendMessage(recipientId: String, content: String) async {
        isSending = true
        do {
            try await api.request(
                .sendMessage,
                body: SendMessageRequest(
                    senderId: currentUserId,
                    recipientId: recipientId,
                    content: content
                )
            )
            await loadMessages(otherUserId: recipientId)
        } catch {
            errorMessage = error.localizedDescription
        }
        isSending = false
    }

    func markAsRead(senderId: String) async {
        try? await api.request(.markChatRead(senderId: senderId, recipientId: currentUserId))
    }
}

// MARK: - DTOs

struct ConversationDto: Decodable, Identifiable {
    let id: String
    let participantId: String?
    let participantName: String?
    let participantRole: String?
    let lastMessage: String?
    let lastMessageTime: String?
    let unreadCount: Int?
    let avatarUrl: String?
}

struct ChatMessageDto: Decodable, Identifiable {
    let id: String
    let senderId: String?
    let recipientId: String?
    let content: String?
    let timestamp: String?
    let isRead: Bool?
    let senderName: String?
}

struct SendMessageRequest: Encodable {
    let senderId: String
    let recipientId: String
    let content: String
}
