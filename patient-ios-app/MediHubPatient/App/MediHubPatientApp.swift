import SwiftUI

@main
struct MediHubPatientApp: App {
    @StateObject private var authManager = AuthManager.shared
    @StateObject private var localizationManager = LocalizationManager.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(authManager)
                .environmentObject(localizationManager)
        }
    }
}
