import SwiftUI

// MARK: - Family Access View (matches Angular my-family-access.ts)

struct FamilyAccessView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = FamilyAccessViewModel()
    @State private var selectedTab = 0
    @State private var showGrantSheet = false

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.loadAll() }
        } else {
            content
                .task { await vm.loadAll() }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                Text("Granted by Me").tag(0)
                Text("Access I Have").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()

            if vm.isLoading {
                ProgressView("Loading…").padding()
            } else if selectedTab == 0 {
                grantedByMeTab
            } else {
                accessIHaveTab
            }
        }
        .navigationTitle("Family Access")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { showGrantSheet = true } label: {
                    Image(systemName: "plus.circle.fill")
                }
            }
        }
        .sheet(isPresented: $showGrantSheet) {
            GrantProxySheet(vm: vm, isPresented: $showGrantSheet)
        }
        .refreshable { await vm.loadAll() }
    }

    // MARK: Granted by Me
    private var grantedByMeTab: some View {
        Group {
            if vm.grantedByMe.isEmpty {
                ContentUnavailableView("No Proxies Granted", systemImage: "person.2.slash",
                    description: Text("You haven't granted access to anyone. Tap + to add."))
            } else {
                List {
                    ForEach(vm.grantedByMe) { proxy in
                        ProxyCard(proxy: proxy, showRevoke: true) {
                            Task { await vm.revoke(proxyId: proxy.id ?? "") }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: Access I Have
    private var accessIHaveTab: some View {
        Group {
            if vm.accessIHave.isEmpty {
                ContentUnavailableView("No Access Granted to You", systemImage: "person.crop.circle.badge.xmark",
                    description: Text("No one has granted you proxy access."))
            } else {
                List(vm.accessIHave) { proxy in
                    ProxyCard(proxy: proxy, showRevoke: false, revokeAction: {})
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

// MARK: - Proxy Card

struct ProxyCard: View {
    let proxy: ProxyResponse
    let showRevoke: Bool
    let revokeAction: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(proxy.proxyDisplayName ?? proxy.proxyUsername ?? proxy.grantorName ?? "User")
                        .font(.headline)
                    if let relationship = proxy.relationship {
                        Text(relationship.capitalized)
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
                Spacer()
                StatusBadge(
                    text: proxy.status?.capitalized ?? "Active",
                    color: proxy.status?.uppercased() == "ACTIVE" ? "green" : "gray"
                )
            }

            // Permissions
            if !proxy.permissionsList.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(proxy.permissionsList, id: \.self) { perm in
                            Text(perm.replacingOccurrences(of: "_", with: " ").capitalized)
                                .font(.caption2).bold()
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(Color.accentColor.opacity(0.12))
                                .foregroundColor(.accentColor)
                                .cornerRadius(8)
                        }
                    }
                }
            }

            HStack {
                if let created = proxy.createdAt {
                    Text("Since \(ProxyCard.formatDate(created))").font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                if let expires = proxy.expiresAt {
                    Text("Expires \(ProxyCard.formatDate(expires))").font(.caption2).foregroundColor(.orange)
                }
            }
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .trailing) {
            if showRevoke {
                Button(role: .destructive, action: revokeAction) {
                    Label("Revoke", systemImage: "xmark.circle")
                }
            }
        }
    }

    /// Format ISO timestamp to readable date like "Mar 26, 2026"
    static func formatDate(_ isoString: String) -> String {
        let prefix = String(isoString.prefix(10)) // "2026-03-26"
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        if let date = formatter.date(from: prefix) {
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: date)
        }
        return prefix
    }
}

// MARK: - Grant Proxy Sheet

struct GrantProxySheet: View {
    @ObservedObject var vm: FamilyAccessViewModel
    @Binding var isPresented: Bool

    @State private var username = ""
    @State private var relationship = ""
    @State private var notes = ""
    @State private var expiresAt = Date().addingTimeInterval(365 * 24 * 3600)
    @State private var hasExpiry = false

    // Permissions toggles
    @State private var viewRecords = true
    @State private var viewAppointments = true
    @State private var viewMedications = true
    @State private var viewLabResults = false
    @State private var viewBilling = false

    @State private var isSubmitting = false
    @State private var errorMsg: String?

    private let relationships = ["Spouse", "Parent", "Child", "Sibling", "Caregiver", "Other"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Proxy User") {
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Picker("Relationship", selection: $relationship) {
                        Text("Select…").tag("")
                        ForEach(relationships, id: \.self) { Text($0).tag($0) }
                    }
                }

                Section("Permissions") {
                    Toggle("View Records", isOn: $viewRecords)
                    Toggle("View Appointments", isOn: $viewAppointments)
                    Toggle("View Medications", isOn: $viewMedications)
                    Toggle("View Lab Results", isOn: $viewLabResults)
                    Toggle("View Billing", isOn: $viewBilling)
                }

                Section("Expiry") {
                    Toggle("Set Expiry Date", isOn: $hasExpiry)
                    if hasExpiry {
                        DatePicker("Expires On", selection: $expiresAt, displayedComponents: .date)
                    }
                }

                Section("Notes") {
                    TextField("Optional notes…", text: $notes, axis: .vertical)
                        .lineLimit(3)
                }

                if let err = errorMsg {
                    Section {
                        Text(err).foregroundColor(.red).font(.caption)
                    }
                }
            }
            .navigationTitle("Grant Access")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Grant") {
                        Task { await grant() }
                    }
                    .disabled(username.isEmpty || relationship.isEmpty || isSubmitting)
                    .bold()
                }
            }
            .interactiveDismissDisabled(isSubmitting)
        }
    }

    private func grant() async {
        isSubmitting = true
        errorMsg = nil

        var perms: [String] = []
        if viewRecords { perms.append("VIEW_RECORDS") }
        if viewAppointments { perms.append("VIEW_APPOINTMENTS") }
        if viewMedications { perms.append("VIEW_MEDICATIONS") }
        if viewLabResults { perms.append("VIEW_LAB_RESULTS") }
        if viewBilling { perms.append("VIEW_BILLING") }

        let iso = ISO8601DateFormatter()
        let request = ProxyGrantRequest(
            proxyUsername: username,
            relationship: relationship.uppercased(),
            permissions: perms.joined(separator: ","),
            expiresAt: hasExpiry ? iso.string(from: expiresAt) : nil,
            notes: notes.isEmpty ? nil : notes
        )

        do {
            let _: ProxyResponse = try await APIClient.shared.post(APIEndpoints.proxies, body: request)
            await vm.loadAll()
            isPresented = false
        } catch let apiError as APIError {
            switch apiError {
            case .httpError(let code, let msg):
                if code == 404 {
                    errorMsg = "User '\(username)' not found. Please check the username and try again."
                } else if code == 400 {
                    errorMsg = msg ?? "Invalid request. Please check the form."
                } else {
                    errorMsg = msg ?? "Server error (\(code)). Please try again."
                }
            default:
                errorMsg = "Failed to grant access: \(apiError.localizedDescription)"
            }
        } catch {
            errorMsg = "Failed to grant access: \(error.localizedDescription)"
        }
        isSubmitting = false
    }
}

// MARK: - View Model

@MainActor
final class FamilyAccessViewModel: ObservableObject {
    @Published var grantedByMe: [ProxyResponse] = []
    @Published var accessIHave: [ProxyResponse] = []
    @Published var isLoading = false

    func loadAll() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in
                self.grantedByMe = (try? await APIClient.shared.get(APIEndpoints.proxies)) ?? []
            }
            group.addTask { @MainActor in
                self.accessIHave = (try? await APIClient.shared.get(APIEndpoints.proxyAccess)) ?? []
            }
        }
        isLoading = false
    }

    func revoke(proxyId: String) async {
        let _: String? = try? await APIClient.shared.delete(APIEndpoints.revokeProxy(id: proxyId))
        await loadAll()
    }
}
