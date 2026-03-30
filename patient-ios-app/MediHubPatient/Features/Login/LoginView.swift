import SwiftUI

struct LoginView: View {
    @StateObject private var vm = LoginViewModel()
    @EnvironmentObject var localization: LocalizationManager
    @FocusState private var focusedField: Field?

    enum Field { case username, password }

    var body: some View {
        ZStack {
            // Layered gradient background
            LinearGradient(
                stops: [
                    .init(color: Color("BrandBlue"), location: 0),
                    .init(color: Color("BrandDarkBlue"), location: 0.6),
                    .init(color: Color("BrandDarkBlue").opacity(0.95), location: 1)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            // Subtle pattern overlay
            Circle()
                .fill(Color.white.opacity(0.04))
                .frame(width: 400, height: 400)
                .offset(x: -120, y: -200)
                .blur(radius: 60)

            Circle()
                .fill(Color.white.opacity(0.03))
                .frame(width: 300, height: 300)
                .offset(x: 150, y: 300)
                .blur(radius: 40)

            ScrollView(showsIndicators: false) {
                VStack(spacing: 36) {
                    // Logo + Title
                    VStack(spacing: 16) {
                        ZStack {
                            Circle()
                                .fill(Color.white.opacity(0.15))
                                .frame(width: 100, height: 100)
                            Image(systemName: "cross.circle.fill")
                                .font(.system(size: 56, weight: .light))
                                .foregroundStyle(.white)
                        }

                        Text("login_title".localized)
                            .font(.system(size: 32, weight: .bold, design: .rounded))
                            .foregroundStyle(.white)

                        Text("login_subtitle".localized)
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.75))
                    }
                    .padding(.top, 72)

                    // Card
                    VStack(spacing: 22) {
                        // Error banner
                        if let error = vm.errorMessage {
                            HStack(spacing: 10) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.orange)
                                    .font(.subheadline)
                                Text(error)
                                    .font(.caption)
                                    .foregroundStyle(.primary)
                                Spacer()
                            }
                            .padding(14)
                            .background(Color.orange.opacity(0.1))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        }

                        // Biometric button
                        if vm.biometricAvailable && !vm.showUsernameForm {
                            Button(action: vm.loginWithBiometrics) {
                                HStack(spacing: 12) {
                                    Image(systemName: vm.biometricType == "Face ID" ? "faceid" : "touchid")
                                        .font(.title2)
                                    Text(String(format: "biometric_sign_in".localized, vm.biometricType))
                                        .fontWeight(.semibold)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(
                                    LinearGradient(colors: [Color("BrandBlue"), Color("BrandDarkBlue")],
                                                   startPoint: .leading, endPoint: .trailing)
                                )
                                .foregroundStyle(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                                .shadow(color: Color("BrandBlue").opacity(0.3), radius: 8, y: 4)
                            }
                            .disabled(vm.isLoading)

                            Button("use_username".localized) {
                                withAnimation(.spring(response: 0.35)) { vm.showUsernameForm = true }
                            }
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(.secondary)
                        }

                        // Username / Password form
                        if vm.showUsernameForm || !vm.biometricAvailable {
                            VStack(spacing: 16) {
                                // Username field
                                VStack(alignment: .leading, spacing: 6) {
                                    Label("username_label".localized, systemImage: "person")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(.secondary)
                                    TextField("username_placeholder".localized, text: $vm.username)
                                        .autocapitalization(.none)
                                        .textContentType(.username)
                                        .focused($focusedField, equals: .username)
                                        .submitLabel(.next)
                                        .onSubmit { focusedField = .password }
                                        .padding(14)
                                        .background(Color(.systemGray6))
                                        .clipShape(RoundedRectangle(cornerRadius: 12))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 12)
                                                .stroke(focusedField == .username ? Color("BrandBlue") : .clear, lineWidth: 2)
                                        )
                                }

                                // Password field
                                VStack(alignment: .leading, spacing: 6) {
                                    Label("password_label".localized, systemImage: "lock")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(.secondary)
                                    SecureField("password_placeholder".localized, text: $vm.password)
                                        .textContentType(.password)
                                        .focused($focusedField, equals: .password)
                                        .submitLabel(.go)
                                        .onSubmit { vm.loginWithCredentials() }
                                        .padding(14)
                                        .background(Color(.systemGray6))
                                        .clipShape(RoundedRectangle(cornerRadius: 12))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 12)
                                                .stroke(focusedField == .password ? Color("BrandBlue") : .clear, lineWidth: 2)
                                        )
                                }

                                // Login button
                                Button(action: vm.loginWithCredentials) {
                                    Group {
                                        if vm.isLoading {
                                            ProgressView()
                                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                        } else {
                                            Text("sign_in".localized)
                                                .fontWeight(.semibold)
                                        }
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 16)
                                    .background(
                                        LinearGradient(colors: [Color("BrandBlue"), Color("BrandDarkBlue")],
                                                       startPoint: .leading, endPoint: .trailing)
                                    )
                                    .foregroundStyle(.white)
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                                    .shadow(color: Color("BrandBlue").opacity(0.3), radius: 8, y: 4)
                                }
                                .disabled(vm.isLoading)

                                // Back to biometric
                                if vm.biometricAvailable {
                                    Button(String(format: "use_biometric".localized, vm.biometricType)) {
                                        withAnimation(.spring(response: 0.35)) { vm.showUsernameForm = false }
                                    }
                                    .font(.footnote.weight(.medium))
                                    .foregroundStyle(.secondary)
                                }
                            }
                        }

                        Rectangle()
                            .fill(Color(.separator))
                            .frame(height: 0.5)
                            .padding(.horizontal, 20)

                        // Forgot password
                        Button("forgot_password".localized) {
                            // TODO: navigate to password reset
                        }
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color("BrandBlue"))
                    }
                    .padding(28)
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .shadow(color: .black.opacity(0.12), radius: 24, x: 0, y: 12)
                    .padding(.horizontal, 24)

                    Spacer(minLength: 48)
                }
            }
        }
        .onTapGesture { focusedField = nil }
    }
}

#Preview {
    LoginView()
        .environmentObject(LocalizationManager.shared)
}
