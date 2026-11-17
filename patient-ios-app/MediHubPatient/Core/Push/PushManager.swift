import Combine
import Foundation
import UIKit
import UserNotifications

// MARK: - Push notifications (APNs directly, no Firebase)

/// Registers this install for chat notifications and routes a tapped one.
///
/// The backend sends to APNs itself with a token-based key, so the only
/// thing the app hands over is the raw APNs device token as lowercase hex.
/// Everything here is harmless when push is not set up: without the
/// `aps-environment` entitlement `registerForRemoteNotifications` fails into
/// `didFailToRegisterForRemoteNotificationsWithError`, which is ignored, and
/// no registration is ever sent.
@MainActor
final class PushManager: ObservableObject {
    static let shared = PushManager()

    /// A notification the patient tapped, waiting for the Messages tab to
    /// open it. Cleared by whoever acts on it.
    @Published var messagesRequest: MessagesRequest?

    private var deviceToken: Data?
    /// Token and locale of the last registration attempted this session.
    /// One attempt per sign-in, token change or language change — never a
    /// retry loop: the push endpoints may not exist on every backend yet, and
    /// a 404 there must cost one silent request, not one per screen.
    private var lastAttempt: String?
    private var cancellables = Set<AnyCancellable>()

    private init() {
        // The server composes the notification text in the registered locale,
        // so a language change re-registers. `@Published` emits BEFORE the
        // value changes, hence the value is passed along rather than re-read.
        LocalizationManager.shared.$currentLanguage
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] language in
                Task { @MainActor in await self?.uploadRegistration(locale: language) }
            }
            .store(in: &cancellables)
    }

    // MARK: Lifecycle

    /// After any sign-in and on a restored session. Never prompts: it only
    /// re-registers when the patient already said yes.
    func sessionDidStart() {
        Task { @MainActor in
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            if Self.isAllowed(settings.authorizationStatus) {
                UIApplication.shared.registerForRemoteNotifications()
            }
            await self.uploadRegistration(locale: nil)
        }
    }

    /// The permission prompt, asked in context — the first time the patient
    /// opens Messages — rather than at a cold start with no explanation.
    func requestAuthorizationIfNeeded() async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined:
            let granted = (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
            if granted { UIApplication.shared.registerForRemoteNotifications() }
        default:
            if Self.isAllowed(settings.authorizationStatus) {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }

    private static func isAllowed(_ status: UNAuthorizationStatus) -> Bool {
        status == .authorized || status == .provisional || status == .ephemeral
    }

    /// From the app delegate. The token can change (restore, reinstall), so
    /// every delivery is sent on.
    func didRegister(deviceToken: Data) {
        self.deviceToken = deviceToken
        Task { @MainActor in await self.uploadRegistration(locale: nil) }
    }

    /// Sign-out: the next session registers afresh.
    func sessionDidEnd() {
        lastAttempt = nil
    }

    private func uploadRegistration(locale: String?) async {
        guard let token = deviceToken, AuthManager.shared.isAuthenticated else { return }
        let body = PushRegistration.body(token: token,
                                         locale: locale ?? LocalizationManager.shared.currentLanguage,
                                         appVersion: PushRegistration.appVersion)
        let attempt = body.token + "|" + body.locale
        guard attempt != lastAttempt else { return }
        // Recorded before the request, so a failure is not retried either.
        lastAttempt = attempt
        // Best effort and silent on ANY failure (404/405 included): the send
        // side is off by default server-side, and a registration problem
        // must never surface to the patient or hold up anything else.
        try? await APIClient.shared.sendNoContent(
            .PUT,
            path: APIEndpoints.pushDevice(installationId: PushRegistration.installationId()),
            body: body
        )
    }

    // MARK: Tap handling

    /// Notifications carry no PHI: `{"type": "CHAT_MESSAGE", "senderId": …}`.
    func handleNotificationTap(type: String?, senderId: String?) {
        guard type == "CHAT_MESSAGE" else { return }
        messagesRequest = MessagesRequest(senderId: senderId)
    }
}

/// The wire half of push registration: pure values, no actor.
enum PushRegistration {
    static let installationIdKey = "push_installation_id"

    /// A random id generated once per install and kept in app storage. It is
    /// NOT the user: the server re-binds it to whoever signs in next.
    static func installationId(defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: installationIdKey), !existing.isEmpty {
            return existing
        }
        let fresh = UUID().uuidString.lowercased()
        defaults.set(fresh, forKey: installationIdKey)
        return fresh
    }

    /// The APNs device token as the backend expects it: lowercase hex, no
    /// spaces or brackets (`Data.description` is neither on current iOS).
    static func hexString(_ token: Data) -> String {
        token.map { String(format: "%02x", $0) }.joined()
    }

    static func body(token: Data, locale: String, appVersion: String) -> PushDeviceRegistration {
        PushDeviceRegistration(token: hexString(token), platform: "IOS", locale: locale, appVersion: appVersion)
    }

    static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }
}

/// "Open Messages", and the thread with this sender when it is known.
struct MessagesRequest: Equatable {
    let id = UUID()
    let senderId: String?
}

/// `PUT /me/push-devices/{installationId}`.
struct PushDeviceRegistration: Encodable, Equatable {
    let token: String
    let platform: String
    let locale: String
    let appVersion: String
}

// MARK: - Notification center delegate

/// Kept apart from `PushManager` so the delegate callbacks, which the system
/// makes off the main actor, only pass plain strings across.
final class PushNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        let type = info["type"] as? String
        let senderId = info["senderId"] as? String
        Task { @MainActor in
            PushManager.shared.handleNotificationTap(type: type, senderId: senderId)
        }
        completionHandler()
    }
}

// MARK: - App delegate

/// The two APNs callbacks only exist on a `UIApplicationDelegate`.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// `UNUserNotificationCenter.delegate` is weak; this keeps it alive.
    private let notificationDelegate = PushNotificationDelegate()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Set at launch so a tap that cold-starts the app is delivered.
        UNUserNotificationCenter.current().delegate = notificationDelegate
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in
            PushManager.shared.didRegister(deviceToken: deviceToken)
        }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // Expected until the App ID has the Push Notifications capability and
        // the build carries `aps-environment`: nothing to show, nothing to send.
        #if DEBUG
        print("[Push] registration unavailable: \(error.localizedDescription)")
        #endif
    }
}
