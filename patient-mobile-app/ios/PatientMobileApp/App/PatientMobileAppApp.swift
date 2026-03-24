import SwiftUI

@main
struct PatientMobileApp: App {
    @StateObject private var authManager = AuthManager.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(authManager)
                .onAppear { authManager.restoreSession() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background {
                        authManager.recordBackgroundTimestamp()
                    }
                    if phase == .active {
                        authManager.checkInactivityTimeout()
                    }
                }
        }
    }
}
