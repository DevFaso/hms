import SwiftUI

@main
struct MediHubPatientApp: App {
    @StateObject private var authManager = AuthManager.shared
    @StateObject private var localizationManager = LocalizationManager.shared
    @StateObject private var keycloakAuthService = KeycloakAuthService.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(authManager)
                .environmentObject(localizationManager)
                .environmentObject(keycloakAuthService)
                .onOpenURL { url in
                    // KC-3 — forward OIDC redirect to AppAuth
                    _ = keycloakAuthService.resume(url: url)
                }
        }
    }
}
