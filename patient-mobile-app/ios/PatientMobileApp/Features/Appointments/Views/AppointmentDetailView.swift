import SwiftUI

struct AppointmentDetailView: View {
    let appointment: AppointmentDTO
    @ObservedObject var viewModel: AppointmentViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showCancelAlert = false
    @State private var showRescheduleSheet = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Status header
                HMSCard {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(appointment.appointmentType ?? "Appointment")
                                .font(.hmsTitle3)
                                .foregroundColor(.hmsTextPrimary)
                            HMSStatusBadge(
                                text: appointment.status ?? "Unknown",
                                color: statusColor(appointment.status)
                            )
                        }
                        Spacer()
                        Image(systemName: "calendar.circle.fill")
                            .font(.system(size: 40))
                            .foregroundColor(.hmsPrimary)
                    }
                }

                // Details card
                HMSCard {
                    VStack(alignment: .leading, spacing: 12) {
                        DetailRow(icon: "calendar", label: "Date", value: appointment.displayDate)
                        DetailRow(icon: "clock", label: "Time", value: appointment.displayTime)
                        if let doctor = appointment.doctorName {
                            DetailRow(icon: "stethoscope", label: "Doctor", value: doctor)
                        }
                        if let dept = appointment.departmentName {
                            DetailRow(icon: "building.2", label: "Department", value: dept)
                        }
                        if let hospital = appointment.hospitalName {
                            DetailRow(icon: "cross.fill", label: "Hospital", value: hospital)
                        }
                        if let location = appointment.location {
                            DetailRow(icon: "mappin.and.ellipse", label: "Location", value: location)
                        }
                    }
                }

                // Reason & Notes
                if appointment.reason != nil || appointment.notes != nil {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 8) {
                            if let reason = appointment.reason {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Reason")
                                        .font(.hmsCaptionMedium)
                                        .foregroundColor(.hmsTextTertiary)
                                    Text(reason)
                                        .font(.hmsBody)
                                        .foregroundColor(.hmsTextPrimary)
                                }
                            }
                            if let notes = appointment.notes {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Notes")
                                        .font(.hmsCaptionMedium)
                                        .foregroundColor(.hmsTextTertiary)
                                    Text(notes)
                                        .font(.hmsBody)
                                        .foregroundColor(.hmsTextSecondary)
                                }
                            }
                        }
                    }
                }

                // Actions (only for upcoming)
                if isUpcoming {
                    VStack(spacing: 10) {
                        HMSPrimaryButton("Reschedule") {
                            showRescheduleSheet = true
                        }
                        Button("Cancel Appointment") {
                            showCancelAlert = true
                        }
                        .font(.hmsBodyMedium)
                        .foregroundColor(.hmsError)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Details")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Cancel Appointment", isPresented: $showCancelAlert) {
            Button("Keep", role: .cancel) {}
            Button("Cancel It", role: .destructive) {
                Task {
                    await viewModel.cancel(appointmentId: appointment.id)
                    dismiss()
                }
            }
        } message: {
            Text("Are you sure you want to cancel this appointment?")
        }
        .sheet(isPresented: $showRescheduleSheet) {
            RescheduleSheet(appointmentId: appointment.id, viewModel: viewModel)
        }
    }

    private var isUpcoming: Bool {
        let active = ["SCHEDULED", "CONFIRMED", "PENDING"]
        return active.contains(appointment.status?.uppercased() ?? "")
    }

    private func statusColor(_ status: String?) -> Color {
        switch status?.uppercased() {
        case "SCHEDULED", "CONFIRMED": return .hmsSuccess
        case "COMPLETED": return .hmsInfo
        case "CANCELLED", "NO_SHOW": return .hmsError
        case "PENDING": return .hmsWarning
        default: return .hmsTextSecondary
        }
    }
}

// MARK: - Detail Row

private struct DetailRow: View {
    let icon: String
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(.hmsPrimary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.hmsCaption)
                    .foregroundColor(.hmsTextTertiary)
                Text(value)
                    .font(.hmsBody)
                    .foregroundColor(.hmsTextPrimary)
            }
        }
    }
}

// MARK: - Reschedule Sheet

private struct RescheduleSheet: View {
    let appointmentId: String
    @ObservedObject var viewModel: AppointmentViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedDate = Date()

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Choose a new date and time")
                    .font(.hmsBody)
                    .foregroundColor(.hmsTextSecondary)

                DatePicker("", selection: $selectedDate, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                    .datePickerStyle(.graphical)
                    .tint(.hmsPrimary)

                HMSPrimaryButton("Confirm Reschedule") {
                    let formatter = DateFormatter()
                    formatter.dateFormat = "yyyy-MM-dd HH:mm"
                    let dateString = formatter.string(from: selectedDate)
                    Task {
                        await viewModel.reschedule(appointmentId: appointmentId, newDateTime: dateString)
                        dismiss()
                    }
                }
            }
            .padding(16)
            .navigationTitle("Reschedule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
