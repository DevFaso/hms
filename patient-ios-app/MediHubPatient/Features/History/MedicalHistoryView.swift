import SwiftUI

// MARK: - My Medical History (matches the web's my-medical-history)

/// What the care team has recorded about a patient's past: diagnoses,
/// operations, relatives' conditions and social habits. All four sections are
/// read-only on every surface; the only thing a patient writes is a personal
/// note per section, which stays on this device (the web keeps it in the
/// browser) and is never sent to the care team.
///
/// Unlike the web, which loads the four requests as one and shows every
/// section empty when any of them fails, each section here has its own
/// loading, error-with-retry and empty state.
struct MedicalHistoryView: View {
    @StateObject private var vm = MedicalHistoryViewModel()

    var body: some View {
        List {
            medicalSection
            surgicalSection
            familySection
            socialSection
        }
        .listStyle(.insetGrouped)
        .navigationTitle("history_title".localized)
        .task { await vm.loadIfNeeded() }
        .refreshable { await vm.load() }
        .sheet(item: $vm.editingSection) { section in
            HistoryNoteSheet(vm: vm, section: section)
        }
    }

    // ── Medical (diagnoses) ──────────────────────────────────────────────────

    private var medicalSection: some View {
        Section {
            switch vm.medicalState {
            case .loading: loadingRow
            case .failed: failedRow(.medical)
            case .loaded:
                if vm.medical.isEmpty {
                    emptyRow("history_no_medical")
                } else {
                    ForEach(vm.medical) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.description ?? item.icdCode ?? "—").font(.headline)
                            if let when = item.diagnosedAt {
                                Text("history_date".localized + ": " + Self.formatDate(when))
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            notesRows(.medical)
        } header: {
            sectionHeader(.medical)
        }
    }

    // ── Surgical ─────────────────────────────────────────────────────────────

    private var surgicalSection: some View {
        Section {
            switch vm.surgicalState {
            case .loading: loadingRow
            case .failed: failedRow(.surgical)
            case .loaded:
                if vm.surgical.isEmpty {
                    emptyRow("history_no_surgical")
                } else {
                    ForEach(vm.surgical) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.procedureDisplay ?? item.procedureCode ?? "—").font(.headline)
                            if let when = item.procedureDate {
                                Text("history_date".localized + ": " + Self.formatDate(when))
                                    .font(.caption).foregroundColor(.secondary)
                            }
                            Text("history_outcome".localized + ": " + (item.outcome ?? "-"))
                                .font(.caption).foregroundColor(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            notesRows(.surgical)
        } header: {
            sectionHeader(.surgical)
        }
    }

    // ── Family ───────────────────────────────────────────────────────────────

    private var familySection: some View {
        Section {
            switch vm.familyState {
            case .loading: loadingRow
            case .failed: failedRow(.family)
            case .loaded:
                if vm.family.isEmpty {
                    emptyRow("history_no_family")
                } else {
                    ForEach(vm.family) { item in
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "person.fill")
                                .foregroundColor(.green)
                                .frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.relativeTitle(item)).font(.headline)
                                Text(item.conditionDisplay ?? item.conditionCode ?? "-")
                                    .font(.subheadline)
                                if let age = item.ageAtOnset {
                                    Text("history_age_at_onset".localized + ": \(age)")
                                        .font(.caption).foregroundColor(.secondary)
                                }
                            }
                            Spacer()
                            if let severity = item.severity, !severity.isEmpty {
                                Text(severity)
                                    .font(.caption2.weight(.semibold))
                                    .padding(.horizontal, 8).padding(.vertical, 3)
                                    .background(Color.orange.opacity(0.15))
                                    .foregroundColor(.orange)
                                    .clipShape(Capsule())
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            notesRows(.family)
        } header: {
            sectionHeader(.family)
        }
    }

    // ── Social ───────────────────────────────────────────────────────────────

    private var socialSection: some View {
        Section {
            switch vm.socialState {
            case .loading: loadingRow
            case .failed: failedRow(.social)
            case .loaded:
                if let social = vm.social {
                    socialCard("history_smoking_tobacco") {
                        socialField("history_tobacco_use", Self.tobaccoStatus(social))
                        if let type = social.tobaccoType, !type.isEmpty {
                            socialField("history_tobacco_types", type)
                        }
                        if let quit = social.tobaccoQuitDate, !quit.isEmpty {
                            socialField("history_quit_date", Self.formatDate(quit))
                        }
                    }
                    socialCard("history_smokeless_tobacco") {
                        socialField("history_smokeless_use", Self.smokelessStatus(social))
                    }
                    socialCard("history_alcohol") {
                        socialField("history_alcohol_use", Self.alcoholStatus(social))
                        if let drinks = social.alcoholDrinksPerWeek, drinks != 0 {
                            socialField("history_drinks_per_week", "\(drinks)")
                        }
                    }
                } else {
                    emptyRow("history_no_social")
                }
            }
            notesRows(.social)
        } header: {
            sectionHeader(.social)
        }
    }

    // ── Shared rows ──────────────────────────────────────────────────────────

    private func sectionHeader(_ section: HistorySection) -> some View {
        Label(section.titleKey.localized, systemImage: section.icon)
            .foregroundColor(section.tint)
    }

    private var loadingRow: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("loading".localized).foregroundColor(.secondary)
        }
    }

    /// Kept apart from "nothing on record": an empty list must never stand in for a failed request.
    private func failedRow(_ section: HistorySection) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle").foregroundColor(.orange)
            Text("history_section_load_failed".localized).font(.subheadline)
            Spacer()
            Button("retry".localized) { Task { await vm.load(section) } }
                .buttonStyle(.bordered)
        }
    }

    private func emptyRow(_ key: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "clock").foregroundColor(.secondary)
            Text(key.localized).font(.subheadline).foregroundColor(.secondary)
        }
    }

    /// The web's "personal notes" block: the note if there is one, the
    /// device-only disclaimer, and Add or Edit.
    private func notesRows(_ section: HistorySection) -> some View {
        let note = vm.notes[section] ?? ""
        return VStack(alignment: .leading, spacing: 6) {
            Text(section.notesKey.localized).font(.subheadline.weight(.semibold))
            if !note.isEmpty {
                Text(note).font(.subheadline)
            }
            Text("history_notes_disclaimer".localized).font(.caption).foregroundColor(.secondary)
            Button {
                vm.beginEditing(section)
            } label: {
                Label(note.isEmpty ? "history_notes_add".localized : "edit".localized,
                      systemImage: note.isEmpty ? "plus" : "pencil")
                    .font(.subheadline)
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 4)
    }

    private func socialCard<Content: View>(_ titleKey: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(titleKey.localized).font(.subheadline.weight(.semibold))
            content()
        }
        .padding(.vertical, 2)
    }

    private func socialField(_ labelKey: String, _ value: String) -> some View {
        HStack {
            Text(labelKey.localized).font(.caption).foregroundColor(.secondary)
            Spacer()
            Text(value).font(.subheadline)
        }
    }

    // ── Presentation rules, the web's ────────────────────────────────────────

    static func relativeTitle(_ item: HistoryFamilyEntry) -> String {
        let relationship = item.relationship ?? "—"
        if let name = item.relativeName, !name.isEmpty { return relationship + " — " + name }
        return relationship
    }

    /// Quit date → former; tobaccoUse → current; otherwise never.
    static func tobaccoStatus(_ social: HistorySocial) -> String {
        if let quit = social.tobaccoQuitDate, !quit.isEmpty { return "history_tobacco_former".localized }
        if social.tobaccoUse == true { return "history_tobacco_current".localized }
        return "history_tobacco_never".localized
    }

    /// tobaccoUse === false → never; otherwise the recorded type or a dash.
    static func smokelessStatus(_ social: HistorySocial) -> String {
        if social.tobaccoUse == false { return "history_tobacco_never".localized }
        if let type = social.tobaccoType, !type.isEmpty { return type }
        return "-"
    }

    /// alcoholUse → the frequency, or plain "yes"; otherwise a dash.
    static func alcoholStatus(_ social: HistorySocial) -> String {
        guard social.alcoholUse == true else { return "-" }
        if let frequency = social.alcoholFrequency, !frequency.isEmpty { return frequency }
        return "yes".localized
    }

    /// "2026-09-21T10:15:00+00:00" or "2026-09-21" as a short date in the
    /// device's format; the raw text when neither parses.
    static func formatDate(_ raw: String) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        if let date = iso.date(from: raw) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        let plain = DateFormatter()
        plain.calendar = Calendar(identifier: .iso8601)
        plain.locale = Locale(identifier: "en_US_POSIX")
        plain.dateFormat = "yyyy-MM-dd"
        if let date = plain.date(from: String(raw.prefix(10))) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        return raw
    }
}

