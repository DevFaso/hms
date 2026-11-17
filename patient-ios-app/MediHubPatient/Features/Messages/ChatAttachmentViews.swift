import AVFoundation
import SwiftUI
import UIKit

// MARK: - One attachment in a chat bubble

/// A photo as a thumbnail that opens full screen, a voice note as a player,
/// anything else as a file row that opens in QuickLook. Every byte comes
/// through `ChatAttachmentCache`, i.e. the authenticated download.
struct ChatAttachmentView: View {
    let attachment: ChatAttachmentDTO

    var body: some View {
        switch attachment.kindEnum {
        case .photo:
            ChatPhotoAttachmentView(attachment: attachment)
        case .audio:
            ChatAudioAttachmentView(attachment: attachment)
        case .other:
            ChatFileAttachmentView(attachment: attachment)
        }
    }
}

enum ChatAttachmentFormat {
    /// "0:07", "1:30".
    static func duration(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        return String(format: "%d:%02d", clamped / 60, clamped % 60)
    }

    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let url = error as? URLError, url.code == .cancelled { return true }
        return false
    }
}

// MARK: - Photo

struct ChatPhotoAttachmentView: View {
    let attachment: ChatAttachmentDTO
    @State private var thumbnail: UIImage?
    @State private var fullImage: UIImage?
    @State private var failed = false
    @State private var showFull = false

    var body: some View {
        Group {
            if let thumbnail {
                Button { showFull = true } label: {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 200, height: 200)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("chat_attachment_photo".localized))
                .accessibilityHint(Text("chat_attachment_open_hint".localized))
            } else if failed {
                AttachmentFailureRow(systemImage: "photo") { Task { await load() } }
            } else {
                ProgressView()
                    .frame(width: 200, height: 150)
                    .background(Color(.systemGray6))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .task { await load() }
        .fullScreenCover(isPresented: $showFull) {
            if let fullImage {
                ChatPhotoFullView(image: fullImage)
            }
        }
    }

    private func load() async {
        guard thumbnail == nil else { return }
        failed = false
        do {
            let url = try await ChatAttachmentCache.shared.fileURL(for: attachment)
            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else {
                failed = true
                return
            }
            fullImage = image
            thumbnail = image.preparingThumbnail(of: CGSize(width: 400, height: 400)) ?? image
        } catch {
            if !ChatAttachmentFormat.isCancellation(error) { failed = true }
        }
    }
}

struct ChatPhotoFullView: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1

    var body: some View {
        NavigationStack {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale)
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in scale = min(max(value.magnification, 1), 4) }
                        .onEnded { _ in withAnimation { if scale < 1.1 { scale = 1 } } }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
                .accessibilityLabel(Text("chat_attachment_photo".localized))
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("done".localized) { dismiss() }
                    }
                }
                .toolbarBackground(.visible, for: .navigationBar)
        }
    }
}

// MARK: - Voice note

/// Plays one cached voice note. AVAudioPlayer reads a file, which is why the
/// bytes go through the cache rather than memory.
@MainActor
final class ChatAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var isPlaying = false
    @Published private(set) var isLoading = false
    @Published var errorText: String?

    private var player: AVAudioPlayer?

    func toggle(_ attachment: ChatAttachmentDTO) async {
        if let player {
            if player.isPlaying {
                player.pause()
                isPlaying = false
            } else {
                player.play()
                isPlaying = true
            }
            return
        }
        isLoading = true
        errorText = nil
        defer { isLoading = false }
        do {
            let url = try await ChatAttachmentCache.shared.fileURL(for: attachment)
            // .playback so a voice note is heard with the ring switch on silent.
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try? AVAudioSession.sharedInstance().setActive(true)
            let newPlayer = try AVAudioPlayer(contentsOf: url)
            newPlayer.delegate = self
            newPlayer.play()
            player = newPlayer
            isPlaying = true
        } catch {
            if !ChatAttachmentFormat.isCancellation(error) {
                errorText = "chat_audio_unplayable".localized
            }
        }
    }

    func stop() {
        player?.stop()
        player?.currentTime = 0
        isPlaying = false
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.isPlaying = false
            self.player?.currentTime = 0
        }
    }
}

struct ChatAudioAttachmentView: View {
    let attachment: ChatAttachmentDTO
    @StateObject private var player = ChatAudioPlayer()

    var body: some View {
        HStack(spacing: 10) {
            Button {
                Task { await player.toggle(attachment) }
            } label: {
                if player.isLoading {
                    ProgressView().frame(width: 32, height: 32)
                } else {
                    Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(.accentColor)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(player.isPlaying ? "chat_audio_pause".localized : "chat_audio_play".localized))

            VStack(alignment: .leading, spacing: 2) {
                Text("chat_attachment_voice_note".localized)
                    .font(.subheadline.weight(.medium))
                if let seconds = attachment.durationSeconds {
                    Text(ChatAttachmentFormat.duration(seconds))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                if let error = player.errorText {
                    Text(error).font(.caption).foregroundColor(.red)
                }
            }
        }
        .padding(10)
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .onDisappear { player.stop() }
    }
}

// MARK: - Any other file

struct ChatFileAttachmentView: View {
    let attachment: ChatAttachmentDTO
    @State private var preview: DocumentPreviewItem?
    @State private var isOpening = false
    @State private var failed = false

    var body: some View {
        Button {
            Task { await open() }
        } label: {
            HStack(spacing: 10) {
                if isOpening {
                    ProgressView()
                } else {
                    Image(systemName: failed ? "exclamationmark.triangle" : "doc.fill")
                        .foregroundColor(failed ? .red : .accentColor)
                }
                Text(attachment.displayName ?? "chat_attachment_file".localized)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                    .lineLimit(2)
            }
            .padding(10)
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(isOpening)
        .accessibilityHint(Text("chat_attachment_open_hint".localized))
        .sheet(item: $preview) { item in
            DocumentPreview(url: item.url)
        }
    }

    private func open() async {
        isOpening = true
        failed = false
        defer { isOpening = false }
        do {
            let url = try await ChatAttachmentCache.shared.fileURL(for: attachment)
            preview = DocumentPreviewItem(url: url)
        } catch {
            if !ChatAttachmentFormat.isCancellation(error) { failed = true }
        }
    }
}

// MARK: - Shared failure row

private struct AttachmentFailureRow: View {
    let systemImage: String
    let retry: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage).foregroundColor(.secondary)
            Text("chat_attachment_unavailable".localized)
                .font(.footnote)
                .foregroundColor(.secondary)
            Button("retry".localized, action: retry)
                .font(.footnote.weight(.semibold))
        }
        .padding(10)
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}
