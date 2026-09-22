import SwiftUI

// MARK: - Sharing & privacy (matches the web's my-sharing)

/// The one thing a patient controls and everything they can see. Their record
/// follows them on the treatment relationship, so there is nothing to grant:
/// the opt-out closes the door to other hospitals while their own hospital
/// keeps its access and an emergency access stays possible. Beneath it, the
/// accounting of disclosures, with emergency and external counts across the
/// whole history rather than the loaded page.
struct SharingPrivacyView: View {
    @StateObject private var vm = SharingPrivacyViewModel()

    var body: some View {
        List {
            optOutSection
            disclosuresSection
        }
        .listStyle(.insetGrouped)
        .navigationTitle("disclosures_page_title".localized)
        .task { await vm.loadAll() }
        .refreshable { await vm.loadAll() }
        // A swipe-down after a failed save must not leave the error under the card.
        .sheet(isPresented: $vm.showOptOutForm, onDismiss: { vm.optOutFormDismissed() }) {
            OptOutReasonSheet(vm: vm)
        }
    }

    // ── Opt-out ──────────────────────────────────────────────────────────────

    private var optOutSection: some View {
        Section {
            Text("sharing_optout_desc".localized)
                .font(.subheadline)
                .foregroundColor(.secondary)
            if vm.optOutLoading {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("sharing_optout_loading".localized).foregroundColor(.secondary)
                }
            } else if vm.optOutFailed {
                // A load failure is shown as such, never as "sharing is on".
                VStack(alignment: .leading, spacing: 8) {
                    Label("sharing_optout_load_failed".localized, systemImage: "exclamationmark.triangle")
                        .foregroundColor(.red)
                    Button("retry".localized) { Task { await vm.loadOptOut() } }
                }
            } else {
                HStack(spacing: 8) {
                    Image(systemName: vm.optedOut ? "lock.fill" : "lock.open")
                        .foregroundColor(vm.optedOut ? .orange : .green)
                    Text(vm.optOutStatusText)
                }
                if let notice = vm.optOutNotice {
                    Text(notice).font(.caption).foregroundColor(.green)
                }
                if let error = vm.optOutError, !vm.showOptOutForm {
                    Text(error).font(.caption).foregroundColor(.red)
                }
                Button {
                    if vm.optedOut { vm.revokeOptOut() } else { vm.openOptOutForm() }
                } label: {
                    if vm.optOutSaving {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("sharing_optout_saving".localized)
                        }
                    } else {
                        Text(vm.optedOut ? "sharing_optout_disable".localized : "sharing_optout_enable".localized)
                    }
                }
                .disabled(vm.optOutSaving)
            }
        } header: {
            Text("sharing_optout_title".localized)
        } footer: {
            if !vm.optOutLoading, !vm.optOutFailed {
                Text("sharing_optout_own_hospital_note".localized)
            }
        }
    }

    // ── Accesses and disclosures ─────────────────────────────────────────────

    @ViewBuilder
    private var disclosuresSection: some View {
        Section {
            // Shown above every state, including the empty one: routine chart
            // reads emit no audit event, and the list must not read as "nobody looked".
            Label("disclosures_scope_note".localized, systemImage: "info.circle")
                .font(.footnote)
                .foregroundColor(.secondary)
            if vm.logLoading, vm.entries.isEmpty {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("disclosures_loading".localized).foregroundColor(.secondary)
                }
            } else if vm.logFailed, vm.entries.isEmpty {
                // Kept apart from "no rows": an empty log tells a patient nobody
                // has looked, which is not a safe thing to say about a failed request.
                VStack(alignment: .leading, spacing: 8) {
                    Label("disclosures_failed_title".localized, systemImage: "exclamationmark.triangle")
                        .font(.headline)
                        .foregroundColor(.red)
                    Text("disclosures_failed_desc".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Button("retry".localized) { Task { await vm.loadDisclosures() } }
                }
            } else if vm.entries.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Label("disclosures_empty_title".localized, systemImage: "shield")
                        .font(.headline)
                    Text("disclosures_empty_desc".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            } else {
                if vm.emergencyCount > 0 {
                    summaryRow(String(format: "disclosures_summary_emergency".localized, vm.emergencyCount),
                               systemImage: "cross.case.fill", tint: .red)
                }
                if vm.externalCount > 0 {
                    summaryRow(String(format: "disclosures_summary_external".localized, vm.externalCount),
                               systemImage: "arrowshape.turn.up.right.fill", tint: .orange)
                }
                if vm.logFailed {
                    // A refresh that failed keeps the rows already shown and says so.
                    HStack(spacing: 8) {
                        Text("disclosures_failed_title".localized).font(.caption).foregroundColor(.red)
                        Spacer()
                        Button("retry".localized) { Task { await vm.loadDisclosures() } }
                            .font(.caption)
                    }
                }
            }
        } header: {
            Text("disclosures_title".localized)
        }

        if !vm.entries.isEmpty {
            Section {
                ForEach(vm.entries) { entry in
                    DisclosureRow(entry: entry)
                }
                if vm.hasMorePages {
                    Button {
                        Task { await vm.loadMore() }
                    } label: {
                        if vm.loadingMore {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("loading".localized)
                            }
                        } else {
                            Text("disclosures_load_more".localized)
                        }
                    }
                    .disabled(vm.loadingMore)
                    if vm.loadMoreFailed {
                        Text("disclosures_load_more_failed".localized).font(.caption).foregroundColor(.red)
                    }
                }
            }
        }
    }

    private func summaryRow(_ text: String, systemImage: String, tint: Color) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage).foregroundColor(tint).frame(width: 24)
            Text(text).font(.subheadline).bold()
        }
    }
}

