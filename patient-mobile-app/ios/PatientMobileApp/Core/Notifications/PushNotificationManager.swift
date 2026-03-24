import Foundation
import UserNotifications

/// Push notification manager for APNs registration and handling
final class PushNotificationManager: NSObject, ObservableObject {
    static let shared = PushNotificationManager()

    @Published var isAuthorized = false
    @Published var deviceToken: String?

    override private init() {
        super.init()
    }

    // MARK: - Permission

    func requestAuthorization() async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(
                options: [.alert, .badge, .sound]
            )
            await MainActor.run { isAuthorized = granted }
            if granted {
                await MainActor.run {
                    // Must call on main thread
                    registerForRemoteNotifications()
                }
            }
            return granted
        } catch {
            return false
        }
    }

    @MainActor
    private func registerForRemoteNotifications() {
        #if !targetEnvironment(simulator)
        UIApplication.shared.registerForRemoteNotifications()
        #endif
    }

    // MARK: - Token Handling

    func didRegisterForRemoteNotifications(deviceToken data: Data) {
        let token = data.map { String(format: "%02.2hhx", $0) }.joined()
        self.deviceToken = token
        Task { await sendTokenToBackend(token) }
    }

    private func sendTokenToBackend(_ token: String) async {
        struct TokenBody: Encodable { let token: String; let platform: String }
        try? await APIClient.shared.request(
            .registerDeviceToken,
            body: TokenBody(token: token, platform: "ios")
        )
    }

    func didFailToRegisterForRemoteNotifications(error: Error) {
        // Silent failure — push is optional enhancement
    }

    // MARK: - Check Current Status

    func checkCurrentStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        await MainActor.run {
            isAuthorized = settings.authorizationStatus == .authorized
        }
    }
}
