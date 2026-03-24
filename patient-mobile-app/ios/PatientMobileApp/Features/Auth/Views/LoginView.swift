import SwiftUI

struct LoginView: View {
    @EnvironmentObject var authManager: AuthManager
    @StateObject private var viewModel = LoginViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 32) {
                    // Logo & Header
                    VStack(spacing: 12) {
                        Image(systemName: "heart.circle.fill")
                            .font(.system(size: 72))
                            .foregroundColor(.hmsPrimary)

                        Text("HMS Patient")
                            .font(.hmsTitle)
                            .foregroundColor(.hmsTextPrimary)

                        Text("Sign in to access your health records")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextSecondary)
                    }
                    .padding(.top, 60)

                    // Form
                    VStack(spacing: 16) {
                        HMSTextField(
                            placeholder: "Username or Email",
                            text: $viewModel.username,
                            icon: "person"
                        )
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)

                        HMSTextField(
                            placeholder: "Password",
                            text: $viewModel.password,
                            isSecure: true,
                            icon: "lock"
                        )
                        .textContentType(.password)

                        if let error = viewModel.errorMessage {
                            Text(error)
                                .font(.hmsCaption)
                                .foregroundColor(.hmsError)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        HMSPrimaryButton("Sign In", isLoading: authManager.isLoading) {
                            Task {
                                await viewModel.login(authManager: authManager)
                            }
                        }
                        .disabled(viewModel.username.isEmpty || viewModel.password.isEmpty)
                    }

                    // Footer links
                    VStack(spacing: 12) {
                        Button("Forgot Password?") {
                            viewModel.showForgotPassword = true
                        }
                        .font(.hmsCaptionMedium)
                        .foregroundColor(.hmsPrimary)

                        HStack(spacing: 4) {
                            Text("Don't have an account?")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                            Button("Register") {
                                viewModel.showRegister = true
                            }
                            .font(.hmsCaptionMedium)
                            .foregroundColor(.hmsPrimary)
                        }
                    }
                }
                .padding(.horizontal, 24)
            }
            .background(Color.hmsBackground)
            .sheet(isPresented: $viewModel.showForgotPassword) {
                ForgotPasswordView()
            }
            .sheet(isPresented: $viewModel.showRegister) {
                RegisterView()
            }
        }
    }
}

// MARK: - Placeholder sheets

struct ForgotPasswordView: View {
    @Environment(\.dismiss) var dismiss
    @State private var email = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Text("Enter your email address and we'll send you a reset link.")
                    .font(.hmsBody)
                    .foregroundColor(.hmsTextSecondary)
                    .multilineTextAlignment(.center)

                HMSTextField(placeholder: "Email", text: $email, icon: "envelope")
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)

                HMSPrimaryButton("Send Reset Link") {
                    // TODO: call authService.requestPasswordReset
                    dismiss()
                }
            }
            .padding(24)
            .navigationTitle("Reset Password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

struct RegisterView: View {
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            HMSEmptyState(
                icon: "person.badge.plus",
                title: "Registration",
                message: "Patient self-registration coming soon."
            )
            .navigationTitle("Register")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
