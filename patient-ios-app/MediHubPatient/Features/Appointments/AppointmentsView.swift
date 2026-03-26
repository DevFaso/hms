import SwiftUI

struct AppointmentsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = AppointmentsViewModel()
    @State private var showError = false
    @State private var cancelTarget: AppointmentDTO?
    @State private var cancelReason = ""

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
            if vm.isLoading && vm.appointments.isEmpty {
                ProgressView("Loading appointments…")
            } else if vm.appointments.isEmpty {
                ContentUnavailableView("No Appointments",
                    systemImage: "calendar.badge.exclamationmark",
                    description: Text("You have no appointments."))
            } else {
                List {
                    // Upcoming
                    if !vm.upcoming.isEmpty {
                        Section("Upcoming") {
                            ForEach(vm.upcoming) { appt in
                                AppointmentRowView(appointment: appt)
                                    .padding(.vertical, 4)
                                    .swipeActions(edge: .trailing) {
                                        Button(role: .destructive) {
                                            cancelTarget = appt
                                        } label: {
                                            Label("Cancel", systemImage: "xmark.circle")
                                        }
                                    }
                            }
                        }
                    }

                    // Past
                    if !vm.past.isEmpty {
                        Section("Past") {
                            ForEach(vm.past) { appt in
                                AppointmentRowView(appointment: appt)
                                    .padding(.vertical, 4)
                                    .opacity(0.7)
                            }
                        }
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
        .alert("Cancel Appointment", isPresented: .constant(cancelTarget != nil)) {
            TextField("Reason (optional)", text: $cancelReason)
            Button("Cancel Appointment", role: .destructive) {
                if let appt = cancelTarget {
                    Task {
                        await vm.cancel(appointmentId: appt.id ?? "", reason: cancelReason)
                        cancelTarget = nil
                        cancelReason = ""
                    }
                }
            }
            Button("Keep", role: .cancel) { cancelTarget = nil; cancelReason = "" }
        } message: {
            Text("Are you sure you want to cancel this appointment?")
        }
    }
}

@MainActor
final class AppointmentsViewModel: ObservableObject {
    @Published var appointments: [AppointmentDTO] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    var upcoming: [AppointmentDTO] {
        appointments.filter {
            let s = $0.status?.uppercased() ?? ""
            return s != "CANCELLED" && s != "COMPLETED" && s != "NO_SHOW"
        }
    }

    var past: [AppointmentDTO] {
        appointments.filter {
            let s = $0.status?.uppercased() ?? ""
            return s == "COMPLETED" || s == "CANCELLED" || s == "NO_SHOW"
        }
    }

    func load() async {
        isLoading = true
        do {
            appointments = try await APIClient.shared.get(APIEndpoints.appointments)
        } catch { errorMessage = error.localizedDescription }
        isLoading = false
    }

    func cancel(appointmentId: String, reason: String) async {
        let req = CancelAppointmentRequest(appointmentId: appointmentId, reason: reason.isEmpty ? nil : reason)
        let _: AppointmentDTO? = try? await APIClient.shared.put(APIEndpoints.cancelAppointment, body: req)
        await load()
    }
}