// MARK: - One access or disclosure

private struct DisclosureRow: View {
    let entry: DisclosureEntryDTO

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: iconName)
                .foregroundColor(tint)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.actor ?? "—").font(.headline)
                Text(subtitle).font(.subheadline).foregroundColor(.secondary)
                if let when = entry.timestamp {
                    Text(DisclosureFormat.dateTime(when)).font(.caption).foregroundColor(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }

    /// Category, then the actor's role and hospital when known — the web's "pli-sub" line.
    private var subtitle: String {
        var parts = [DisclosureFormat.categoryLabel(entry.category)]
        if let role = DisclosureFormat.roleLabel(entry.actorRole) { parts.append(role) }
        if let hospital = entry.hospitalName, !hospital.isEmpty { parts.append(hospital) }
        return parts.joined(separator: " · ")
    }

    private var iconName: String {
        switch entry.category ?? "" {
        case "EMERGENCY_ACCESS": "cross.case.fill"
        case "SHARED_WITH_PROVIDER": "arrowshape.turn.up.right"
        case "INSURANCE": "building.columns"
        case "COPY_RELEASED": "square.and.arrow.down"
        case "IDENTITY_CHANGE": "arrow.triangle.merge"
        default: "eye"
        }
    }

    private var tint: Color {
        if entry.category == "EMERGENCY_ACCESS" { return .red }
        if entry.externalDisclosure == true { return .orange }
        return .accentColor
    }
}

// MARK: - The reason sheet (the web's opt-out form)

/// Confirming the opt-out with an optional reason. The view model owns the
/// request, so closing the sheet cannot abandon a save the server may already
/// have applied; dismissal is refused while one is out.
private struct OptOutReasonSheet: View {
    @ObservedObject var vm: SharingPrivacyViewModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("sharing_optout_desc".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Text("sharing_optout_own_hospital_note".localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                Section {
                    TextField("sharing_optout_reason_placeholder".localized, text: $vm.optOutReason, axis: .vertical)
                        .lineLimit(3 ... 6)
                        .disabled(vm.optOutSaving)
                        .onChange(of: vm.optOutReason) { _, value in
                            // The server refuses more than 1000 characters (@Size); stop at the same line.
                            if value.count > SharingPrivacyViewModel.reasonMaxLength {
                                vm.optOutReason = String(value.prefix(SharingPrivacyViewModel.reasonMaxLength))
                            }
                        }
                } header: {
                    Text("sharing_optout_reason_label".localized)
                } footer: {
                    Text(String(format: "sharing_optout_reason_count".localized,
                                vm.optOutReason.count, SharingPrivacyViewModel.reasonMaxLength))
                }
                Section {
                    if let error = vm.optOutError {
                        Text(error).font(.caption).foregroundColor(.red)
                    }
                    Button {
                        vm.confirmOptOut()
                    } label: {
                        if vm.optOutSaving {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("sharing_optout_saving".localized)
                            }
                            .frame(maxWidth: .infinity)
                        } else {
                            Text("sharing_optout_confirm".localized).bold().frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(vm.optOutSaving)
                }
            }
            .navigationTitle("sharing_optout_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { vm.cancelOptOutForm() }
                        .disabled(vm.optOutSaving)
                }
            }
            .interactiveDismissDisabled(vm.optOutSaving)
        }
    }
}

// MARK: - Labels and dates

enum DisclosureFormat {
    private static let knownCategories: Set<String> = [
        "EMERGENCY_ACCESS", "TREATMENT_ACCESS", "SHARED_WITH_PROVIDER",
        "INSURANCE", "COPY_RELEASED", "IDENTITY_CHANGE",
    ]

    /// The literal the audit service stamps when it cannot resolve a role; a sentence, not a token.
    private static let unknownRole = "Unknown Role"