/// One note, edited in a sheet. Nothing leaves the device, so there is no
/// request to protect; Save writes the Keychain and closes.
struct HistoryNoteSheet: View {
    @ObservedObject var vm: MedicalHistoryViewModel
    let section: HistorySection
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $draft)
                        .frame(minHeight: 140)
                } footer: {
                    Text("history_notes_disclaimer".localized)
                }
            }
            .navigationTitle(section.notesKey.localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("save".localized) {
                        vm.saveNote(draft, for: section)
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
            .onAppear { draft = vm.notes[section] ?? "" }
        }
    }
}

/// The four sections, in the web's order, with the web's colours.
enum HistorySection: String, CaseIterable, Identifiable {
    case medical, surgical, family, social

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .medical: return "history_medical"
        case .surgical: return "history_surgical"
        case .family: return "history_family"
        case .social: return "history_social"
        }
    }

    var notesKey: String {
        switch self {
        case .medical: return "history_notes_medical"
        case .surgical: return "history_notes_surgical"
        case .family: return "history_notes_family"
        case .social: return "history_notes_social"
        }
    }

    var icon: String {
        switch self {
        case .medical: return "stethoscope"
        case .surgical: return "scissors"
        case .family: return "person.2.fill"
        case .social: return "person.3.fill"
        }
    }

    var tint: Color {
        switch self {
        case .medical: return Color("BrandBlue")
        case .surgical: return .purple
        case .family: return .green
        case .social: return .orange
        }
    }
}

