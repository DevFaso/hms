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
    @Published var booking = BookingOptions()
    private var hospitalsTask: Task<Void, Never>?
    private var departmentsTask: Task<Void, Never>?
    private var providersTask: Task<Void, Never>?

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

    // MARK: Booking wizard

    /// Opens on a fresh wizard with the hospitals loading. A booking still in
    /// flight keeps its flag so a second submit is refused until it answers.
    func openBooking() {
        departmentsTask?.cancel()
        providersTask?.cancel()
        booking = BookingOptions(isBooking: booking.isBooking)
        loadBookingHospitals()
    }

    func loadBookingHospitals() {
        hospitalsTask?.cancel()
        booking.loading = .hospitals
        booking.loadError = nil
        hospitalsTask = Task {
            do {
                let list: [BookingHospitalDTO] = try await APIClient.shared.get(APIEndpoints.bookingHospitals)
                guard !Task.isCancelled else { return }
                booking.hospitals = list
                booking.hospitalsLoaded = true
                booking.loading = nil
            } catch {
                guard !Task.isCancelled else { return }
                booking.loading = nil
                booking.loadError = .hospitals
            }
        }
    }

    /// A new hospital empties the two levels below it and cancels any load
    /// still in flight for the old one, so a slow answer cannot land on the
    /// new choice.
    /// An empty id is the placeholder: the levels below are emptied and nothing loads.
    func selectBookingHospital(_ hospitalId: String) {
        departmentsTask?.cancel()
        providersTask?.cancel()
        booking.departments = []
        booking.providers = []
        booking.departmentsLoaded = false
        booking.providersLoaded = false
        booking.loading = nil
        booking.loadError = nil
        booking.bookingError = nil
        guard !hospitalId.isEmpty else { return }
        booking.loading = .departments
        departmentsTask = Task {
            do {
                let list: [BookingDepartmentDTO] = try await APIClient.shared.get(
                    APIEndpoints.bookingDepartments(hospitalId: hospitalId))
                guard !Task.isCancelled else { return }
                booking.departments = list
                booking.departmentsLoaded = true
                booking.loading = nil
            } catch {
                guard !Task.isCancelled else { return }
                booking.loading = nil
                booking.loadError = .departments
            }
        }
    }

    func selectBookingDepartment(hospitalId: String, departmentId: String) {
        providersTask?.cancel()
        booking.providers = []
        booking.providersLoaded = false
        booking.loading = nil
        booking.loadError = nil
        booking.bookingError = nil
        guard !hospitalId.isEmpty, !departmentId.isEmpty else { return }
        booking.loading = .providers
        providersTask = Task {
            do {
                let list: [BookingProviderDTO] = try await APIClient.shared.get(
                    APIEndpoints.bookingProviders(hospitalId: hospitalId, departmentId: departmentId))
                guard !Task.isCancelled else { return }
                booking.providers = list
                booking.providersLoaded = true
                booking.loading = nil
            } catch {
                guard !Task.isCancelled else { return }
                booking.loading = nil
                booking.loadError = .providers
            }
        }
    }

    /// A changed provider, date or time makes the last refusal stale (a conflict is for one slot).
    func clearBookingError() {
        booking.bookingError = nil
    }

    /// True when booked; otherwise the error is shown inline in the sheet.
    /// Runs as its own task on the view model, not the sheet, so dismissing
    /// the sheet cannot abandon a request the server may already honour.
    func book(_ request: BookAppointmentRequest) async -> Bool {
        guard !booking.isBooking else { return false }
        booking.isBooking = true
        booking.bookingError = nil
        let task = Task<Bool, Never> {
            do {
                let _: AppointmentDTO = try await APIClient.shared.post(APIEndpoints.bookAppointment, body: request)
                return true
            } catch {
                booking.bookingError = "booking_failed".localized + ": " + error.localizedDescription
                return false
            }
        }
        let booked = await task.value
        // The flag stays up through the refresh: Book (and swipe) coming back
        // while the list refetches let the same visit be posted twice.
        if booked { await load() }
        booking.isBooking = false
        return booked
    }
}

/// Which of the wizard's three lists is loading or failed, so the sheet can show and retry just that one.
enum BookingStep { case hospitals, departments, providers }

/// The wizard's data, hospital -> department -> provider, each list loaded
/// when the level above it is chosen (the web's flow). The selection itself
/// lives in the sheet; this holds what the server returned for it.
struct BookingOptions {
    var hospitals: [BookingHospitalDTO] = []
    var departments: [BookingDepartmentDTO] = []
    var providers: [BookingProviderDTO] = []
    var hospitalsLoaded = false
    var departmentsLoaded = false
    var providersLoaded = false
    var loading: BookingStep?
    var loadError: BookingStep?
    var isBooking = false
    var bookingError: String?
}

