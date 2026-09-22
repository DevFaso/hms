import SwiftUI

// MARK: - Pre-check-in (matches the web's pre-checkin-form)

/// The web's three-step pre-check-in: the details that changed (blank keeps
/// the current value), the hospital's questionnaires for the visit, then
/// review and consent. Presented as a sheet from the appointment detail.
struct PreCheckInView: View {
    let appointment: AppointmentDTO
    let onCompleted: () -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = PreCheckInViewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(appointment.staffName ?? "—").font(.headline)
                        Text([appointment.appointmentDate, appointment.timeRange].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundColor(.secondary)
                        if let hospital = appointment.hospitalName {
                            Text(hospital).font(.caption).foregroundColor(.secondary)
                        }
                    }
                } header: {
                    Text(String(format: "step_indicator".localized, vm.stepIndex + 1, PreCheckInViewModel.Step.allCases.count)
                         + " · " + vm.step.titleKey.localized)
                }

                switch vm.step {
                case .demographics: demographicsStep
                case .questionnaires: questionnairesStep
                case .review: reviewStep
                }
            }
            .navigationTitle("pre_checkin".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { dismiss() }
                        .disabled(vm.isSubmitting)
                }
            }
            .safeAreaInset(edge: .bottom) { stepBar }
            // Leaving mid-submit would strand the answer; the sheet waits for it.
            .interactiveDismissDisabled(vm.isSubmitting)
            .task { await vm.load(appointmentId: appointment.id ?? "") }
            .onChange(of: vm.submitted) { _, done in
                if done {
                    onCompleted()
                    dismiss()
                }
            }
        }
    }

    // ── Step 1: details ──────────────────────────────────────────────────────

    private var demographicsStep: some View {
        Group {
            Section {
                Text("demographics_hint".localized).font(.footnote).foregroundColor(.secondary)
            }
            Section("contact_info".localized) {
                TextField("phone".localized, text: $vm.demographics.phoneNumber).keyboardType(.phonePad)
                TextField("email".localized, text: $vm.demographics.email)
                    .keyboardType(.emailAddress).textInputAutocapitalization(.never)
                TextField("address".localized, text: $vm.demographics.addressLine1)
                TextField("city".localized, text: $vm.demographics.city)
                TextField("state".localized, text: $vm.demographics.state)
                TextField("zip_code".localized, text: $vm.demographics.zipCode)
            }
            Section("emergency_contact".localized) {
                TextField("name".localized, text: $vm.demographics.emergencyContactName)
                TextField("phone".localized, text: $vm.demographics.emergencyContactPhone).keyboardType(.phonePad)
                TextField("relationship".localized, text: $vm.demographics.emergencyContactRelationship)
            }
            Section("insurance".localized) {
                TextField("insurance_provider".localized, text: $vm.demographics.insuranceProvider)
                TextField("member_id".localized, text: $vm.demographics.insuranceMemberId)
                TextField("insurance_plan".localized, text: $vm.demographics.insurancePlan)
            }
        }
    }

    // ── Step 2: questionnaires ───────────────────────────────────────────────

    @ViewBuilder
    private var questionnairesStep: some View {
        if vm.loadFailed {
            Section {
                Text("questionnaires_load_failed".localized).foregroundColor(.red)
                Button("retry".localized) { Task { await vm.load(appointmentId: appointment.id ?? "", force: true) } }
            }
        } else if !vm.questionnairesLoaded {
            Section { HStack { ProgressView(); Text("loading".localized).foregroundColor(.secondary) } }
        } else if vm.questionnaires.isEmpty {
            Section { Text("no_questionnaires".localized).foregroundColor(.secondary) }
        } else {
            ForEach(vm.questionnaires) { q in
                Section {
                    ForEach(q.questions) { question in
                        questionField(questionnaireId: q.id, question: question)
                    }
                } header: {
                    Text(q.title)
                } footer: {
                    if let description = q.description, !description.isEmpty { Text(description) }
                }
            }
        }
    }

    @ViewBuilder
    private func questionField(questionnaireId: String, question: QuestionnaireQuestion) -> some View {
        let value = vm.answers[questionnaireId]?[question.id]
        VStack(alignment: .leading, spacing: 6) {
            Text(question.required ? "\(question.text) *" : question.text).font(.subheadline)
            switch question.type {
            case "YES_NO":
                Picker("", selection: Binding<Bool?>(
                    get: { value?.bool },
                    set: { vm.answer(questionnaireId, question.id, $0.map(AnswerValue.bool)) }
                )) {
                    Text("yes".localized).tag(Bool?.some(true))
                    Text("no".localized).tag(Bool?.some(false))
                }
                .pickerStyle(.segmented)
            case "MULTI_CHOICE" where !question.options.isEmpty:
                Picker("", selection: Binding<String>(
                    get: { value?.text ?? "" },
                    set: { vm.answer(questionnaireId, question.id, $0.isEmpty ? nil : .text($0)) }
                )) {
                    Text("—").tag("")
                    ForEach(question.options, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.menu)
            case "NUMBER", "SCALE":
                TextField(question.text, text: Binding(
                    get: { value?.text ?? "" },
                    set: { vm.answer(questionnaireId, question.id, $0.isEmpty ? nil : .text($0)) }
                ))
                .keyboardType(.decimalPad)
                if let problem = vm.problem(question, value) {
                    Text(problem == .notANumber
                         ? "answer_not_a_number".localized
                         : String(format: "answer_out_of_range".localized, fmt(question.min), fmt(question.max)))
                        .font(.caption).foregroundColor(.red)
                } else if question.min != nil || question.max != nil {
                    Text(String(format: "answer_range_hint".localized, fmt(question.min), fmt(question.max)))
                        .font(.caption).foregroundColor(.secondary)
                }
            default:
                TextField(question.text, text: Binding(
                    get: { value?.text ?? "" },
                    set: { vm.answer(questionnaireId, question.id, $0.isEmpty ? nil : .text($0)) }
                ), axis: .vertical)
                .lineLimit(1 ... 4)
            }
        }
        .padding(.vertical, 2)
    }

    /// "3" for 3.0, "2.5" for 2.5, "–" when the bound is absent.
    private func fmt(_ d: Double?) -> String {
        guard let d else { return "\u{2013}" }
        // Int64(d) traps past 2^63; such a bound is shown as a Double.
        return d.rounded() == d && abs(d) < 9.0e18 ? String(Int64(d)) : String(d)
    }

    // ── Step 3: review and consent ───────────────────────────────────────────

    private var reviewStep: some View {
        Group {
            Section("step_demographics".localized) {
                let updated = vm.updatedFields
                if updated.isEmpty {
                    Text("nothing_to_update".localized).foregroundColor(.secondary)
                } else {
                    Text(String(format: "updated_fields".localized, updated.count)).font(.caption).foregroundColor(.secondary)
                    ForEach(Array(updated.enumerated()), id: \.offset) { _, field in
                        LabeledContent(field.0.localized, value: field.1)
                    }
                }
            }
            Section("step_questionnaires".localized) {
                Text(vm.questionnaires.isEmpty
                     ? "no_questionnaires".localized
                     : String(format: "questionnaires_completed".localized, vm.answeredQuestionnaires, vm.questionnaires.count))
                    .foregroundColor(.secondary)
            }
            Section("consent_title".localized) {
                Toggle(isOn: $vm.consentAcknowledged) {
                    Text("consent_text".localized).font(.subheadline)
                }
            }
            if let error = vm.submitError {
                Section { Text(error).foregroundColor(.red).font(.caption) }
            }
        }
    }

    // ── Bottom bar: back / next / submit ─────────────────────────────────────

    private var stepBar: some View {
        VStack(spacing: 6) {
            if vm.step == .questionnaires, vm.questionnairesLoaded, !vm.questionnairesComplete {
                Text((vm.missingRequired ? "required_answers_missing" : "answers_invalid").localized)
                    .font(.caption).foregroundColor(.red)
            }
            HStack {
                if vm.stepIndex > 0 {
                    Button("back".localized) { vm.goBack() }.disabled(vm.isSubmitting)
                }
                Spacer()
                if vm.step == .review {
                    Button {
                        Task { await vm.submit(appointmentId: appointment.id ?? "") }
                    } label: {
                        if vm.isSubmitting {
                            HStack { ProgressView(); Text("submitting".localized) }
                        } else {
                            Text("submit_pre_checkin".localized).bold()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!vm.consentAcknowledged || vm.isSubmitting)
                } else {
                    Button("next".localized) { vm.goNext() }
                        .buttonStyle(.borderedProminent)
                        .disabled(vm.step == .questionnaires && !(vm.questionnairesLoaded && vm.questionnairesComplete))
                }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }
}

/// An answer as the patient gave it: a yes/no, or the text typed or chosen.
enum AnswerValue: Equatable {
    case bool(Bool)
    case text(String)

    var bool: Bool? { if case .bool(let b) = self { return b } else { return nil } }
    var text: String? { if case .text(let t) = self { return t } else { return nil } }
    var isAnswered: Bool {
        switch self {
        case .bool: return true
        case .text(let t): return !t.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }
}

@MainActor
final class PreCheckInViewModel: ObservableObject {
    enum Step: CaseIterable {
        case demographics, questionnaires, review
        var titleKey: String {
            switch self {
            case .demographics: "step_demographics"
            case .questionnaires: "step_questionnaires"
            case .review: "step_review"
            }
        }
    }

    enum AnswerProblem { case notANumber, outOfRange }

    struct Demographics {
        var phoneNumber = "", email = "", addressLine1 = "", city = "", state = "", zipCode = ""
        var emergencyContactName = "", emergencyContactPhone = "", emergencyContactRelationship = ""
        var insuranceProvider = "", insuranceMemberId = "", insurancePlan = ""
    }

    struct ParsedQuestionnaire: Identifiable {
        let id: String
        let title: String
        let description: String?
        let questions: [QuestionnaireQuestion]
    }

    @Published var step: Step = .demographics
    @Published var questionnaires: [ParsedQuestionnaire] = []
    @Published var questionnairesLoaded = false
    @Published var loadFailed = false
    /// questionnaireId -> (questionId -> answer)
    @Published var answers: [String: [String: AnswerValue]] = [:]
    @Published var demographics = Demographics()
    @Published var consentAcknowledged = false
    @Published var isSubmitting = false
    @Published var submitError: String?
    @Published var submitted = false
    private var loadedFor: String?

    var stepIndex: Int { Step.allCases.firstIndex(of: step) ?? 0 }

    func goNext() {
        let all = Step.allCases
        if stepIndex + 1 < all.count { step = all[stepIndex + 1]; submitError = nil }
    }

    func goBack() {
        if stepIndex > 0 { step = Step.allCases[stepIndex - 1]; submitError = nil }
    }

    func load(appointmentId: String, force: Bool = false) async {
        if !force, loadedFor == appointmentId, !loadFailed { return }
        loadedFor = appointmentId
        questionnairesLoaded = false
        loadFailed = false
        do {
            let list: [QuestionnaireDTO] = try await APIClient.shared.get(APIEndpoints.appointmentQuestionnaires(id: appointmentId))
            questionnaires = list.map {
                ParsedQuestionnaire(id: $0.id, title: $0.title ?? "", description: $0.description,
                                    questions: QuestionnaireQuestion.parse($0.questions))
            }
            questionnairesLoaded = true
        } catch {
            loadFailed = true
        }
    }

    /// nil clears the answer.
    func answer(_ questionnaireId: String, _ questionId: String, _ value: AnswerValue?) {
        var current = answers[questionnaireId] ?? [:]
        current[questionId] = value
        answers[questionnaireId] = current
    }

    /// Numbers must parse, be finite and sit inside min..max; the rest is free text or a choice.
    func problem(_ question: QuestionnaireQuestion, _ value: AnswerValue?) -> AnswerProblem? {
        guard isNumeric(question), let text = value?.text?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        guard let number = Double(text.replacingOccurrences(of: ",", with: ".")), number.isFinite else { return .notANumber }
        if let min = question.min, number < min { return .outOfRange }
        if let max = question.max, number > max { return .outOfRange }
        return nil
    }

    private func isNumeric(_ q: QuestionnaireQuestion) -> Bool { q.type == "NUMBER" || q.type == "SCALE" }

    var missingRequired: Bool {
        questionnaires.contains { q in
            q.questions.contains { $0.required && !(answers[q.id]?[$0.id]?.isAnswered ?? false) }
        }
    }

    var hasAnswerProblems: Bool {
        questionnaires.contains { q in q.questions.contains { problem($0, answers[q.id]?[$0.id]) != nil } }
    }

    var questionnairesComplete: Bool { !missingRequired && !hasAnswerProblems }

    /// Questionnaires with at least one answer: what the review counts and what is sent.
    var answeredQuestionnaires: Int {
        questionnaires.filter { q in (answers[q.id] ?? [:]).values.contains { $0.isAnswered } }.count
    }

    /// (string key, value) for every detail the patient filled in.
    var updatedFields: [(String, String)] {
        let d = demographics
        return [
            ("phone", d.phoneNumber), ("email", d.email), ("address", d.addressLine1), ("city", d.city),
            ("state", d.state), ("zip_code", d.zipCode), ("emergency_contact", d.emergencyContactName),
            ("emergency_contact_phone", d.emergencyContactPhone), ("relationship", d.emergencyContactRelationship),
            ("insurance_provider", d.insuranceProvider), ("member_id", d.insuranceMemberId),
            ("insurance_plan", d.insurancePlan)
        ].filter { !$0.1.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    /// Runs as the view model's own task so the sheet cannot abandon a request
    /// the server may already have honoured; the sheet also refuses to close
    /// while it waits.
    func submit(appointmentId: String) async {
        guard !isSubmitting, consentAcknowledged else { return }
        let request = buildRequest(appointmentId: appointmentId)
        isSubmitting = true
        submitError = nil
        let task = Task<Bool, Never> {
            do {
                let _: PreCheckInResponse = try await APIClient.shared.post(APIEndpoints.preCheckIn(id: appointmentId), body: request)
                return true
            } catch {
                submitError = "precheckin_failed".localized + ": " + error.localizedDescription
                return false
            }
        }
        let ok = await task.value
        isSubmitting = false
        if ok { submitted = true }
    }

    private func buildRequest(appointmentId: String) -> PreCheckInRequest {
        func orNil(_ s: String) -> String? {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        let responses: [QuestionnaireSubmission] = questionnaires.compactMap { q in
            let given = (answers[q.id] ?? [:]).filter { $0.value.isAnswered }
            if given.isEmpty { return nil }
            var object: [String: Any] = [:]
            for (questionId, value) in given {
                object[questionId] = typed(q.questions.first { $0.id == questionId }, value)
            }
            guard let data = try? JSONSerialization.data(withJSONObject: object),
                  let json = String(data: data, encoding: .utf8) else { return nil }
            return QuestionnaireSubmission(questionnaireId: q.id, responses: json)
        }
        let d = demographics
        return PreCheckInRequest(
            appointmentId: appointmentId,
            phoneNumber: orNil(d.phoneNumber), email: orNil(d.email), addressLine1: orNil(d.addressLine1),
            city: orNil(d.city), state: orNil(d.state), zipCode: orNil(d.zipCode),
            emergencyContactName: orNil(d.emergencyContactName), emergencyContactPhone: orNil(d.emergencyContactPhone),
            emergencyContactRelationship: orNil(d.emergencyContactRelationship),
            insuranceProvider: orNil(d.insuranceProvider), insuranceMemberId: orNil(d.insuranceMemberId),
            insurancePlan: orNil(d.insurancePlan),
            questionnaireResponses: responses,
            consentAcknowledged: consentAcknowledged
        )
    }

    /// The JSON value the web would send: only NUMBER becomes a number (the
    /// web's form has no SCALE input and stores it as text).
    private func typed(_ question: QuestionnaireQuestion?, _ value: AnswerValue) -> Any {
        switch value {
        case .bool(let b): return b
        case .text(let t):
            let text = t.trimmingCharacters(in: .whitespaces)
            guard question?.type == "NUMBER",
                  let number = Double(text.replacingOccurrences(of: ",", with: ".")), number.isFinite else { return text }
            return number.rounded() == number && abs(number) < 9.0e18 ? Int64(number) as Any : number as Any
        }
    }
}