    /// Patient-facing wording for a category; anything unknown or absent gets
    /// a neutral label rather than the raw enum name.
    static func categoryLabel(_ raw: String?) -> String {
        guard let raw, knownCategories.contains(raw) else {
            return "disclosures_category_unknown".localized
        }
        return ("disclosures_category_" + raw.lowercased()).localized
    }

    /// The web's `bareRole` + role enum label: strips `ROLE_`, hides the
    /// "Unknown Role" sentence, and falls back to a humanised token when no
    /// translation exists so a new role never renders as a key.
    static func roleLabel(_ raw: String?) -> String? {
        guard var token = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty, token != unknownRole else { return nil }
        if token.hasPrefix("ROLE_") { token = String(token.dropFirst("ROLE_".count)) }
        let key = "disclosures_role_" + token.lowercased()
        let translated = key.localized
        if translated != key { return translated }
        return token.replacingOccurrences(of: "_", with: " ").capitalized
    }

    private static func parse(_ iso: String) -> Date? {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .iso8601)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        if let date = parser.date(from: String(iso.prefix(19))) { return date }
        parser.dateFormat = "yyyy-MM-dd"
        return parser.date(from: String(iso.prefix(10)))
    }

    /// "2026-09-21T10:15:00" in the device's medium date / short time; the raw text when it does not parse.
    static func dateTime(_ iso: String) -> String {
        guard let date = parse(iso) else { return iso }
        let out = DateFormatter()
        out.dateStyle = .medium
        out.timeStyle = .short
        return out.string(from: date)
    }

    /// The date alone, for "off since …".
    static func date(_ iso: String) -> String {
        guard let date = parse(iso) else { return iso }
        let out = DateFormatter()
        out.dateStyle = .medium
        out.timeStyle = .none
        return out.string(from: date)
    }
}

// MARK: - View model

@MainActor
final class SharingPrivacyViewModel: ObservableObject {
    /// What the web asks for on its one page.
    static let pageSize = 50
    /// `OptOutRequestDTO.reason` is `@Size(max = 1000)`.
    static let reasonMaxLength = 1000

    // ── Opt-out ──
    @Published var optOut: RecordSharingOptOutDTO?
    @Published var optOutLoading = true
    /// A load failure is shown as such, never as "sharing is on".
    @Published var optOutFailed = false
    @Published var optOutSaving = false
    @Published var showOptOutForm = false
    @Published var optOutReason = ""
    @Published var optOutNotice: String?
    @Published var optOutError: String?
    /// The opt-out is patient-scoped on the API; resolved from the profile like the web does.
    private var patientId = ""

    // ── Accounting of disclosures ──
    @Published var entries: [DisclosureEntryDTO] = []
    @Published var accounting: DisclosureAccountingDTO?
    @Published var logLoading = true
    /// Distinguished from "no rows": a failed request must never read as "nobody looked".
    @Published var logFailed = false
    @Published var loadingMore = false
    @Published var loadMoreFailed = false

    /// Owned here, not by the sheet, so leaving the screen cannot abandon a save.
    private var saveTask: Task<Void, Never>?
    /// Bumped by every page-0 load; a page-0 or load-more answer from an older
    /// generation is dropped, so a refresh and a "Show more" cannot split the
    /// rows and the paging cursor between them.
    private var disclosuresGeneration = 0

    var optedOut: Bool { optOut?.inForce == true }

    var optOutStatusText: String {
        if optedOut {
            let since = optOut?.optedOutAt.map(DisclosureFormat.date) ?? "—"
            return String(format: "sharing_optout_status_on".localized, since)
        }
        return "sharing_optout_status_off".localized
    }

    /// Emergency overrides across the whole history, or 0 if unknown.
    var emergencyCount: Int { accounting?.countsByCategory?["EMERGENCY_ACCESS"] ?? 0 }

    /// Times the record went outside the treating team, across the whole history.
    var externalCount: Int { accounting?.externalDisclosures ?? 0 }

    var hasMorePages: Bool {
        guard let accounting else { return false }
        return (accounting.page ?? 0) + 1 < (accounting.totalPages ?? 0)
    }

