import SwiftUI

struct AppointmentsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = AppointmentsViewModel()
    @State private var showError = false
    @State private var cancelTarget: AppointmentDTO?
    @State private var cancelReason = ""
    @State private var rescheduleTarget: AppointmentDTO?

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
                                    .swipeActions(edge: .leading) {
                                        Button {
                                            rescheduleTarget = appt
                                        } label: {
                                            Label("Reschedule", systemImage: "calendar.badge.clock")
                                        }
                                        .tint(.orange)
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
        .sheet(item: $rescheduleTarget) { appt in
            RescheduleSheet(appointment: appt, vm: vm, isPresented: $rescheduleTarget)
        }
    }
}

// MARK: - Reschedule Sheet

struct RescheduleSheet: View {
    let appointment: AppointmentDTO
    @ObservedObject var vm: AppointmentsViewModel
    @Binding var isPresented: AppointmentDTO?
    @State private var newDate = Date()
    @State private var newStartTime = Date()
    @State private var newEndTime = Date().addingTimeInterval(1800) // 30 min
    @State private var reason = ""
    @State private var isSubmitting = false
    @State private var errorMsg: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Current Appointment") {
                    HStack { Text("Provider").foregroundColor(.secondary); Spacer(); Text(appointment.staffName ?? "—") }
                    HStack { Text("Date").foregroundColor(.secondary); Spacer(); Text(appointment.appointmentDate ?? "—") }
                    HStack { Text("Time").foregroundColor(.secondary); Spacer(); Text(appointment.timeRange ?? "—") }
                }

                Section("New Date & Time") {
                    DatePicker("Date", selection: $newDate, in: Date()..., displayedComponents: .date)
                    DatePicker("Start Time", selection: $newStartTime, displayedComponents: .hourAndMinute)
                    DatePicker("End Time", selection: $newEndTime, displayedComponents: .hourAndMinute)
                }

                Section("Reason") {
                    TextField("Why are you rescheduling?", text: $reason, axis: .vertical)
                        .lineLimit(3)
                }

                if let err = errorMsg {
                    Section { Text(err).foregroundColor(.red).font(.caption) }
                }
            }
            .navigationTitle("Reschedule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Reschedule") { Task { await submit() } }
                        .disabled(isSubmitting)
                        .bold()
                }
            }
            .interactiveDismissDisabled(isSubmitting)
        }
    }

    private func submit() async {
        isSubmitting = true
        errorMsg = nil

        let dateFmt = DateFormatter()
        dateFmt.dateFormat = "yyyy-MM-dd"
        let timeFmt = DateFormatter()
        timeFmt.dateFormat = "HH:mm:ss"

        let result = await vm.reschedule(
            appointmentId: appointment.id ?? "",
            newDate: dateFmt.string(from: newDate),
            newStartTime: timeFmt.string(from: newStartTime),
            newEndTime: timeFmt.string(from: newEndTime),
            reason: reason
        )
        if let err = result {
            errorMsg = err
        } else {
            isPresented = nil
        }
        isSubmitting = false
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

    func reschedule(appointmentId: String, newDate: String, newStartTime: String, newEndTime: String, reason: String) async -> String? {
        let req = RescheduleAppointmentRequest(
            appointmentId: appointmentId,
            newDate: newDate,
            newStartTime: newStartTime,
            newEndTime: newEndTime,
            reason: reason.isEmpty ? nil : reason
        )
        do {
            let _: AppointmentDTO = try await APIClient.shared.put(APIEndpoints.rescheduleAppointment, body: req)
            await load()
            return nil
        } catch {
            return error.localizedDescription
        }
    }
}
