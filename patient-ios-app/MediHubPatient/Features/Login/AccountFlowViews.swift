import SwiftUI

// The account flows the web portal has and the app shipped without: a
// forgotten password, activating an account, the MFA challenge and changing
// the password. Each mirrors the portal's page (login.ts, reset-password,
// verify-email, mfa-challenge, profile) and its wording.

extension MfaChallenge: Identifiable {
    var id: String { mfaToken }
}

/// The portal's sign-in page, for what the app hands to the browser.
private var portalLoginURL: URL? {
    URL(string: AppEnvironment.portalOrigin + "/login")
}

// MARK: - Forgot password

struct ForgotPasswordView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var isSending = false
    @State private var requestSent = false

    @State private var code = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var isResetting = false
    @State private var resetSubmitted = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("forgot_password_instruction".localized)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    TextField("email_address".localized, text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button {
                        Task { await sendRequest() }
                    } label: {
                        if isSending { ProgressView() } else { Text("send_reset_link".localized) }
                    }
                    .disabled(isSending || !AccountLinkParser.looksLikeEmail(email))
                    if requestSent {
                        // The same answer whether or not the address has an
                        // account: the endpoint never says, and neither do we.
                        Text("reset_link_sent".localized)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    TextField("reset_code_placeholder".localized, text: $code, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("new_password".localized, text: $newPassword)
                        .textContentType(.newPassword)
                    SecureField("confirm_password".localized, text: $confirmPassword)
                        .textContentType(.newPassword)
                    Button {
                        Task { await confirmReset() }
                    } label: {
                        if isResetting { ProgressView() } else { Text("reset_password_submit".localized) }
                    }
                    .disabled(isResetting || code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                              || newPassword.isEmpty)
                    if resetSubmitted {
                        Text("reset_submitted".localized)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("reset_have_code_title".localized)
                } footer: {
                    Text("reset_have_code_desc".localized)
                }

                if let portalLoginURL {
                    Section {
                        Link("open_web_portal".localized, destination: portalLoginURL)
                    } footer: {
                        Text("reset_web_fallback".localized)
                    }
                }

                if let errorText {
                    Section { Text(errorText).foregroundStyle(.red) }
                }
            }
            .navigationTitle("reset_password_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("close".localized) { dismiss() }
                }
            }
        }
    }

    private func sendRequest() async {
        isSending = true
        errorText = nil
        defer { isSending = false }
        // 204 whatever the address; a failure is answered the same way, as
        // the portal does, so the dialog cannot be used to probe accounts.
        try? await APIClient.shared.sendNoContent(
            .POST,
            path: APIEndpoints.requestPasswordReset,
            body: PasswordResetRequest(email: email.trimmingCharacters(in: .whitespacesAndNewlines)),
            auth: .none
        )
        requestSent = true
    }

    private func confirmReset() async {
        errorText = nil
        resetSubmitted = false
        if let problem = PasswordRules.problem(newPassword: newPassword, confirmation: confirmPassword) {
            errorText = problem.localized
            return
        }
        isResetting = true
        defer { isResetting = false }
        do {
            // The e-mailed link or the code alone: both carry the token.
            // Also forgets the Face ID password, which may now be stale.
            try await AccountService.confirmPasswordReset(tokenText: code, newPassword: newPassword)
            // The server answers 204 for a bad code too, so this cannot claim
            // success; it says what to do next.
            resetSubmitted = true
            newPassword = ""
            confirmPassword = ""
        } catch {
            errorText = error.localizedDescription
        }
    }
}

// MARK: - Account activation

