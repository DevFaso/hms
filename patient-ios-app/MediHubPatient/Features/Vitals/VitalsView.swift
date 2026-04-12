import SwiftUI

struct VitalsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = VitalsViewModel()
    @State private var selectedVital: VitalSignDTO?

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
            if vm.isLoading, vm.vitals.isEmpty {
                ProgressView("loading".localized)
            } else if vm.vitals.isEmpty {
                ContentUnavailableView("no_vitals".localized,
                                       systemImage: "heart.fill",
                                       description: Text("no_vitals_desc".localized))
            } else {
                ScrollView {
                    // Latest readings card
                    if let latest = vm.vitals.first {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Latest Readings").font(.headline).padding(.horizontal)
                            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                                if latest.systolicBpMmHg != nil {
                                    VitalTileView(label: "Blood Pressure", value: latest.bloodPressureDisplay, icon: "waveform.path.ecg", color: .red)
                                }
                                if latest.heartRateBpm != nil {
                                    VitalTileView(label: "Heart Rate", value: latest.heartRateDisplay, icon: "heart.fill", color: .pink)
                                }
                                if latest.temperatureCelsius != nil {
                                    VitalTileView(label: "Temperature", value: latest.temperatureDisplay, icon: "thermometer", color: .orange)
                                }
                                if latest.spo2Percent != nil {
                                    VitalTileView(label: "SpO₂", value: latest.oxygenDisplay, icon: "lungs.fill", color: .blue)
                                }
                                if latest.respiratoryRateBpm != nil {
                                    VitalTileView(label: "Resp. Rate", value: latest.respiratoryRateDisplay, icon: "wind", color: .teal)
                                }
                                if latest.bloodGlucoseMgDl != nil {
                                    VitalTileView(label: "Blood Glucose", value: latest.bloodGlucoseDisplay, icon: "drop.fill", color: .indigo)
                                }
                                if latest.weightKg != nil {
                                    VitalTileView(label: "Weight", value: latest.weightDisplay, icon: "scalemass.fill", color: .purple)
                                }
                            }
                            .padding(.horizontal)
                        }
                        .padding(.top)
                    }

                    // History list
                    if vm.vitals.count > 1 {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("History").font(.headline).padding(.horizontal).padding(.top)
                            ForEach(vm.vitals) { vital in
                                Button { selectedVital = vital } label: {
                                    VitalHistoryRow(vital: vital)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else if let only = vm.vitals.first {
                        Button { selectedVital = only } label: {
                            Text("View Full Details")
                                .font(.subheadline).bold()
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        .buttonStyle(.borderedProminent)
                        .padding()
                    }
                }
            }
        }
        .navigationTitle("vitals_title".localized)
        .refreshable { await vm.load() }
        .sheet(item: $selectedVital) { vital in
            VitalDetailSheet(vital: vital)
        }
    }
}

// MARK: - Vital Tile (latest readings grid)
struct VitalTileView: View {
    let label: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundColor(color)
            Text(value).font(.headline).bold()
            Text(label).font(.caption).foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(color.opacity(0.08))
        .cornerRadius(14)
    }
}

// MARK: - History row
struct VitalHistoryRow: View {
    let vital: VitalSignDTO
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(vital.recordedDateDisplay)
                    .font(.subheadline).bold()
                if let src = vital.source, !src.isEmpty {
                    Text(vital.sourceDisplay).font(.caption2).foregroundColor(.secondary)
                }
                HStack(spacing: 12) {
                    if vital.systolicBpMmHg != nil { Label(vital.bloodPressureDisplay, systemImage: "waveform.path.ecg").font(.caption) }
                    if vital.heartRateBpm != nil { Label(vital.heartRateDisplay, systemImage: "heart.fill").font(.caption) }
                    if vital.spo2Percent != nil { Label(vital.oxygenDisplay, systemImage: "lungs.fill").font(.caption) }
                    if vital.temperatureCelsius != nil { Label(vital.temperatureDisplay, systemImage: "thermometer").font(.caption) }
                }
                .foregroundColor(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .foregroundColor(.secondary)
                .font(.caption)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
        .padding(.horizontal)
    }
}

// MARK: - Vital Detail Sheet
struct VitalDetailSheet: View {
    let vital: VitalSignDTO
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Recorded") {
                    if let date = vital.recordedAt {
                        HStack { Text("Date").foregroundColor(.secondary); Spacer(); Text(vital.recordedDateDisplay) }
                    }
                    if let src = vital.source, !src.isEmpty {
                        HStack { Text("Source").foregroundColor(.secondary); Spacer(); Text(vital.sourceDisplay) }
                    }
                    if let by = vital.recordedByName, !by.isEmpty {
                        HStack { Text("Recorded By").foregroundColor(.secondary); Spacer(); Text(by) }
                    }
                }

                Section("Readings") {
                    ForEach(vital.allReadings, id: \.label) { reading in
                        HStack {
                            Text(reading.label).foregroundColor(.secondary)
                            Spacer()
                            Text(reading.value).bold()
                        }
                    }
                    if vital.allReadings.isEmpty {
                        Text("No detailed readings available").foregroundColor(.secondary)
                    }
                }

                if let notes = vital.notes, !notes.isEmpty {
                    Section("Notes") {
                        Text(notes)
                    }
                }
            }
            .navigationTitle("Vital Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
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
        vitals = await (try? APIClient.shared.get(
            APIEndpoints.vitals,
            queryItems: [URLQueryItem(name: "limit", value: "20")]
        )) ?? []
        isLoading = false
    }
}
