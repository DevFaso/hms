import SwiftUI

struct MessagesView: View {
    @StateObject private var vm = MessagesViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.threads.isEmpty {
                    ProgressView("Loading messages…")
                } else if vm.threads.isEmpty {
                    ContentUnavailableView("No Messages",
                        systemImage: "message",
                        description: Text("No messages yet."))
                } else {
                    List(vm.threads) { thread in
                        NavigationLink(value: thread) {
                            ThreadRowView(thread: thread)
                        }
                    }
                    .listStyle(.insetGrouped)
                    .navigationDestination(for: ChatThreadDTO.self) { thread in
                        MessageThreadView(thread: thread)
                    }
                }
            }
            .navigationTitle("Messages")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
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
    let thread: ChatThreadDTO
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill")
                .font(.largeTitle).foregroundColor(.accentColor)
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(thread.recipientName ?? "Unknown").font(.headline)
                    Spacer()
                    if let unread = thread.unreadCount, unread > 0 {
                        Text("\(unread)")
                            .font(.caption2).bold().foregroundColor(.white)
                            .padding(6).background(Color.accentColor).clipShape(Circle())
                    }
                }
                Text(thread.lastMessage ?? "").font(.subheadline)
                    .foregroundColor(.secondary).lineLimit(1)
                Text(thread.lastMessageAt ?? "").font(.caption2).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class MessagesViewModel: ObservableObject {
    @Published var threads: [ChatThreadDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        threads = (try? await APIClient.shared.get(APIEndpoints.chatThreads)) ?? []
        isLoading = false
    }
}

// MARK: - Message Thread

struct MessageThreadView: View {
    let thread: ChatThreadDTO
    @StateObject private var vm: MessageThreadViewModel

    init(thread: ChatThreadDTO) {
        self.thread = thread
        _vm = StateObject(wrappedValue: MessageThreadViewModel(threadId: thread.id ?? ""))
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
        .navigationTitle(thread.recipientName ?? "Message")
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
    let threadId: String
    let currentUserId: String? = nil // set from AuthManager if needed

    init(threadId: String) { self.threadId = threadId }

    func load() async {
        isLoading = true
        messages = (try? await APIClient.shared.get(
            APIEndpoints.chatMessages(threadId: threadId)
        )) ?? []
        isLoading = false
    }

    func send() async {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft = ""
        let body = SendMessageRequest(content: text, attachmentUrl: nil)
        if let msg: ChatMessageDTO = try? await APIClient.shared.post(
            APIEndpoints.chatMessages(threadId: threadId), body: body
        ) {
            messages.append(msg)
        }
    }
}

// MARK: - Compose

struct ComposeMessageView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var recipient = ""
    @State private var subject = ""
    @State private var messageBody = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("To") { TextField("Recipient", text: $recipient) }
                Section("Subject") { TextField("Subject", text: $subject) }
                Section("Message") {
                    TextEditor(text: $messageBody).frame(minHeight: 120)
                }
            }
            .navigationTitle("New Message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") { dismiss() } // TODO: wire to API
                }
            }
        }
    }
}
