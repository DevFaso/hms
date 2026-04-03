import SwiftUI

struct AppointmentsView: View {
    var embeddedInNav: Bool = true
    @StateObject private var vm = AppointmentsViewModel()
    @State private var showError = false
    @State private var cancelTarget: AppointmentDTO?
    @State private var showCancelAlert = false
    @State private var cancelReason = ""
    @State private var rescheduleTarget: AppointmentDTO?
    @State private var showBooking = false

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
            if vm.isLoading, vm.appointments.isEmpty {
                ProgressView("loading".localized)
            } else if vm.appointments.isEmpty {
                ContentUnavailableView("no_appointments".localized,
                                       systemImage: "calendar.badge.exclamationmark",
                                       description: Text("no_appointments_desc".localized))
            } else {
                List {
                    // Upcoming
                    if !vm.upcoming.isEmpty {
                        Section("upcoming".localized) {
                            ForEach(vm.upcoming) { appt in
                                NavigationLink(destination: AppointmentDetailView(appointment: appt)) {
                                    AppointmentRowView(appointment: appt)
                                }
                                .padding(.vertical, 4)
                                .swipeActions(edge: .trailing) {
                                    Button(role: .destructive) {
                                        cancelTarget = appt
                                        showCancelAlert = true
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
                        Section("past".localized) {
                            ForEach(vm.past) { appt in
                                NavigationLink(destination: AppointmentDetailView(appointment: appt)) {
                                    AppointmentRowView(appointment: appt)
                                }
                                .padding(.vertical, 4)
                                .opacity(0.7)
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("tab_appointments".localized)
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(action: { showBooking = true }) {
                    Image(systemName: "plus")
                }
            }
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
        .alert("Cancel Appointment", isPresented: $showCancelAlert) {
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
            if let appt = cancelTarget {
                Text("Cancel appointment with \(appt.staffName ?? "provider") on \(appt.appointmentDate ?? "")?")
            } else {
                Text("Are you sure you want to cancel this appointment?")
            }
        }
        .sheet(item: $rescheduleTarget) { appt in
            RescheduleSheet(appointment: appt, vm: vm, isPresented: $rescheduleTarget)
        }
        .sheet(isPresented: $showBooking) {
            BookAppointmentSheet(vm: vm, isPresented: $showBooking)
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
        do {
            let req = CancelAppointmentRequest(appointmentId: appointmentId, reason: reason.isEmpty ? nil : reason)
            let _: AppointmentDTO = try await APIClient.shared.put(APIEndpoints.cancelAppointment, body: req)
            await load()
        } catch {
            errorMessage = "Cancel failed: \(error.localizedDescription)"
            await load()
        }
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

    func bookAppointment(_ request: BookAppointmentRequest) async -> String? {
        do {
            let _: AppointmentDTO = try await APIClient.shared.post(APIEndpoints.bookAppointment, body: request)
            await load()
            return nil
        } catch {
            return error.localizedDescription
        }
    }
}

// MARK: - Book Appointment Sheet

struct BookAppointmentSheet: View {
    @ObservedObject var vm: AppointmentsViewModel
    @Binding var isPresented: Bool
    @State private var appointmentDate = Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date()
    @State private var startTime = {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        comps.hour = 9; comps.minute = 0
        return Calendar.current.date(from: comps) ?? Date()
    }()

    @State private var endTime = {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        comps.hour = 9; comps.minute = 30
        return Calendar.current.date(from: comps) ?? Date()
    }()

    @State private var selectedDoctorKey = ""
    @State private var reason = ""
    @State private var notes = ""
    @State private var isSubmitting = false
    @State private var errorMsg: String?

    /// Build unique doctor entries from past appointments
    private struct DoctorOption: Hashable {
        let key: String // unique composite key
        let staffId: String
        let staffName: String
        let staffEmail: String?
        let hospitalName: String
        let hospitalId: String?
        let departmentId: String?
    }

    private var doctorOptions: [DoctorOption] {
        var seen = Set<String>()
        var result: [DoctorOption] = []
        for appt in vm.appointments {
            guard let sid = appt.staffId, !sid.isEmpty,
                  let sname = appt.staffName, !sname.isEmpty,
                  let hname = appt.hospitalName, !hname.isEmpty else { continue }
            let key = "\(sid)|\(hname)"
            if seen.insert(key).inserted {
                result.append(DoctorOption(
                    key: key, staffId: sid, staffName: sname,
                    staffEmail: appt.staffEmail,
                    hospitalName: hname, hospitalId: appt.hospitalId,
                    departmentId: appt.departmentId
                ))
            }
        }
        return result.sorted { $0.staffName < $1.staffName }
    }

    private var selectedDoctor: DoctorOption? {
        doctorOptions.first { $0.key == selectedDoctorKey }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Doctor & Hospital") {
                    if doctorOptions.isEmpty {
                        Text("No previous doctors found.\nAsk the front desk to book your first appointment.")
                            .foregroundColor(.secondary)
                            .font(.callout)
                    } else {
                        Picker("Select Doctor", selection: $selectedDoctorKey) {
                            Text("Select a doctor…").tag("")
                            ForEach(doctorOptions, id: \.key) { doc in
                                Text("\(doc.staffName) — \(doc.hospitalName)")
                                    .tag(doc.key)
                            }
                        }
                    }

                    if let doc = selectedDoctor {
                        HStack {
                            Text("Hospital").foregroundColor(.secondary)
                            Spacer()
                            Text(doc.hospitalName)
                        }
                    }
                }

                Section("Date & Time") {
                    DatePicker("Date", selection: $appointmentDate, in: Date()..., displayedComponents: .date)
                    DatePicker("Start Time", selection: $startTime, displayedComponents: .hourAndMinute)
                    DatePicker("End Time", selection: $endTime, displayedComponents: .hourAndMinute)
                }

                Section("Details") {
                    TextField("Reason for visit", text: $reason, axis: .vertical)
                        .lineLimit(2 ... 4)
                    TextField("Additional notes (optional)", text: $notes, axis: .vertical)
                        .lineLimit(2 ... 4)
                }

                if let err = errorMsg {
                    Section {
                        Text(err)
                            .foregroundColor(.red)
                            .font(.caption)
                    }
                }
            }
            .navigationTitle("Book Appointment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Book") { Task { await submit() } }
                        .disabled(isSubmitting || selectedDoctor == nil)
                        .bold()
                }
            }
            .interactiveDismissDisabled(isSubmitting)
        }
    }

    private func submit() async {
        guard let doc = selectedDoctor else { return }
        isSubmitting = true
        errorMsg = nil

        let dateFmt = DateFormatter()
        dateFmt.dateFormat = "yyyy-MM-dd"
        let timeFmt = DateFormatter()
        timeFmt.dateFormat = "HH:mm:ss"

        let username = KeychainHelper.shared.savedUsername ?? ""

        let req = BookAppointmentRequest(
            patientUsername: username.isEmpty ? nil : username,
            hospitalName: doc.hospitalName,
            hospitalId: doc.hospitalId,
            staffId: doc.staffId,
            staffEmail: doc.staffEmail,
            staffUsername: nil,
            departmentId: doc.departmentId,
            departmentName: nil,
            appointmentDate: dateFmt.string(from: appointmentDate),
            startTime: timeFmt.string(from: startTime),
            endTime: timeFmt.string(from: endTime),
            status: "SCHEDULED",
            reason: reason.isEmpty ? nil : reason,
            notes: notes.isEmpty ? nil : notes
        )

        let result = await vm.bookAppointment(req)
        if let err = result {
            errorMsg = err
        } else {
            isPresented = false
        }
        isSubmitting = false
    }
}