// MARK: - Book Appointment Sheet

/// The web's booking wizard: hospital (where the patient is registered) ->
/// department -> provider (optional, "any available" by default) -> date and
/// start time -> reason and notes. A first-time patient with no visit
/// history can book from here; the old sheet only listed doctors from past
/// appointments.
struct BookAppointmentSheet: View {
    @ObservedObject var vm: AppointmentsViewModel
    @Binding var isPresented: Bool
    @State private var hospitalId = ""     // "" = none chosen
    @State private var departmentId = ""
    @State private var staffId = ""        // "" = any available provider
    /// Tomorrow at 09:00 by default: today with a fixed morning start would
    /// open the sheet on "already passed" for most of the day.
    @State private var appointmentDate = Calendar.current.date(byAdding: .day, value: 1, to: Date()) ?? Date()
    @State private var startTime: Date = {
        var comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        comps.hour = 9; comps.minute = 0
        return Calendar.current.date(from: comps) ?? Date()
    }()
    @State private var reason = ""
    @State private var notes = ""
    /// Bumped by a Book tap so the "already passed" guard is re-read at tap time.
    @State private var tapClock = 0

    private static let reasonMax = 500
    private static let notesMax = 1000
    /// The backend defaults the slot to 30 minutes; the same-day rule is checked here with it.
    private static let slotMinutes = 30

    private var options: BookingOptions { vm.booking }
    private var hospital: BookingHospitalDTO? { options.hospitals.first { $0.id == hospitalId } }

    /// The requested start as an instant in the patient's calendar.
    private var startDateTime: Date? {
        let time = Calendar.current.dateComponents([.hour, .minute], from: startTime)
        return Calendar.current.date(bySettingHour: time.hour ?? 0, minute: time.minute ?? 0, second: 0, of: appointmentDate)
    }

    private var timeError: String? {
        _ = tapClock
        let time = Calendar.current.dateComponents([.hour, .minute], from: startTime)
        // The server ends the slot 30 minutes after the start on the SAME date;
        // a start after 23:30 would end before it began and be refused.
        if (time.hour ?? 0) * 60 + (time.minute ?? 0) + Self.slotMinutes >= 24 * 60 {
            return "crosses_midnight".localized
        }
        // The server only requires the date to be today or later, so today
        // with a start already gone by (or a date that turned into yesterday
        // while the sheet stayed open) would book a visit in the past.
        if let start = startDateTime, start <= Date() {
            return "time_already_passed".localized
        }
        return nil
    }

    private var canSubmit: Bool {
        !hospitalId.isEmpty && !departmentId.isEmpty &&
            options.providersLoaded && !options.providers.isEmpty &&
            timeError == nil && !options.isBooking &&
            reason.utf16.count <= Self.reasonMax && notes.utf16.count <= Self.notesMax
    }