struct AccountActivationView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var isSending = false
    @State private var linkSent = false

    @State private var link = ""
    @State private var isActivating = false
    @State private var activated: Bool?
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("activation_instruction".localized)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    TextField("email_address".localized, text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button {
                        Task { await resend() }
                    } label: {
                        if isSending { ProgressView() } else { Text("send_activation_link".localized) }
                    }
                    .disabled(isSending || !AccountLinkParser.looksLikeEmail(email))
                    if linkSent {
                        Text("activation_link_sent".localized)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    TextField("activation_link_placeholder".localized, text: $link, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button {
                        Task { await activate() }
                    } label: {
                        if isActivating { ProgressView() } else { Text("activate_account".localized) }
                    }
                    .disabled(isActivating || link.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if activated == true {
                        Label("activation_success".localized, systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    } else if activated == false {
                        Text("activation_failed".localized)
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("activation_have_link_title".localized)
                } footer: {
                    Text("activation_have_link_desc".localized)
                }

                if let errorText {
                    Section { Text(errorText).foregroundStyle(.red) }
                }
            }
            .navigationTitle("activation_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("close".localized) { dismiss() }
                }
            }
        }
    }

    private func resend() async {
        isSending = true
        errorText = nil
        linkSent = false
        defer { isSending = false }
        do {
            // The server's answer is already neutral (same text whether or
            // not the address exists), so a failure here is a real one — a
            // network error or a rejected address — and is shown.
            try await APIClient.shared.sendNoContent(
                .POST,
                path: APIEndpoints.resendVerification,
                queryItems: [URLQueryItem(name: "email", value: email.trimmingCharacters(in: .whitespacesAndNewlines))],
                auth: .none
            )
            linkSent = true
        } catch {
            errorText = error.localizedDescription
        }
    }

    private func activate() async {
        errorText = nil
        activated = nil
        // The link carries both; a bare code needs the address typed above.
        let address = AccountLinkParser.email(from: link) ?? email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard AccountLinkParser.looksLikeEmail(address) else {
            errorText = "activation_email_needed".localized
            return
        }
        isActivating = true
        defer { isActivating = false }
        do {
            try await APIClient.shared.sendNoContent(
                .GET,
                path: APIEndpoints.verifyEmail,
                queryItems: [URLQueryItem(name: "email", value: address),
                             URLQueryItem(name: "token", value: AccountLinkParser.token(from: link))],
                auth: .none
            )
            activated = true
        } catch APIError.httpError(let status, _) where (400 ..< 500).contains(status) {
            activated = false
        } catch {
            errorText = error.localizedDescription
        }
    }
}

// MARK: - MFA challenge

struct MfaChallengeView: View {
    @ObservedObject var vm: LoginViewModel
    let challenge: MfaChallenge

    var body: some View {
        NavigationStack {
            Form {
                if challenge.enrolled {
                    Section {
                        Text("mfa_code_hint".localized)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        if let username = challenge.username, !username.isEmpty {
                            Text(String(format: "mfa_signed_in_as".localized, username))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                        TextField("mfa_code_placeholder".localized, text: $vm.mfaCode)
                            .textContentType(.oneTimeCode)
                            .keyboardType(.asciiCapable)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.go)
                            .onSubmit { vm.verifyMfa() }
                        Button {
                            vm.verifyMfa()
                        } label: {
                            if vm.isVerifyingMfa { ProgressView() } else { Text("mfa_verify".localized) }
                        }
                        .disabled(vm.isVerifyingMfa)
                    }
                    if let error = vm.mfaError {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                } else {
                    // Enrolment (QR code, secret, backup codes) is a portal
                    // flow; the app only answers a challenge.
                    Section {
                        Text("mfa_not_enrolled_app".localized)
                        if let portalLoginURL {
                            Link("open_web_portal".localized, destination: portalLoginURL)
                        }
                    }
                }
            }
            .navigationTitle("mfa_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { vm.cancelMfa() }
                }
            }
        }
        .interactiveDismissDisabled(vm.isVerifyingMfa)
    }
}

// MARK: - Change password

/// Password sessions only: an SSO patient's password belongs to Keycloak.
struct ChangePasswordView: View {
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    @State private var isSaving = false
    @State private var errorText: String?
    @State private var changed = false

    var body: some View {
        Form {
            Section {
                SecureField("current_password".localized, text: $currentPassword)
                    .textContentType(.password)
                SecureField("new_password".localized, text: $newPassword)
                    .textContentType(.newPassword)
                SecureField("confirm_password".localized, text: $confirmPassword)
                    .textContentType(.newPassword)
            } footer: {
                Text("password_min_length".localized)
            }

            Section {
                Button {
                    Task { await save() }
                } label: {
                    if isSaving { ProgressView() } else { Text("change_password".localized) }
                }
                .disabled(isSaving || currentPassword.isEmpty || newPassword.isEmpty)
            }

            if changed {
                Section {
                    Label("password_changed".localized, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                }
            }
            if let errorText {
                Section { Text(errorText).foregroundStyle(.red) }
            }
        }
        .navigationTitle("change_password".localized)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func save() async {
        errorText = nil
        changed = false
        if let problem = PasswordRules.problem(newPassword: newPassword,
                                               confirmation: confirmPassword,
                                               current: currentPassword) {
            errorText = problem.localized
            return
        }
        isSaving = true
        defer { isSaving = false }

        // A wrong current password is a 401, and a 401 on the refreshing
        // path signs the patient out. So: refresh the token first with a
        // harmless authenticated call, then send this one with that token
        // explicitly and no refresh.
        _ = try? await AuthManager.shared.resolveSessionIdentity()
        guard let bearer = KeychainHelper.shared.accessToken else {
            errorText = "error_session_expired".localized
            return
        }
        do {
            // Also updates the password Face ID signs in with.
            try await AccountService.changePassword(current: currentPassword, new: newPassword, bearer: bearer)
            changed = true
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
        } catch APIError.httpError(401, _) {
            errorText = "current_password_incorrect".localized
        } catch {
            errorText = error.localizedDescription
        }
    }
}