    func loadAll() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in await self.loadDisclosures() }
            group.addTask { @MainActor in await self.loadOptOut() }
        }
    }

    // ── Disclosures ──

    func loadDisclosures() async {
        disclosuresGeneration += 1
        let generation = disclosuresGeneration
        logLoading = true
        logFailed = false
        loadMoreFailed = false
        do {
            let first = try await fetchPage(0)
            guard generation == disclosuresGeneration else { return }
            accounting = first
            entries = first.entries ?? []
        } catch {
            guard generation == disclosuresGeneration else { return }
            logFailed = true
        }
        logLoading = false
    }

    func loadMore() async {
        guard !loadingMore, !logLoading, hasMorePages, let current = accounting else { return }
        let generation = disclosuresGeneration
        loadingMore = true
        loadMoreFailed = false
        do {
            let next = try await fetchPage((current.page ?? 0) + 1)
            guard generation == disclosuresGeneration else { loadingMore = false; return }
            accounting = next
            let seen = Set(entries.map { $0.id })
            entries += (next.entries ?? []).filter { !seen.contains($0.id) }
        } catch {
            guard generation == disclosuresGeneration else { loadingMore = false; return }
            loadMoreFailed = true
        }
        loadingMore = false
    }

    private func fetchPage(_ pageIndex: Int) async throws -> DisclosureAccountingDTO {
        try await APIClient.shared.get(
            APIEndpoints.disclosures,
            queryItems: [
                URLQueryItem(name: "page", value: String(pageIndex)),
                URLQueryItem(name: "size", value: String(Self.pageSize)),
            ]
        )
    }

    // ── Opt-out ──

    /// Skipped while a save is out: a refresh answered after the POST/DELETE
    /// would hand back the pre-save state and overwrite what the save returned.
    func loadOptOut() async {
        guard !optOutSaving else { return }
        optOutLoading = true
        optOutFailed = false
        do {
            if patientId.isEmpty {
                let profile: PatientProfileDTO = try await APIClient.shared.get(APIEndpoints.profile)
                patientId = profile.id ?? ""
            }
            guard !patientId.isEmpty else {
                optOutFailed = true
                optOutLoading = false
                return
            }
            let state: RecordSharingOptOutDTO = try await APIClient.shared.get(
                APIEndpoints.recordSharingOptOut(patientId: patientId))
            optOut = state
        } catch {
            optOutFailed = true
        }
        optOutLoading = false
    }

    func openOptOutForm() {
        optOutReason = ""
        optOutError = nil
        optOutNotice = nil
        showOptOutForm = true
    }

    func cancelOptOutForm() {
        guard !optOutSaving else { return }
        showOptOutForm = false
    }

    /// Runs on every dismissal (Cancel, swipe-down, a successful save): an
    /// error the patient walked away from is not shown under the card.
    func optOutFormDismissed() {
        optOutError = nil
    }

    /// `POST .../record-sharing/opt-out` with the trimmed reason, or none.
    func confirmOptOut() {
        guard !patientId.isEmpty, !optOutSaving else { return }
        let reason = optOutReason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard reason.count <= Self.reasonMaxLength else {
            optOutError = String(format: "sharing_optout_reason_too_long".localized, Self.reasonMaxLength)
            return
        }
        optOutSaving = true
        optOutError = nil
        optOutNotice = nil
        saveTask = Task {
            do {
                let state: RecordSharingOptOutDTO = try await APIClient.shared.post(
                    APIEndpoints.recordSharingOptOut(patientId: patientId),
                    body: OptOutRequest(reason: reason.isEmpty ? nil : reason))
                optOut = state
                showOptOutForm = false
                optOutNotice = "sharing_optout_saved_on".localized
            } catch {
                if Self.isConflict(error) {
                    await settleConflict(noticeKey: "sharing_optout_already_off")
                } else {
                    optOutError = Self.failureText(error)
                }
            }
            optOutSaving = false
        }
    }

    /// `DELETE .../record-sharing/opt-out`; the row stays for the disclosure report.
    func revokeOptOut() {
        guard !patientId.isEmpty, !optOutSaving else { return }
        optOutSaving = true
        optOutError = nil
        optOutNotice = nil
        saveTask = Task {
            do {
                let state: RecordSharingOptOutDTO = try await APIClient.shared.delete(
                    APIEndpoints.recordSharingOptOut(patientId: patientId))
                optOut = state
                optOutNotice = "sharing_optout_saved_off".localized
            } catch {
                if Self.isConflict(error) {
                    await settleConflict(noticeKey: "sharing_optout_already_on")
                } else {
                    optOutError = Self.failureText(error)
                }
            }
            optOutSaving = false
        }
    }

    /// A 409 means the setting already is what we asked for (a second opt-out,
    /// a revoke of nothing): the card we showed was stale, not the request wrong.
    private static func isConflict(_ error: Error) -> Bool {
        if case let APIError.httpError(code, _) = error { return code == 409 }
        return false
    }

    /// The server's state, not ours, is the truth then: close the form, say so
    /// in our own words, and reload. The save is over before the reload so the
    /// reload's "not while saving" guard does not swallow it.
    private func settleConflict(noticeKey: String) async {
        optOutSaving = false
        showOptOutForm = false
        optOutError = nil
        optOutNotice = noticeKey.localized
        await loadOptOut()
    }

    /// The web's generic wording, plus the server's own message when it sent one.
    private static func failureText(_ error: Error) -> String {
        let base = "sharing_optout_failed".localized
        if case let APIError.httpError(_, message) = error, let message, !message.isEmpty {
            return base + " " + message
        }
        return base
    }
}
