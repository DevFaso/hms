import SwiftUI

struct LoginView: View {
    @StateObject private var vm = LoginViewModel()
    @FocusState private var focusedField: Field?

    enum Field { case username, password }

    var body: some View {
        ZStack {
            // Background gradient
            LinearGradient(
                colors: [Color("BrandBlue"), Color("BrandDarkBlue")],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 32) {
                    // Logo + Title
                    VStack(spacing: 12) {
                        Image(systemName: "cross.circle.fill")
                            .font(.system(size: 72))
                            .foregroundColor(.white)

                        Text("MediHub Patient")
                            .font(.largeTitle).bold()
                            .foregroundColor(.white)

                        Text("Your health, in your hands")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.8))
                    }
                    .padding(.top, 60)

                    // Card
                    VStack(spacing: 20) {

                        // Error banner
                        if let error = vm.errorMessage {
                            HStack {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.orange)
                                Text(error)
                                    .font(.caption)
                                    .foregroundColor(.primary)
                                Spacer()
                            }
                            .padding(12)
                            .background(Color.orange.opacity(0.12))
                            .cornerRadius(10)
                        }

                        // Biometric button (shown when credentials are saved)
                        if vm.biometricAvailable && !vm.showUsernameForm {
                            Button(action: vm.loginWithBiometrics) {
                                HStack(spacing: 12) {
                                    Image(systemName: vm.biometricType == "Face ID"
                                          ? "faceid" : "touchid")
                                        .font(.title2)
                                    Text("Log in with \(vm.biometricType)")
                                        .fontWeight(.semibold)
                                }
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .cornerRadius(14)
                            }
                            .disabled(vm.isLoading)

                            Button("Use username instead") {
                                withAnimation { vm.showUsernameForm = true }
                            }
                            .font(.footnote)
                            .foregroundColor(.secondary)
                        }

                        // Username / Password form
                        if vm.showUsernameForm || !vm.biometricAvailable {
                            VStack(spacing: 14) {
                                // Username
                                VStack(alignment: .leading, spacing: 6) {
                                    Label("Username", systemImage: "person")
                                        .font(.caption).foregroundColor(.secondary)
                                    TextField("Enter username", text: $vm.username)
                                        .autocapitalization(.none)
                                        .textContentType(.username)
                                        .focused($focusedField, equals: .username)
                                        .submitLabel(.next)
                                        .onSubmit { focusedField = .password }
                                        .padding(12)
                                        .background(Color(.systemGray6))
                                        .cornerRadius(10)
                                }

                                // Password
                                VStack(alignment: .leading, spacing: 6) {
                                    Label("Password", systemImage: "lock")
                                        .font(.caption).foregroundColor(.secondary)
                                    SecureField("Enter password", text: $vm.password)
                                        .textContentType(.password)
                                        .focused($focusedField, equals: .password)
                                        .submitLabel(.go)
                                        .onSubmit { vm.loginWithCredentials() }
                                        .padding(12)
                                        .background(Color(.systemGray6))
                                        .cornerRadius(10)
                                }

                                // Login button
                                Button(action: vm.loginWithCredentials) {
                                    Group {
                                        if vm.isLoading {
                                            ProgressView()
                                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                        } else {
                                            Text("Log In")
                                                .fontWeight(.semibold)
                                        }
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Color.accentColor)
                                    .foregroundColor(.white)
                                    .cornerRadius(14)
                                }
                                .disabled(vm.isLoading)

                                // Back to biometric
                                if vm.biometricAvailable {
                                    Button("Use \(vm.biometricType) instead") {
                                        withAnimation { vm.showUsernameForm = false }
                                    }
                                    .font(.footnote)
                                    .foregroundColor(.secondary)
                                }
                            }
                        }

                        Divider()

                        // Forgot password
                        Button("Forgot password?") {
                            // TODO: navigate to password reset
                        }
                        .font(.footnote)
                        .foregroundColor(.accentColor)
                    }
                    .padding(24)
                    .background(Color(.systemBackground))
                    .cornerRadius(20)
                    .shadow(color: .black.opacity(0.15), radius: 20, x: 0, y: 8)
                    .padding(.horizontal, 24)

                    Spacer(minLength: 40)
                }
            }
        }
        .onTapGesture { focusedField = nil }
    }
}

#Preview {
    LoginView()
}
