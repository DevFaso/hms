import SwiftUI

struct ContentView: View {
    @EnvironmentObject var authManager: AuthManager

    var body: some View {
        Group {
            if authManager.isAuthenticated {
                MainTabView()
            } else {
                LoginView()
            }
        }
        .animation(.easeInOut, value: authManager.isAuthenticated)
        // The brand teal as the app-wide tint (links, toolbar buttons,
        // toggles), matching ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME.
        .tint(Color("AccentColor"))
    }
}
