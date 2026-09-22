import SwiftUI

// MARK: - My Education (matches the web's my-education)

/// What the care team assigned to read (safety material about warning signs
/// first), what is finished, and the questions the patient asked with the
/// team's answers. Opening an item records that it was started; the reader
/// offers the material, a rating, "Mark as read" and "I understand this".
struct EducationView: View {
    @StateObject private var vm = EducationViewModel()

    var body: some View {
        Group {
            if vm.isLoading, vm.items.isEmpty, !vm.loaded {
                ProgressView("loading".localized)
            } else if vm.failed, !vm.loaded {
                // Kept apart from "nothing assigned": an empty list must never stand in for a failed request.
                ContentUnavailableView {
                    Label("education_load_failed".localized, systemImage: "exclamationmark.triangle")
                } actions: {
                    Button("retry".localized) { Task { await vm.load() } }
                }
            } else {
                VStack(spacing: 0) {
                    Picker("education".localized, selection: $vm.tab) {
                        ForEach(EducationViewModel.Tab.allCases) { tab in
                            Text(tab.titleKey.localized).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    switch vm.tab {
                    case .assigned: assignedTab
                    case .completed: completedTab
                    case .questions: questionsTab
                    }
                }
            }
        }
        .navigationTitle("education".localized)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("ask_question".localized) { vm.openAsk(nil) }
            }
        }
        .task { await vm.loadIfNeeded() }
        .onChange(of: vm.tab) { _, selected in vm.tabSelected(selected) }
        .navigationDestination(isPresented: Binding(
            get: { vm.readingId != nil },
            set: { shown in if !shown { vm.closeReader() } }
        )) {
            EducationReaderView(vm: vm)
        }
        .sheet(isPresented: $vm.askOpen, onDismiss: { vm.askDismissed() }) {
            AskQuestionSheet(vm: vm)
        }
        .educationToast(vm.outcome)
    }

    // MARK: Tabs

    @ViewBuilder
    private var assignedTab: some View {
        let list = vm.assigned
        if list.isEmpty {
            refreshableEmpty {
                if vm.completed.isEmpty {
                    ContentUnavailableView("education_empty_title".localized,
                                           systemImage: "book.closed",
                                           description: Text("education_empty_desc".localized))
                } else {
                    // Everything assigned has been read: say so, not "nothing yet".
                    ContentUnavailableView("education_all_read".localized,
                                           systemImage: "checkmark.seal",
                                           description: Text("education_all_read_desc".localized))
                }
            } refresh: {
                await vm.load()
            }
        } else {
            itemList(list, showBanner: true)
        }
    }

    @ViewBuilder
    private var completedTab: some View {
        let list = vm.completed
        if list.isEmpty {
            refreshableEmpty {
                ContentUnavailableView("education_no_completed".localized, systemImage: "book.closed")
            } refresh: {
                await vm.load()
            }
        } else {
            itemList(list, showBanner: false)
        }
    }

    private func itemList(_ list: [EducationItem], showBanner: Bool) -> some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                // Safety content first, as on the web; a completed warning-sign item stays in Completed.
                if showBanner, list.contains(where: { $0.isWarningSign }) {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.red)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("education_warning_title".localized).font(.subheadline.weight(.semibold))
                            Text("education_warning_desc".localized).font(.caption)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(14)
                    .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                }
                ForEach(EducationViewModel.ordered(list)) { item in
                    EducationItemCard(item: item, vm: vm)
                }
            }
            .padding(16)
        }
        .refreshable { await vm.load() }
    }

    /// An empty state that still answers pull-to-refresh: material assigned
    /// after "nothing yet" was shown must not need a leave-and-return.
    private func refreshableEmpty<Content: View>(
        @ViewBuilder content: () -> Content,
        refresh: @escaping @Sendable () async -> Void
    ) -> some View {
        ScrollView {
            content()
                .frame(maxWidth: .infinity)
                .containerRelativeFrame(.vertical)
        }
        .refreshable { await refresh() }
    }

    @ViewBuilder
    private var questionsTab: some View {
        if vm.questionsLoading, !vm.questionsLoaded {
            ProgressView("loading".localized).frame(maxHeight: .infinity)
        } else if vm.questionsFailed, !vm.questionsLoaded {
            ContentUnavailableView {
                Label("questions_load_failed".localized, systemImage: "exclamationmark.triangle")
            } actions: {
                Button("retry".localized) { Task { await vm.loadQuestions() } }
            }
        } else if vm.questions.isEmpty {
            refreshableEmpty {
                ContentUnavailableView {
                    Label("education_no_questions".localized, systemImage: "questionmark.bubble")
                } actions: {
                    Button("ask_question".localized) { vm.openAsk(nil) }
                }
            } refresh: {
                await vm.loadQuestions()
            }
        } else {
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(vm.questions) { question in
                        EducationQuestionCard(question: question)
                    }
                }
                .padding(16)
            }
            .refreshable { await vm.loadQuestions() }
        }
    }
}

