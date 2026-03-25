import SwiftUI

struct MedicationsView: View {
    @StateObject private var vm = MedicationsViewModel()
    @State private var selectedTab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $selectedTab) {
                    Text("Medications").tag(0)
                    Text("Prescriptions").tag(1)
                }
                .pickerStyle(.segmented)
                .padding()

                if vm.isLoading {
                    ProgressView().padding()
                } else {
                    if selectedTab == 0 {
                        medicationsList
                    } else {
                        prescriptionsList
                    }
                }
            }
            .navigationTitle("Medications")
            .refreshable { await vm.load() }
        }
        .task { await vm.load() }
    }

    private var medicationsList: some View {
        Group {
            if vm.medications.isEmpty {
                ContentUnavailableView("No Medications",
                    systemImage: "pill.fill",
                    description: Text("No active medications on record."))
            } else {
                List(vm.medications) { med in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(med.medicationName ?? "Medication").font(.headline)
                            Spacer()
                            StatusBadge(text: med.status?.capitalized ?? "Active",
                                        color: med.status?.uppercased() == "ACTIVE" ? "green" : "gray")
                        }
                        if let dosage = med.dosage, let freq = med.frequency {
                            Text("\(dosage) · \(freq)").font(.subheadline).foregroundColor(.secondary)
                        }
                        if let dr = med.prescribedBy {
                            Text("Prescribed by \(dr)").font(.caption).foregroundColor(.secondary)
                        }
                        if let refills = med.refillsRemaining {
                            Text("\(refills) refill(s) remaining").font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    private var prescriptionsList: some View {
        Group {
            if vm.prescriptions.isEmpty {
                ContentUnavailableView("No Prescriptions",
                    systemImage: "doc.text.fill",
                    description: Text("No prescriptions on record."))
            } else {
                List(vm.prescriptions) { rx in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(rx.medicationName ?? "Prescription").font(.headline)
                            Spacer()
                            StatusBadge(text: rx.status?.capitalized ?? "Active",
                                        color: "blue")
                        }
                        if let dosage = rx.dosage, let freq = rx.frequency {
                            Text("\(dosage) · \(freq)").font(.subheadline).foregroundColor(.secondary)
                        }
                        if let dr = rx.prescribedBy {
                            Text("By \(dr)").font(.caption).foregroundColor(.secondary)
                        }
                        if let refills = rx.refills {
                            Text("\(refills) refill(s)").font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
    }
}

@MainActor
final class MedicationsViewModel: ObservableObject {
    @Published var medications: [MedicationDTO] = []
    @Published var prescriptions: [PrescriptionDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                self.medications = (try? await APIClient.shared.get(
                    APIEndpoints.medications,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )) ?? []
            }
            group.addTask {
                self.prescriptions = (try? await APIClient.shared.get(APIEndpoints.prescriptions)) ?? []
            }
        }
        isLoading = false
    }
}
