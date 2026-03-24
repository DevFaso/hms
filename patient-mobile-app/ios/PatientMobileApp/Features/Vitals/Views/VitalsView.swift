import SwiftUI

struct VitalsView: View {
    @StateObject private var viewModel = VitalsViewModel()
    @State private var showRecordSheet = false

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.vitals.isEmpty {
                    HMSLoadingView(message: "Loading vitals...")
                } else if let error = viewModel.errorMessage, viewModel.vitals.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadVitals() } }
                } else if viewModel.vitals.isEmpty {
                    HMSEmptyState(icon: "heart.text.square", title: "No Vitals", message: "Your vitals will appear here.\nTap + to record a new reading.")
                } else {
                    vitalsList
                }
            }
            .navigationTitle("Vitals")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showRecordSheet = true } label: {
                        Image(systemName: "plus.circle.fill")
                    }
                    .accessibilityLabel("Record vital sign")
                }
            }
            .sheet(isPresented: $showRecordSheet) {
                RecordVitalSheet(viewModel: viewModel, isPresented: $showRecordSheet)
            }
            .task { await viewModel.loadVitals() }
            .refreshable { await viewModel.loadVitals() }
        }
    }

    private var vitalsList: some View {
        List(viewModel.vitals) { vital in
            VitalRow(vital: vital)
        }
        .listStyle(.plain)
    }
}

// MARK: - Vital Row

private struct VitalRow: View {
    let vital: VitalDto

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: vital.icon)
                .font(.title3)
                .foregroundColor(.hmsPrimary)
                .frame(width: 36, height: 36)
                .background(Color.hmsPrimary.opacity(0.1))
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(vital.type?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Vital")
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsTextPrimary)
                if let date = vital.recordedAt {
                    Text(date)
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                }
            }

            Spacer()

            Text(vital.displayValue)
                .font(.hmsSubheadline)
                .foregroundColor(.hmsPrimary)
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Record Vital Sheet

private struct RecordVitalSheet: View {
    @ObservedObject var viewModel: VitalsViewModel
    @Binding var isPresented: Bool

    @State private var selectedType = "blood_pressure"
    @State private var value = ""
    @State private var unit = "mmHg"
    @State private var notes = ""

    private let vitalTypes = [
        ("blood_pressure", "Blood Pressure", "mmHg"),
        ("heart_rate", "Heart Rate", "bpm"),
        ("temperature", "Temperature", "°F"),
        ("weight", "Weight", "kg"),
        ("oxygen_saturation", "Oxygen Saturation", "%"),
        ("blood_glucose", "Blood Glucose", "mg/dL"),
        ("respiratory_rate", "Respiratory Rate", "breaths/min"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("Vital Type") {
                    Picker("Type", selection: $selectedType) {
                        ForEach(vitalTypes, id: \.0) { type in
                            Text(type.1).tag(type.0)
                        }
                    }
                    .onChange(of: selectedType) { _, newValue in
                        if let match = vitalTypes.first(where: { $0.0 == newValue }) {
                            unit = match.2
                        }
                    }
                }

                Section("Reading") {
                    HMSTextField(placeholder: "Value", text: $value, icon: "number")
                        .keyboardType(.decimalPad)
                    HMSTextField(placeholder: "Unit", text: $unit, icon: "ruler")
                        .disabled(true)
                }

                Section("Notes (Optional)") {
                    TextEditor(text: $notes)
                        .frame(minHeight: 60)
                }
            }
            .navigationTitle("Record Vital")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let numValue = Double(value) else { return }
                        Task {
                            await viewModel.recordVital(RecordVitalRequest(
                                type: selectedType,
                                value: numValue,
                                unit: unit,
                                notes: notes.isEmpty ? nil : notes
                            ))
                            if viewModel.recordSuccess {
                                isPresented = false
                                viewModel.recordSuccess = false
                            }
                        }
                    }
                    .disabled(value.isEmpty || viewModel.isRecording)
                }
            }
        }
    }
}
