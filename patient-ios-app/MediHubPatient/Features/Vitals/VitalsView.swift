import SwiftUI

struct VitalsView: View {
    @StateObject private var vm = VitalsViewModel()
    @State private var showRecordSheet = false

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.vitals.isEmpty {
                    ProgressView("Loading vitals…")
                } else if vm.vitals.isEmpty {
                    ContentUnavailableView("No Vitals Recorded",
                        systemImage: "heart.fill",
                        description: Text("No vital signs on record."))
                } else {
                    List(vm.vitals) { vital in
                        VitalRowView(vital: vital)
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Vitals")
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
        .task { await vm.load() }
    }
}

struct VitalRowView: View {
    let vital: VitalSignDTO
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(vital.recordedAt ?? "").font(.caption).foregroundColor(.secondary)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()),
                                GridItem(.flexible())], spacing: 8) {
                VitalTile(label: "Blood Pressure", value: vital.bloodPressureDisplay, icon: "waveform.path.ecg")
                VitalTile(label: "Heart Rate", value: vital.heartRateDisplay, icon: "heart.fill")
                VitalTile(label: "Temperature", value: vital.temperatureDisplay, icon: "thermometer")
                VitalTile(label: "O₂ Saturation", value: vital.oxygenDisplay, icon: "lungs.fill")
                if let w = vital.weight, let u = vital.weightUnit {
                    VitalTile(label: "Weight", value: "\(w) \(u)", icon: "scalemass.fill")
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct VitalTile: View {
    let label: String
    let value: String
    let icon: String
    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: icon).foregroundColor(.accentColor)
            Text(value).font(.subheadline).bold()
            Text(label).font(.caption2).foregroundColor(.secondary).multilineTextAlignment(.center)
        }
        .padding(8)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
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
            }
            .navigationTitle("Record Vital")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let req = RecordVitalRequest(
                            systolicBP: Int(systolic),
                            diastolicBP: Int(diastolic),
                            heartRate: Int(heartRate),
                            temperature: Double(temperature),
                            temperatureUnit: "°C",
                            weight: nil, weightUnit: nil,
                            oxygenSaturation: Double(oxygen),
                            respiratoryRate: nil,
                            bloodGlucose: nil, notes: nil
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
