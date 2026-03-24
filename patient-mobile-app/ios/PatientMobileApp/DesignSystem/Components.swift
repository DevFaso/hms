import SwiftUI

// MARK: - HMS Card
struct HMSCard<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(16)
            .background(Color.hmsSurface)
            .cornerRadius(12)
            .shadow(color: .black.opacity(0.05), radius: 8, x: 0, y: 2)
    }
}

// MARK: - HMS Primary Button
struct HMSPrimaryButton: View {
    let title: String
    let isLoading: Bool
    let action: () -> Void

    init(_ title: String, isLoading: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.isLoading = isLoading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if isLoading {
                    ProgressView()
                        .tint(.white)
                }
                Text(title)
                    .font(.hmsBodyMedium)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.hmsPrimary)
            .foregroundColor(.white)
            .cornerRadius(10)
        }
        .disabled(isLoading)
    }
}

// MARK: - HMS Text Field
struct HMSTextField: View {
    let placeholder: String
    @Binding var text: String
    var isSecure: Bool = false
    var icon: String?

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .foregroundColor(.hmsTextTertiary)
                    .frame(width: 20)
            }

            if isSecure {
                SecureField(placeholder, text: $text)
            } else {
                TextField(placeholder, text: $text)
            }
        }
        .padding(14)
        .background(Color.hmsBackground)
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(Color.hmsBorder, lineWidth: 1)
        )
    }
}

// MARK: - HMS Section Header
struct HMSSectionHeader: View {
    let title: String
    var action: (() -> Void)? = nil
    var actionLabel: String = "See All"

    var body: some View {
        HStack {
            Text(title)
                .font(.hmsSubheadline)
                .foregroundColor(.hmsTextPrimary)

            Spacer()

            if let action {
                Button(actionLabel, action: action)
                    .font(.hmsCaptionMedium)
                    .foregroundColor(.hmsPrimary)
            }
        }
    }
}

// MARK: - Status Badge
struct HMSStatusBadge: View {
    let text: String
    let color: Color

    /// Convenience init that maps a status string to an appropriate color automatically.
    init(status: String) {
        self.text = status.replacingOccurrences(of: "_", with: " ").capitalized
        switch status.lowercased() {
        case "active", "ongoing", "in_progress", "in progress":
            self.color = .hmsSuccess
        case "completed", "done", "fulfilled":
            self.color = .hmsInfo
        case "cancelled", "canceled", "rejected":
            self.color = .hmsError
        case "on_hold", "on hold", "paused", "suspended":
            self.color = .hmsWarning
        case "pending", "scheduled", "uploaded":
            self.color = .hmsPrimary
        default:
            self.color = .hmsTextSecondary
        }
    }

    init(text: String, color: Color) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(.hmsOverline)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.12))
            .foregroundColor(color)
            .cornerRadius(6)
            .accessibilityLabel("Status: \(text)")
    }
}

// MARK: - Loading View
struct HMSLoadingView: View {
    var message: String = "Loading..."

    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.2)
            Text(message)
                .font(.hmsCaption)
                .foregroundColor(.hmsTextSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Error View
struct HMSErrorView: View {
    let message: String
    var retryAction: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40))
                .foregroundColor(.hmsWarning)
            Text(message)
                .font(.hmsBody)
                .foregroundColor(.hmsTextSecondary)
                .multilineTextAlignment(.center)
            if let retryAction {
                Button("Try Again", action: retryAction)
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsPrimary)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Empty State
struct HMSEmptyState: View {
    let icon: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 48))
                .foregroundColor(.hmsTextTertiary)
                .accessibilityHidden(true)
            Text(title)
                .font(.hmsSubheadline)
                .foregroundColor(.hmsTextPrimary)
            Text(message)
                .font(.hmsCaption)
                .foregroundColor(.hmsTextSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}