    var body: some View {
        NavigationStack {
            Form {
                if let step = options.loadError {
                    // A failed list is an error with a retry for that step, never a "no hospitals".
                    Section {
                        Text("booking_options_failed".localized).foregroundColor(.red)
                        Button("retry".localized) { retry(step) }
                    }
                } else if options.loading == .hospitals {
                    Section {
                        HStack { ProgressView(); Text("loading".localized).foregroundColor(.secondary) }
                    }
                } else if options.hospitalsLoaded, options.hospitals.isEmpty {
                    Section {
                        Text("no_hospitals_for_booking".localized).foregroundColor(.secondary).font(.callout)
                    }
                }

                if options.hospitalsLoaded, !options.hospitals.isEmpty {
                    // 1. Hospital
                    Section("hospital".localized) {
                        Picker("hospital".localized, selection: $hospitalId) {
                            Text("select_hospital".localized).tag("")
                            ForEach(options.hospitals) { h in
                                Text(h.name ?? h.id).tag(h.id)
                            }
                        }
                        .onChange(of: hospitalId) { _, newValue in
                            departmentId = ""
                            staffId = ""
                            vm.selectBookingHospital(newValue)
                        }
                        if let address = hospital?.address, !address.isEmpty {
                            Text(address).font(.caption).foregroundColor(.secondary)
                        }
                    }

                    // 2. Department
                    Section("department".localized) {
                        if options.loading == .departments {
                            HStack { ProgressView(); Text("loading".localized).foregroundColor(.secondary) }
                        } else if options.departmentsLoaded, options.departments.isEmpty {
                            Text("no_departments_for_booking".localized).foregroundColor(.secondary).font(.callout)
                        } else if options.departmentsLoaded {
                            Picker("department".localized, selection: $departmentId) {
                                Text("select_department".localized).tag("")
                                ForEach(options.departments) { d in
                                    Text(d.name ?? d.id).tag(d.id)
                                }
                            }
                            .onChange(of: departmentId) { _, newValue in
                                staffId = ""
                                vm.selectBookingDepartment(hospitalId: hospitalId, departmentId: newValue)
                            }
                        } else {
                            Text("choose_hospital_first".localized).foregroundColor(.secondary).font(.callout)
                        }
                    }

                    // 3. Provider, optional: the server assigns one when none is chosen.
                    Section("provider_optional".localized) {
                        if options.loading == .providers {
                            HStack { ProgressView(); Text("loading".localized).foregroundColor(.secondary) }
                        } else if options.providersLoaded, options.providers.isEmpty {
                            Text("no_providers_for_booking".localized).foregroundColor(.secondary).font(.callout)
                        } else if options.providersLoaded {
                            Picker("provider".localized, selection: $staffId) {
                                Text("any_provider".localized).tag("")
                                ForEach(options.providers) { p in
                                    Text(p.displayName).tag(p.id)
                                }
                            }
                            .onChange(of: staffId) { _, _ in vm.clearBookingError() }
                        } else {
                            Text("choose_department_first".localized).foregroundColor(.secondary).font(.callout)
                        }
                    }

                    // 4. Date and start time
                    Section("schedule".localized) {
                        DatePicker("date".localized, selection: $appointmentDate,
                                   in: Calendar.current.startOfDay(for: Date())..., displayedComponents: .date)
                            .onChange(of: appointmentDate) { _, _ in vm.clearBookingError() }
                        DatePicker("start_time".localized, selection: $startTime, displayedComponents: .hourAndMinute)
                            .onChange(of: startTime) { _, _ in vm.clearBookingError() }
                        if let timeError {
                            Text(timeError).font(.caption).foregroundColor(.red)
                        }
                    }

                    // 5. Reason and notes, with the server's caps
                    Section("visit_details".localized) {
                        TextField("reason_for_visit".localized, text: $reason, axis: .vertical)
                            .lineLimit(2 ... 4)
                        // UTF-16 units, which is what the server's @Size counts.
                        Text("\(reason.utf16.count)/\(Self.reasonMax)")
                            .font(.caption2)
                            .foregroundColor(reason.utf16.count > Self.reasonMax ? .red : .secondary)
                        TextField("additional_notes".localized, text: $notes, axis: .vertical)
                            .lineLimit(2 ... 4)
                        Text("\(notes.utf16.count)/\(Self.notesMax)")
                            .font(.caption2)
                            .foregroundColor(notes.utf16.count > Self.notesMax ? .red : .secondary)
                    }

                    if let err = options.bookingError {
                        Section {
                            Text(err).foregroundColor(.red).font(.caption)
                        }
                    }
                }
            }
            .navigationTitle("book_appointment".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { isPresented = false }
                        .disabled(options.isBooking)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if options.isBooking {
                        ProgressView()
                    } else {
                        Button("book".localized) { Task { await submit() } }
                            .disabled(!canSubmit)
                            .bold()
                    }
                }
            }
            // While the request is out, the sheet stays: a swipe would otherwise
            // let the patient reopen and submit the same visit twice.
            .interactiveDismissDisabled(options.isBooking)
            .task { vm.openBooking() }
        }
    }

    private func retry(_ step: BookingStep) {
        switch step {
        case .hospitals: vm.loadBookingHospitals()
        case .departments: if !hospitalId.isEmpty { vm.selectBookingHospital(hospitalId) }
        case .providers:
            if !hospitalId.isEmpty, !departmentId.isEmpty {
                vm.selectBookingDepartment(hospitalId: hospitalId, departmentId: departmentId)
            }
        }
    }

    private func submit() async {
        // Re-read the time guard at tap time: a form left alone for minutes never re-renders.
        tapClock += 1
        guard canSubmit else { return }

        // Fixed calendar and locale so the wire format does not follow the device's.
        let dateFmt = DateFormatter()
        dateFmt.calendar = Calendar(identifier: .iso8601)
        dateFmt.locale = Locale(identifier: "en_US_POSIX")
        dateFmt.dateFormat = "yyyy-MM-dd"
        let time = Calendar.current.dateComponents([.hour, .minute], from: startTime)

        let trimmedReason = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let req = BookAppointmentRequest(
            hospitalId: hospitalId,
            departmentId: departmentId,
            staffId: staffId.isEmpty ? nil : staffId,
            date: dateFmt.string(from: appointmentDate),
            startTime: String(format: "%02d:%02d", time.hour ?? 0, time.minute ?? 0),
            endTime: nil,
            reason: trimmedReason.isEmpty ? nil : trimmedReason,
            notes: trimmedNotes.isEmpty ? nil : trimmedNotes
        )

        if await vm.book(req) {
            isPresented = false
        }
    }
}
