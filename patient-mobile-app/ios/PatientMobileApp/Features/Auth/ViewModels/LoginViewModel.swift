import Foundation

@MainActor
final class LoginViewModel: ObservableObject {
    @Published var username = ""
    @Published var password = ""
    @Published var errorMessage: String?
    @Published var showForgotPassword = false
    @Published var showRegister = false

    func login(authManager: AuthManager) async {
        errorMessage = nil
        do {
            try await authManager.login(username: username, password: password)
        } catch let error as APIError {
            errorMessage = error.localizedDescription
        } catch {
            errorMessage = "Unable to connect. Please check your internet connection."
        }
    }
}