enum HistoryLoadState {
    case loading, failed, loaded
}

@MainActor
final class MedicalHistoryViewModel: ObservableObject {
    @Published var medical: [HistoryDiagnosis] = []
    @Published var surgical: [HistorySurgery] = []
    @Published var family: [HistoryFamilyEntry] = []
    @Published var social: HistorySocial?

    @Published var medicalState: HistoryLoadState = .loading
    @Published var surgicalState: HistoryLoadState = .loading
    @Published var familyState: HistoryLoadState = .loading
    @Published var socialState: HistoryLoadState = .loading

    @Published var notes: [HistorySection: String] = [:]
    @Published var editingSection: HistorySection?

    private var loadedOnce = false

    init() {
        for section in HistorySection.allCases {
            if let note = KeychainHelper.shared.historyNote(section: section.rawValue), !note.isEmpty {
                notes[section] = note
            }
        }
    }

    /// `.task` runs again when the view reappears (a sheet closing, a pop
    /// back); the four requests are not repeated for that.
    func loadIfNeeded() async {
        guard !loadedOnce else { return }
        await load()
    }

    /// The four sections at once; each keeps its own outcome.
    func load() async {
        loadedOnce = true
        await withTaskGroup(of: Void.self) { group in
            for section in HistorySection.allCases {
                group.addTask { await self.load(section) }
            }
        }
    }

    func load(_ section: HistorySection) async {
        switch section {
        case .medical:
            medicalState = .loading
            do {
                medical = try await APIClient.shared.get(APIEndpoints.medicalHistory)
                medicalState = .loaded
            } catch {
                medicalState = .failed
            }
        case .surgical:
            surgicalState = .loading
            do {
                surgical = try await APIClient.shared.get(APIEndpoints.surgicalHistory)
                surgicalState = .loaded
            } catch {
                surgicalState = .failed
            }
        case .family:
            familyState = .loading
            do {
                family = try await APIClient.shared.get(APIEndpoints.familyHistory)
                familyState = .loaded
            } catch {
                familyState = .failed
            }
        case .social:
            socialState = .loading
            do {
                // `{"success":true,"data":null}` is the "nothing recorded" answer.
                // Asked for the object itself, the client would fall back to
                // decoding the wrapper as a HistorySocial and fail on the
                // missing id, turning "none" into an error; asking for the
                // wrapper keeps null as null.
                let wrapper: APIResponse<HistorySocial> = try await APIClient.shared.get(APIEndpoints.socialHistory)
                social = wrapper.data
                socialState = .loaded
            } catch {
                socialState = .failed
            }
        }
    }

    func beginEditing(_ section: HistorySection) {
        editingSection = section
    }

    /// Blank removes the note, as on the web.
    func saveNote(_ text: String, for section: HistorySection) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        KeychainHelper.shared.setHistoryNote(trimmed, section: section.rawValue)
        if trimmed.isEmpty {
            notes.removeValue(forKey: section)
        } else {
            notes[section] = trimmed
        }
    }
}
