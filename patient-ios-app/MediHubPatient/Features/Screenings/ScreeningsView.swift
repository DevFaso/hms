import SwiftUI

// MARK: - My Screenings (matches the web's my-screenings)

/// A mother answers her own mental-health screening (EPDS) from home while a
/// postpartum plan is open for her. Deliberately score-free: the backend never
/// sends a total to this surface and this screen never computes one. What she
/// sees afterwards is whether her care team will follow up, and whether they
/// were alerted straight away.
struct ScreeningsView: View {
    @StateObject private var vm = ScreeningsViewModel()

    var body: some View {
        Group {
            if vm.isLoading, vm.available.isEmpty, vm.history.isEmpty {
                ProgressView("loading".localized)
            } else if vm.failed, vm.available.isEmpty, vm.history.isEmpty {
                // Kept apart from "nothing open": an empty list must never stand in for a failed request.
                ContentUnavailableView {
                    Label("screenings_load_failed".localized, systemImage: "exclamationmark.triangle")
                } actions: {
                    Button("retry".localized) { Task { await vm.load() } }
                }
            } else {
                List {
                    Section {
                        Text("screenings_privacy_note".localized).font(.footnote).foregroundColor(.secondary)
                    }
                    if vm.available.isEmpty {
                        Section {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("screenings_none_open_title".localized).font(.headline)
                                Text("screenings_none_open_desc".localized).font(.subheadline).foregroundColor(.secondary)
                            }
                        }
                    } else {
                        Section {
                            ForEach(vm.available) { a in
                                HStack {
                                    Text(a.name ?? a.code).font(.headline)
                                    Spacer()
                                    Button("start_screening".localized) { vm.start(a) }
                                        .buttonStyle(.borderedProminent)
                                }
                            }
                        } header: {
                            Text("screenings_open_now".localized)
                        } footer: {
                            Text("screenings_open_hint".localized)
                        }
                    }
                    if !vm.history.isEmpty {
                        Section("your_answers".localized) {
                            ForEach(vm.history) { entry in
                                HStack(alignment: .top, spacing: 12) {
                                    Image(systemName: entry.careTeamAlerted == true ? "bell.badge.fill" : "checkmark.circle.fill")
                                        .foregroundColor(entry.careTeamAlerted == true ? .red : .green)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(entry.instrumentName ?? entry.instrumentCode ?? "").font(.headline)
                                        Text(statusKey(entry).localized).font(.subheadline)
                                        if let when = entry.administeredAt {
                                            Text(Self.formatDateTime(when)).font(.caption).foregroundColor(.secondary)
                                        }
                                    }
                                }
                                .padding(.vertical, 2)
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("screenings".localized)
        .task { await vm.load() }
        .refreshable { await vm.load() }
        .fullScreenCover(item: $vm.active) { active in
            ScreeningFormView(vm: vm, active: active)
        }
        .alert("screening_submitted_title".localized, isPresented: $vm.showSubmitted) {
            Button("ok".localized, role: .cancel) {}
        } message: {
            Text("screening_submitted".localized)
        }
    }

    private func statusKey(_ entry: ProScreeningEntry) -> String {
        if entry.careTeamAlerted == true { return "care_team_alerted" }
        if entry.followUpPlanned == true { return "follow_up_planned" }
        return "screening_recorded"
    }

    /// "2026-09-21T10:15:00" in the device's medium date / short time; the raw text when it does not parse.
    static func formatDateTime(_ iso: String) -> String {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .iso8601)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        guard let date = parser.date(from: String(iso.prefix(19))) else { return iso }
        let out = DateFormatter()
        out.dateStyle = .medium
        out.timeStyle = .short
        return out.string(from: date)
    }
}

/// The instrument itself: language, instruction, one choice per item, send.
struct ScreeningFormView: View {
    @ObservedObject var vm: ScreeningsViewModel
    let active: ProScreeningAvailable

    var body: some View {
        NavigationStack {
            Group {
                if vm.instrumentLoading {
                    ProgressView("loading".localized)
                } else if vm.instrumentFailed || vm.instrument == nil {
                    ContentUnavailableView {
                        Label("instrument_load_failed".localized, systemImage: "exclamationmark.triangle")
                    } actions: {
                        Button("retry".localized) { vm.retryInstrument() }
                    }
                } else if let instrument = vm.instrument {
                    form(instrument)
                }
            }
            .navigationTitle(active.name ?? active.code)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { vm.cancel() }
                        .disabled(vm.isSubmitting)
                }
            }
            // A cover has no swipe-to-dismiss; Cancel is the only way out and it waits.
        }
    }

    private func form(_ instrument: ProInstrumentView) -> some View {
        let items = instrument.items ?? []
        let answered = items.filter { vm.answers[$0.itemNo] != nil }.count
        let languages = instrument.availableLanguages ?? []
        return Form {
            Section {
                if languages.count > 1 {
                    Picker("language".localized, selection: Binding(
                        get: { instrument.language ?? vm.language },
                        set: { vm.changeLanguage($0) }
                    )) {
                        ForEach(languages, id: \.self) { Text($0).tag($0) }
                    }
                    .disabled(vm.isSubmitting)
                }
                Text("screenings_privacy_note".localized).font(.footnote).foregroundColor(.secondary)
                if let instruction = instrument.instruction, !instruction.isEmpty {
                    Text(instruction).font(.subheadline)
                }
                Text(String(format: "screening_progress".localized, answered, items.count))
                    .font(.caption).foregroundColor(.accentColor)
            }
            ForEach(items) { item in
                let chosen = vm.answers[item.itemNo]
                let flagged = vm.missingItems.contains(item.itemNo)
                Section {
                    ForEach(item.options ?? []) { option in
                        Button {
                            vm.answer(item.itemNo, option.optionNo)
                        } label: {
                            HStack {
                                Image(systemName: chosen == option.optionNo ? "largecircle.fill.circle" : "circle")
                                    .foregroundColor(chosen == option.optionNo ? .accentColor : .secondary)
                                Text(option.label ?? "").foregroundColor(.primary)
                                Spacer()
                            }
                        }
                        .disabled(vm.isSubmitting)
                    }
                } header: {
                    Text("\(item.itemNo). \(item.prompt ?? "")")
                        .foregroundColor(flagged ? .red : nil)
                }
            }
            Section {
                if let error = vm.submitError {
                    Text(error).font(.caption).foregroundColor(.red)
                }
                Button {
                    Task { await vm.submit() }
                } label: {
                    if vm.isSubmitting {
                        HStack { ProgressView(); Text("sending".localized) }.frame(maxWidth: .infinity)
                    } else {
                        Text("send_my_answers".localized).bold().frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(vm.isSubmitting)
            } footer: {
                if let source = instrument.sourceCitation, !source.isEmpty { Text(source) }
            }
        }
    }
}

@MainActor
final class ScreeningsViewModel: ObservableObject {
    @Published var available: [ProScreeningAvailable] = []
    @Published var history: [ProScreeningEntry] = []
    @Published var isLoading = false
    @Published var failed = false

    @Published var active: ProScreeningAvailable?
    @Published var instrument: ProInstrumentView?
    @Published var instrumentLoading = false
    @Published var instrumentFailed = false
    @Published var language = ""
    /// itemNo -> optionNo
    @Published var answers: [Int: Int] = [:]
    /// Items named by the last refused send, cleared as they are answered.
    @Published var missingItems: Set<Int> = []
    @Published var isSubmitting = false
    @Published var submitError: String?
    @Published var showSubmitted = false
    private var instrumentTask: Task<Void, Never>?

    func load() async {
        isLoading = true
        failed = false
        do {
            let report: ProSelfReport = try await APIClient.shared.get(APIEndpoints.myScreenings)
            available = report.available ?? []
            history = report.history ?? []
        } catch {
            failed = true
        }
        isLoading = false
    }

    /// Opens the form in the device language; the server falls back to English and says which it served.
    func start(_ instrument: ProScreeningAvailable) {
        answers = [:]
        missingItems = []
        submitError = nil
        language = Locale.current.language.languageCode?.identifier ?? ""
        active = instrument
        loadInstrument(code: instrument.code, language: language)
    }

    func cancel() {
        guard !isSubmitting else { return }
        instrumentTask?.cancel()
        active = nil
        instrument = nil
        instrumentLoading = false
        instrumentFailed = false
    }

    func changeLanguage(_ language: String) {
        guard let active else { return }
        self.language = language
        loadInstrument(code: active.code, language: language)
    }

    func retryInstrument() {
        guard let active else { return }
        loadInstrument(code: active.code, language: language)
    }

    /// One request at a time, keyed on (code, language): a language switch
    /// cancels the one before it, so a slow first answer can never overwrite
    /// the wording the mother chose. Answers survive a switch: item and
    /// option numbers are language-independent.
    private func loadInstrument(code: String, language: String) {
        instrumentTask?.cancel()
        instrumentLoading = true
        instrumentFailed = false
        instrumentTask = Task {
            do {
                let query = language.isEmpty ? nil : [URLQueryItem(name: "language", value: language)]
                let view: ProInstrumentView = try await APIClient.shared.get(
                    APIEndpoints.screeningInstrument(code: code), queryItems: query)
                guard !Task.isCancelled else { return }
                let itemNos = Set((view.items ?? []).map { $0.itemNo })
                answers = answers.filter { itemNos.contains($0.key) }
                instrument = view
                self.language = view.language ?? language
                instrumentLoading = false
            } catch {
                guard !Task.isCancelled else { return }
                instrument = nil
                instrumentLoading = false
                instrumentFailed = true
            }
        }
    }

    func answer(_ itemNo: Int, _ optionNo: Int) {
        answers[itemNo] = optionNo
        missingItems.remove(itemNo)
        if missingItems.isEmpty { submitError = nil }
        else { submitError = String(format: "screening_incomplete".localized, missingItems.sorted().map(String.init).joined(separator: ", ")) }
    }

    /// The form's own task, so closing the cover cannot abandon an answer the
    /// server may already have recorded (a safety-item answer alerts the care
    /// team on receipt); Cancel is disabled while it is out.
    func submit() async {
        guard let instrument, !isSubmitting else { return }
        let missing = (instrument.items ?? []).map { $0.itemNo }.filter { answers[$0] == nil }
        if !missing.isEmpty {
            missingItems = Set(missing)
            submitError = String(format: "screening_incomplete".localized, missing.map(String.init).joined(separator: ", "))
            return
        }
        let request = ProResponseCreate(
            instrumentCode: instrument.code,
            language: instrument.language,
            answers: Dictionary(uniqueKeysWithValues: answers.map { (String($0.key), $0.value) })
        )
        isSubmitting = true
        submitError = nil
        let task = Task<Result<ProScreeningEntry, Error>, Never> {
            do {
                let entry: ProScreeningEntry = try await APIClient.shared.post(APIEndpoints.myScreenings, body: request)
                return .success(entry)
            } catch {
                return .failure(error)
            }
        }
        let result = await task.value
        isSubmitting = false
        switch result {
        case .success(let entry):
            history.insert(entry, at: 0)
            instrumentTask?.cancel()
            active = nil
            instrument = nil
            answers = [:]
            showSubmitted = true
        case .failure(let error):
            submitError = "screening_submit_failed".localized + ": " + error.localizedDescription
            // A refusal usually means the plan closed since the form was
            // opened; the overview is reloaded so the list stops offering it.
            if case APIError.httpError(let status, _) = error, (400 ..< 500).contains(status) {
                await load()
            }
        }
    }
}
