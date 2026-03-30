import SwiftUI

struct VitalsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = VitalsViewModel()
    @State private var showRecordSheet = false

    var body: some View {
        if embeddedInNav {
            NavigationStack { content }
                .task { await vm.load() }
        } else {
            content
                .task { await vm.load() }
        }
    }

    private var content: some View {
        Group {
            if vm.isLoading && vm.vitals.isEmpty {
                ProgressView("loading".localized)
            } else if vm.vitals.isEmpty {
                ContentUnavailableView("no_vitals".localized,
                    systemImage: "heart.fill",
                    description: Text("no_vitals_desc".localized))
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        ForEach(vm.vitals) { vital in
                            VitalCard(vital: vital)
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("vitals_title".localized)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { showRecordSheet = true }) {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showRecordSheet) {
            RecordVitalSheet(vm: vm)
        }
        .refreshable { await vm.load() }
    }
}

struct VitalCard: View {
    let vital: VitalSignDTO
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: vital.typeIcon)
                .font(.title2)
                .foregroundColor(.accentColor)
            Text(vital.displayValue)
                .font(.headline).bold()
            if let type = vital.type {
                Text(type.replacingOccurrences(of: "_", with: " ").capitalized)
                    .font(.caption).foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            if let date = vital.recordedAt {
                Text(date.prefix(10))
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(14)
    }
}

struct RecordVitalSheet: View {
    @ObservedObject var vm: VitalsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var systolic = ""
    @State private var diastolic = ""
    @State private var heartRate = ""
    @State private var temperature = ""
    @State private var oxygen = ""
    @State private var weight = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Blood Pressure (mmHg)") {
                    HStack {
                        TextField("Systolic", text: $systolic).keyboardType(.numberPad)
                        Text("/")
                        TextField("Diastolic", text: $diastolic).keyboardType(.numberPad)
                    }
                }
                Section("Heart Rate (bpm)") {
                    TextField("Heart Rate", text: $heartRate).keyboardType(.numberPad)
                }
                Section("Temperature (°C)") {
                    TextField("Temperature", text: $temperature).keyboardType(.decimalPad)
                }
                Section("O₂ Saturation (%)") {
                    TextField("Oxygen Saturation", text: $oxygen).keyboardType(.decimalPad)
                }
                Section("Weight (kg)") {
                    TextField("Weight", text: $weight).keyboardType(.decimalPad)
                }
            }
            .navigationTitle("Record Vital")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let req = RecordVitalRequest(
                            systolicBpMmHg: Int(systolic),
                            diastolicBpMmHg: Int(diastolic),
                            heartRateBpm: Int(heartRate),
                            temperatureCelsius: Double(temperature),
                            spo2Percent: Double(oxygen),
                            respiratoryRateBpm: nil,
                            bloodGlucoseMgDl: nil,
                            weightKg: Double(weight),
                            bodyPosition: nil,
                            notes: nil
                        )
                        Task { await vm.record(req); dismiss() }
                    }
                }
            }
        }
    }
}

@MainActor
final class VitalsViewModel: ObservableObject {
    @Published var vitals: [VitalSignDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        vitals = (try? await APIClient.shared.get(
            APIEndpoints.vitals,
            queryItems: [URLQueryItem(name: "limit", value: "20")]
        )) ?? []
        isLoading = false
    }

    func record(_ req: RecordVitalRequest) async {
        if let newVital: VitalSignDTO = try? await APIClient.shared.post(APIEndpoints.vitals, body: req) {
            vitals.insert(newVital, at: 0)
        }
    }
}
