import SwiftUI

struct AppointmentsView: View {
    @StateObject private var vm = AppointmentsViewModel()
    @State private var showError = false

    var body: some View {
        NavigationStack {
            Group {
                if vm.isLoading && vm.appointments.isEmpty {
                    ProgressView("Loading appointments…")
                } else if vm.appointments.isEmpty {
                    ContentUnavailableView("No Appointments",
                        systemImage: "calendar.badge.exclamationmark",
                        description: Text("You have no upcoming appointments."))
                } else {
                    List {
                        ForEach(vm.appointments) { appt in
                            AppointmentRowView(appointment: appt)
                                .padding(.vertical, 4)
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Appointments")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { Task { await vm.load() } }) {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .refreshable { await vm.load() }
            .onChange(of: vm.errorMessage) { _, newVal in showError = (newVal != nil) }
            .alert("Error", isPresented: $showError) {
                Button("OK") { vm.errorMessage = nil }
            } message: {
                Text(vm.errorMessage ?? "")
            }
        }
        .task { await vm.load() }
    }
}

@MainActor
final class AppointmentsViewModel: ObservableObject {
    @Published var appointments: [AppointmentDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load() async {
        isLoading = true
        do {
            appointments = try await APIClient.shared.get(APIEndpoints.appointments)
        } catch { errorMessage = error.localizedDescription }
        isLoading = false
    }
}