// MARK: - Cards

private struct EducationItemCard: View {
    let item: EducationItem
    @ObservedObject var vm: EducationViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: item.resourceType == "VIDEO" ? "play.circle.fill" : "doc.text.fill")
                    .foregroundColor(item.isWarningSign ? .red : Color("BrandBlue"))
                Text(item.title ?? "").font(.headline)
                Spacer(minLength: 8)
                if let status = item.comprehensionStatus {
                    EducationStatusBadge(status: status)
                }
            }
            if let description = item.description, !description.isEmpty {
                Text(description).font(.subheadline).foregroundColor(.secondary).lineLimit(3)
            }
            HStack(spacing: 12) {
                if let category = item.category {
                    Text(EducationLabels.categoryKey(category).localized).font(.caption.weight(.medium))
                }
                if let minutes = item.estimatedDuration {
                    Text(String(format: "education_duration".localized, minutes))
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            if !item.isCompleted {
                ProgressView(value: Double(min(max(item.progressPercentage ?? 0, 0), 100)), total: 100)
            }
            HStack {
                Spacer()
                Button("ask_about_this".localized) { vm.openAsk(item) }
                    .buttonStyle(.bordered)
                Button(item.isCompleted ? "education_review".localized : "education_open".localized) {
                    vm.openReader(item)
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct EducationQuestionCard: View {
    let question: EducationQuestion

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: question.hasAnswer ? "checkmark.bubble.fill" : "clock")
                    .foregroundColor(question.hasAnswer ? .green : .secondary)
                Text("your_question".localized).font(.subheadline.weight(.semibold))
                if question.isUrgent == true {
                    Text("urgent".localized)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Color.red.opacity(0.15), in: Capsule())
                        .foregroundColor(.red)
                }
                Spacer(minLength: 0)
            }
            Text(question.questionText ?? "").font(.body)
            if let when = question.createdAt {
                Text(ScreeningsView.formatDateTime(when)).font(.caption).foregroundColor(.secondary)
            }
            Divider()
            if question.hasAnswer {
                Text("education_answer".localized).font(.subheadline.weight(.semibold)).foregroundColor(.green)
                Text(question.answerText ?? "").font(.body)
            } else {
                Text("awaiting_answer".localized).font(.caption).foregroundColor(.secondary)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct EducationStatusBadge: View {
    let status: String

    var body: some View {
        let color = EducationLabels.statusColor(status)
        Text(EducationLabels.statusKey(status).localized)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundColor(color)
    }
}

// MARK: - Reader

/// One assigned resource: its text, or a button to watch the video / open the
/// material, a rating, and "Mark as read" then "I understand this".
struct EducationReaderView: View {
    @ObservedObject var vm: EducationViewModel
    @Environment(\.openURL) private var openURL
    @State private var noViewer = false

    var body: some View {
        Group {
            if let item = vm.reading {
                ScrollView {
                    content(item)
                        .padding(16)
                }
                .navigationTitle(item.title ?? "education".localized)
            } else {
                // Only during the pop after closeReader(); nothing to show.
                Color.clear
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .educationToast(vm.outcome)
    }

    private func content(_ item: EducationItem) -> some View {
        let saving = vm.isSaving(item)
        let rating = item.rating ?? 0
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                if let category = item.category {
                    Text(EducationLabels.categoryKey(category).localized).font(.caption.weight(.medium))
                }
                if let minutes = item.estimatedDuration {
                    Text(String(format: "education_duration".localized, minutes))
                        .font(.caption).foregroundColor(.secondary)
                }
                if let status = item.comprehensionStatus {
                    EducationStatusBadge(status: status)
                }
            }
            if item.isWarningSign {
                Text("education_warning_desc".localized)
                    .font(.footnote)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            }
            if let description = item.description, !description.isEmpty {
                Text(description).font(.subheadline).foregroundColor(.secondary)
            }
            if let text = item.textContent, !text.isEmpty {
                Text(text)
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
            }
            // External material opens in whatever the phone has for it.
            if let video = item.videoUrl, !video.isEmpty {
                Button {
                    open(video)
                } label: {
                    Label("watch_video".localized, systemImage: "play.circle").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            if let link = item.contentUrl, !link.isEmpty {
                Button {
                    open(link)
                } label: {
                    Label("open_material".localized, systemImage: "arrow.up.right.square").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            if noViewer {
                Text("no_app_for_link".localized).font(.footnote).foregroundColor(.red)
            }
            if !item.hasContent {
                Text("education_no_content".localized).font(.subheadline).foregroundColor(.secondary)
            }

            Divider()
            Text("education_rate_prompt".localized).font(.subheadline.weight(.semibold))
            HStack(spacing: 4) {
                ForEach(1 ... 5, id: \.self) { star in
                    Button {
                        vm.rate(item, stars: star)
                    } label: {
                        Image(systemName: rating >= star ? "star.fill" : "star")
                            .font(.title2)
                            .foregroundColor(rating >= star ? .orange : .secondary)
                    }
                    .buttonStyle(.plain)
                    .disabled(saving)
                    .accessibilityLabel(String(format: "education_rate_star".localized, star))
                }
            }
            Button("ask_about_this".localized) { vm.openAsk(item) }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            if !item.isCompleted {
                Button {
                    vm.markComplete(item)
                } label: {
                    progressLabel("mark_as_read", busy: saving)
                }
                .buttonStyle(.borderedProminent)
                .disabled(saving)
            } else if item.confirmedUnderstanding != true {
                Button {
                    vm.confirmUnderstanding(item)
                } label: {
                    progressLabel("i_understand_this", busy: saving)
                }
                .buttonStyle(.borderedProminent)
                .disabled(saving)
            } else {
                Label("education_understood".localized, systemImage: "checkmark.seal.fill")
                    .foregroundColor(.green)
                    .font(.body.weight(.medium))
                    .padding(.vertical, 8)
            }
            Spacer(minLength: 24)
        }
    }

    private func progressLabel(_ key: String, busy: Bool) -> some View {
        HStack {
            if busy { ProgressView() }
            Text(key.localized).bold()
        }
        .frame(maxWidth: .infinity)
    }

    private func open(_ raw: String) {
        guard let url = URL(string: raw) else {
            noViewer = true
            return
        }
        noViewer = false
        openURL(url) { accepted in
            if !accepted { noViewer = true }
        }
    }
}

// MARK: - Ask sheet

/// A question for the care team, about one item or in general; 5...2000
/// characters as the server requires, optionally urgent. Cannot be dismissed
/// while the question is on its way.
struct AskQuestionSheet: View {
    @ObservedObject var vm: EducationViewModel
    @State private var text = ""
    @State private var urgent = false

    /// What is sent: the trimmed text, counted in UTF-16 units as the server does.
    private var length: Int { text.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count }
    private var canSend: Bool {
        !vm.askSubmitting && length >= EducationViewModel.questionMin && length <= EducationViewModel.questionMax
    }

    var body: some View {
        NavigationStack {
            Form {
                if let target = vm.askTarget {
                    Section {
                        Text(String(format: "ask_about".localized, target.title ?? ""))
                            .font(.subheadline).foregroundColor(.secondary)
                    }
                }
                Section {
                    ZStack(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("question_placeholder".localized)
                                .foregroundColor(Color(.placeholderText))
                                .padding(.top, 8).padding(.leading, 5)
                        }
                        TextEditor(text: $text)
                            .frame(minHeight: 120)
                            .disabled(vm.askSubmitting)
                    }
                    Toggle("mark_urgent".localized, isOn: $urgent)
                        .disabled(vm.askSubmitting)
                } footer: {
                    Text("\(length)/\(EducationViewModel.questionMax)")
                        .foregroundColor(length > EducationViewModel.questionMax ? .red : .secondary)
                }
                Section {
                    if let error = vm.askError {
                        Text(error).font(.caption).foregroundColor(.red)
                    }
                    Button {
                        vm.submitQuestion(text: text, urgent: urgent)
                    } label: {
                        if vm.askSubmitting {
                            HStack { ProgressView(); Text("sending".localized) }.frame(maxWidth: .infinity)
                        } else {
                            Text("send_question".localized).bold().frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canSend)
                }
            }
            .navigationTitle("ask_question".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { vm.closeAsk() }
                        .disabled(vm.askSubmitting)
                }
            }
            // Leaving mid-send would strand a question the server may already hold; the sheet waits.
            .interactiveDismissDisabled(vm.askSubmitting)
        }
    }
}

// MARK: - Labels

/// Category and status labels, localised here as the web does through its
/// enum-label pipe; unknown values fall back to "Other" / "Not started".
enum EducationLabels {
    static func statusKey(_ status: String) -> String {
        switch status.uppercased() {
        case "IN_PROGRESS": "edu_status_in_progress"
        case "COMPLETED": "edu_status_completed"
        case "CONFIRMED_UNDERSTANDING": "edu_status_confirmed"
        case "FEEDBACK_PROVIDED": "edu_status_feedback_provided"
        case "NEEDS_CLARIFICATION": "edu_status_needs_clarification"
        default: "edu_status_not_started"
        }
    }

    static func statusColor(_ status: String) -> Color {
        switch status.uppercased() {
        case "COMPLETED", "CONFIRMED_UNDERSTANDING", "FEEDBACK_PROVIDED": .green
        case "NEEDS_CLARIFICATION": .red
        default: Color("BrandBlue")
        }
    }

    static func categoryKey(_ category: String) -> String {
        switch category.uppercased() {
        case "PRENATAL_CARE": "edu_cat_prenatal_care"
        case "NUTRITION": "edu_cat_nutrition"
        case "EXERCISE": "edu_cat_exercise"
        case "LABOR_AND_DELIVERY": "edu_cat_labor_and_delivery"
        case "POSTPARTUM_CARE": "edu_cat_postpartum_care"
        case "BREASTFEEDING": "edu_cat_breastfeeding"
        case "NEWBORN_CARE": "edu_cat_newborn_care"
        case "MENTAL_HEALTH": "edu_cat_mental_health"
        case "WARNING_SIGNS": "edu_cat_warning_signs"
        case "BIRTH_PLAN": "edu_cat_birth_plan"
        case "PRENATAL_VITAMINS": "edu_cat_prenatal_vitamins"
        case "MANAGING_DISCOMFORT": "edu_cat_managing_discomfort"
        case "HIGH_RISK_PREGNANCY": "edu_cat_high_risk_pregnancy"
        case "ULTRASOUND_SCANS": "edu_cat_ultrasound_scans"
        case "GENETIC_SCREENING": "edu_cat_genetic_screening"
        default: "edu_cat_other"
        }
    }
}

// MARK: - Toast

/// A short confirmation at the bottom of the screen, the web's toast; the
/// view model clears it after a moment.
private struct EducationToast: ViewModifier {
    let message: String?

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .bottom) {
                if let message {
                    Text(message)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .padding(16)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.25), value: message)
    }
}

private extension View {
    func educationToast(_ message: String?) -> some View {
        modifier(EducationToast(message: message))
    }
}

// MARK: - View model

@MainActor
final class EducationViewModel: ObservableObject {
    enum Tab: String, CaseIterable, Identifiable {
        case assigned, completed, questions
        var id: String { rawValue }
        var titleKey: String {
            switch self {
            case .assigned: "education_assigned_tab"
            case .completed: "education_completed_tab"
            case .questions: "education_questions_tab"
            }
        }
    }

    /// The server's @Size bounds on questionText, counted in UTF-16 units as Java does.
    static let questionMin = 5
    static let questionMax = 2000

    @Published var items: [EducationItem] = []
    @Published var isLoading = false
    @Published var failed = false
    /// True once a load succeeded; a later failed refresh keeps the list on screen.
    @Published private(set) var loaded = false
    @Published var tab: Tab = .assigned

    @Published var questions: [EducationQuestion] = []
    @Published var questionsLoaded = false
    @Published var questionsLoading = false
    @Published var questionsFailed = false
    /// Set when a fetch is asked for while one is out; the running fetch re-runs once.
    private var questionsReloadPending = false
    /// Same for the items list: a save that lands mid-fetch re-runs the fetch once.
    private var itemsReloadPending = false

    /// The resource open in the reader; nil pops it.
    @Published var readingId: String?
    /// Resources with a progress save in flight; the reader's buttons wait on its own.
    @Published private(set) var savingIds: Set<String> = []

    /// The item a question is being asked about; nil with askOpen = a general question.
    @Published var askTarget: EducationItem?
    @Published var askOpen = false
    @Published var askSubmitting = false
    @Published var askError: String?

    @Published var outcome: String?
    private var outcomeTask: Task<Void, Never>?

    var assigned: [EducationItem] { items.filter { !$0.isCompleted } }
    var completed: [EducationItem] { items.filter { $0.isCompleted } }
    var reading: EducationItem? {
        guard let readingId else { return nil }
        return items.first { $0.resourceId == readingId }
    }

    func isSaving(_ item: EducationItem) -> Bool { savingIds.contains(item.resourceId) }

    /// Warning-sign material is safety content; it is surfaced first, as on the web.
    static func ordered(_ list: [EducationItem]) -> [EducationItem] {
        list.filter { $0.isWarningSign } + list.filter { !$0.isWarningSign }
    }

    // MARK: Loading

    func loadIfNeeded() async {
        guard !loaded, !isLoading else { return }
        await load()
    }

    /// One fetch at a time: a second call while one is out is folded into it
    /// by re-running the fetch once it lands, so a progress save that
    /// completes mid-load (see saveProgress) is never overwritten by a list
    /// fetched before the server held it.
    func load() async {
        if isLoading {
            itemsReloadPending = true
            return
        }
        isLoading = true
        repeat {
            itemsReloadPending = false
            failed = false
            do {
                let list: [EducationItem] = try await APIClient.shared.get(APIEndpoints.myEducation)
                // The progress table has no unique (patient, resource) row, so one
                // resource can come back twice; the list keys on resourceId.
                var seen = Set<String>()
                items = list.filter { seen.insert($0.resourceId).inserted }
                loaded = true
            } catch {
                failed = true
                // The list stays on screen after a failed refresh; say it did not refresh.
                if loaded { showOutcome("education_refresh_failed".localized) }
            }
        } while itemsReloadPending
        isLoading = false
    }

    func tabSelected(_ selected: Tab) {
        guard selected == .questions, !questionsLoaded, !questionsLoading else { return }
        Task { await self.loadQuestions() }
    }

    /// One fetch at a time: a second call while one is out is folded into it
    /// by re-running the fetch once it lands, so a question sent mid-load (see
    /// submitQuestion) is never overwritten by a list fetched before it existed.
    func loadQuestions() async {
        if questionsLoading {
            questionsReloadPending = true
            return
        }
        questionsLoading = true
        repeat {
            questionsReloadPending = false
            questionsFailed = false
            do {
                let list: [EducationQuestion] = try await APIClient.shared.get(APIEndpoints.educationQuestions)
                questions = list
                questionsLoaded = true
            } catch {
                questionsFailed = true
                if questionsLoaded { showOutcome("questions_refresh_failed".localized) }
            }
        } while questionsReloadPending
        questionsLoading = false
    }

    // MARK: Reading

    /// Opening counts as starting, so staff can see engagement; a finished resource is never downgraded.
    func openReader(_ item: EducationItem) {
        readingId = item.resourceId
        if !item.isCompleted, (item.progressPercentage ?? 0) == 0 {
            saveProgress(item, EducationProgressUpdate(progressPercentage: 1), notify: false)
        }
    }

    func closeReader() {
        readingId = nil
    }

    func markComplete(_ item: EducationItem) {
        saveProgress(item, EducationProgressUpdate(progressPercentage: 100), notify: true)
    }

    func confirmUnderstanding(_ item: EducationItem) {
        saveProgress(item, EducationProgressUpdate(progressPercentage: 100, confirmedUnderstanding: true), notify: true)
    }

    func rate(_ item: EducationItem, stars: Int) {
        saveProgress(item, EducationProgressUpdate(rating: min(max(stars, 1), 5)), notify: true)
    }

    /// Runs as the view model's own task, not the view's: closing the reader
    /// or leaving the screen mid-flight must not abandon a "read" or
    /// "understood" the server may already hold. Silent saves stay silent.
    private func saveProgress(_ item: EducationItem, _ update: EducationProgressUpdate, notify: Bool) {
        // A save for another resource must not swallow this one (open A, back,
        // open B while A's 1 % is still in flight): the guard is per resource.
        guard !savingIds.contains(item.resourceId) else { return }
        savingIds.insert(item.resourceId)
        Task {
            do {
                let updated: EducationItem = try await APIClient.shared.put(
                    APIEndpoints.educationProgress(resourceId: item.resourceId), body: update)
                // A refresh GET sent before this PUT could land after it with the
                // old row; the list is re-fetched once it does (load() folds it in).
                let reloadAfter = self.isLoading
                self.items = self.items.map { $0.resourceId == updated.resourceId ? updated : $0 }
                if notify { self.showOutcome("education_saved".localized) }
                if reloadAfter { await self.load() }
            } catch {
                if notify { self.showOutcome("education_save_failed".localized + ": " + error.localizedDescription) }
            }
            self.savingIds.remove(item.resourceId)
        }
    }

    // MARK: Questions

    func openAsk(_ item: EducationItem?) {
        askTarget = item
        askError = nil
        askOpen = true
    }

    func closeAsk() {
        guard !askSubmitting else { return }
        askOpen = false
    }

    /// The sheet is gone (Cancel or a swipe, never mid-send): forget its target.
    func askDismissed() {
        askTarget = nil
        askError = nil
    }

    func submitQuestion(text: String, urgent: Bool) {
        guard !askSubmitting else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.utf16.count < Self.questionMin {
            askError = "question_too_short".localized
            return
        }
        if trimmed.utf16.count > Self.questionMax {
            askError = "question_too_long".localized
            return
        }
        let request = EducationQuestionSubmit(resourceId: askTarget?.resourceId, questionText: trimmed, isUrgent: urgent)
        askSubmitting = true
        askError = nil
        Task {
            do {
                let question: EducationQuestion = try await APIClient.shared.post(APIEndpoints.educationQuestions, body: request)
                // Prepended to a loaded list (deduped by id); an unloaded list is
                // fetched on the tab's first visit, this question included. A GET
                // still in flight would land without it, so it is re-run after.
                let reloadAfter = self.questionsLoading
                if self.questionsLoaded, !self.questions.contains(where: { $0.id == question.id }) {
                    self.questions.insert(question, at: 0)
                }
                self.askSubmitting = false
                self.askOpen = false
                self.showOutcome("question_sent".localized)
                if reloadAfter { await self.loadQuestions() }
            } catch {
                self.askSubmitting = false
                self.askError = "question_failed".localized + ": " + error.localizedDescription
            }
        }
    }

    private func showOutcome(_ message: String) {
        outcome = message
        outcomeTask?.cancel()
        outcomeTask = Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled else { return }
            self.outcome = nil
        }
    }
}
