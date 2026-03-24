import SwiftUI

struct AppointmentListView: View {
    @StateObject private var viewModel = AppointmentViewModel()
    @State private var selectedTab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Segmented control
                Picker("", selection: $selectedTab) {
                    Text("Upcoming").tag(0)
                    Text("Past").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.top, 8)

                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading appointments...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.load() } }
                } else {
                    let appointments = selectedTab == 0 ? viewModel.upcoming : viewModel.past
                    if appointments.isEmpty {
                        HMSEmptyState(
                            icon: selectedTab == 0 ? "calendar.badge.clock" : "clock.arrow.circlepath",
                            title: selectedTab == 0 ? "No Upcoming Appointments" : "No Past Appointments",
                            message: selectedTab == 0
                                ? "You don't have any scheduled appointments."
                                : "Your appointment history will appear here."
                        )
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 12) {
                                ForEach(appointments) { apt in
                                    NavigationLink(destination: AppointmentDetailView(
                                        appointment: apt,
                                        viewModel: viewModel
                                    )) {
                                        AppointmentCard(appointment: apt)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(16)
                        }
                    }
                }
            }
            .navigationTitle("Appointments")
            .refreshable { await viewModel.load() }
            .task { await viewModel.load() }
        }
    }
}

// MARK: - Appointment Card

private struct AppointmentCard: View {
    let appointment: AppointmentDTO

    var body: some View {
        HMSCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(appointment.appointmentType ?? "Appointment")
                            .font(.hmsBodyMedium)
                            .foregroundColor(.hmsTextPrimary)
                        if let doctor = appointment.doctorName {
                            Text(doctor)
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextSecondary)
                        }
                    }
                    Spacer()
                    HMSStatusBadge(
                        text: appointment.status ?? "Unknown",
                        color: statusColor(appointment.status)
                    )
                }

                Divider()

                HStack(spacing: 16) {
                    Label(appointment.displayDate, systemImage: "calendar")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                    Label(appointment.displayTime, systemImage: "clock")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextSecondary)
                }

                if let dept = appointment.departmentName {
                    Label(dept, systemImage: "building.2")
                        .font(.hmsCaption)
                        .foregroundColor(.hmsTextTertiary)
                }
            }
        }
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
