import SwiftUI

struct DashboardView: View {
    @StateObject private var vm = DashboardViewModel()
    @EnvironmentObject var authManager: AuthManager
    @State private var navigateTo: DashboardDestination?

    enum DashboardDestination: Hashable {
        case appointments, labResults, medications, billing
        case careTeam, vitals, visits, documents
        case notifications, healthRecords
    }

    private let quickLinks: [(title: String, icon: String, color: Color, dest: DashboardDestination)] = [
        ("Appointments", "calendar",        .green,  .appointments),
        ("Test Results", "testtube.2",      .purple, .labResults),
        ("Medications",  "pill.fill",       .teal,   .medications),
        ("Billing",      "creditcard.fill", .orange, .billing),
        ("Care Team",    "person.2.fill",   .pink,   .careTeam),
        ("Vitals",       "heart.fill",      .red,    .vitals),
        ("Visits",       "building.2.fill", .indigo, .visits),
        ("Documents",    "doc.fill",        .blue,   .documents),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {

                    // MARK: Welcome header
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Welcome, \(authManager.currentUser?.firstName ?? "Patient")!")
                                .font(.title2).bold()
                            Text(Date.now.formatted(.dateTime.weekday(.wide).month().day()))
                                .font(.subheadline).foregroundColor(.secondary)
                        }
                        Spacer()
                        if vm.unreadNotificationCount > 0 {
                            NavigationLink(value: DashboardDestination.notifications) {
                                ZStack(alignment: .topTrailing) {
                                    Image(systemName: "bell.fill")
                                        .font(.title2)
                                        .foregroundColor(.accentColor)
                                    Text("\(vm.unreadNotificationCount)")
                                        .font(.caption2).bold()
                                        .foregroundColor(.white)
                                        .padding(4)
                                        .background(Color.red)
                                        .clipShape(Circle())
                                        .offset(x: 6, y: -6)
                                }
                            }
                        }
                    }
                    .padding(.horizontal)

                    // MARK: Health alerts
                    if let allergies = vm.healthSummary?.allergies, !allergies.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(allergies, id: \.self) { allergy in
                                    Label(allergy, systemImage: "exclamationmark.triangle.fill")
                                        .font(.caption).bold()
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(Color.red.opacity(0.1))
                                        .foregroundColor(.red)
                                        .cornerRadius(20)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }

                    // MARK: Quick links grid
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()),
                                        GridItem(.flexible()), GridItem(.flexible())],
                              spacing: 12) {
                        ForEach(quickLinks, id: \.title) { link in
                            NavigationLink(value: link.dest) {
                                VStack(spacing: 8) {
                                    Image(systemName: link.icon)
                                        .font(.title2)
                                        .foregroundColor(.white)
                                        .frame(width: 52, height: 52)
                                        .background(link.color)
                                        .cornerRadius(14)
                                    Text(link.title)
                                        .font(.caption)
                                        .foregroundColor(.primary)
                                        .multilineTextAlignment(.center)
                                }
                            }
                        }
                    }
                    .padding(.horizontal)

                    // MARK: Upcoming appointments
                    if !vm.upcomingAppointments.isEmpty {
                        SectionCard(title: "Upcoming Appointments", icon: "calendar") {
                            ForEach(vm.upcomingAppointments) { appt in
                                AppointmentRowView(appointment: appt)
                                    .padding(.vertical, 4)
                                if appt.id != vm.upcomingAppointments.last?.id {
                                    Divider()
                                }
                            }
                        }
                        .padding(.horizontal)
                    }

                    // MARK: Recent lab results
                    if !vm.labResults.isEmpty {
                        SectionCard(title: "Recent Lab Results", icon: "testtube.2") {
                            ForEach(vm.labResults.prefix(3)) { lab in
                                LabResultRowView(result: lab)
                                    .padding(.vertical, 4)
                                if lab.id != vm.labResults.prefix(3).last?.id {
                                    Divider()
                                }
                            }
                        }
                        .padding(.horizontal)
                    }

                    // MARK: Active conditions
                    if let conditions = vm.healthSummary?.activeDiagnoses, !conditions.isEmpty {
                        SectionCard(title: "Active Conditions", icon: "stethoscope") {
                            ForEach(conditions, id: \.self) { condition in
                                Label(condition, systemImage: "circle.fill")
                                    .font(.subheadline)
                                    .foregroundColor(.primary)
                                    .padding(.vertical, 2)
                            }
                        }
                        .padding(.horizontal)
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle("Dashboard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { Task { await vm.loadAll() } }) {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .refreshable { await vm.loadAll() }
            .navigationDestination(for: DashboardDestination.self) { dest in
                switch dest {
                case .appointments:  AppointmentsView()
                case .labResults:    LabResultsView()
                case .medications:   MedicationsView()
                case .billing:       BillingView()
                case .careTeam:      CareTeamView()
                case .vitals:        VitalsView()
                case .visits:        VisitHistoryView()
                case .documents:     DocumentsView()
                case .notifications: NotificationsView()
                case .healthRecords: HealthRecordsView()
                }
            }
            .overlay {
                if vm.isLoading && vm.healthSummary == nil {
                    ProgressView("Loading…")
                        .padding(24)
                        .background(.regularMaterial)
                        .cornerRadius(16)
                }
            }
        }
        .task { await vm.loadAll() }
    }
}

// MARK: - Reusable section card

struct SectionCard<Content: View>: View {
    let title: String
    let icon: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: icon)
                .font(.headline)
            content
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
    }
}

// MARK: - Appointment row (reused across views)

struct AppointmentRowView: View {
    let appointment: AppointmentDTO
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6)
                .fill(appointment.statusColor == "green" ? Color.green : Color.blue)
                .frame(width: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(appointment.doctorName ?? "Doctor")
                    .font(.subheadline).bold()
                Text(appointment.departmentName ?? appointment.type ?? "")
                    .font(.caption).foregroundColor(.secondary)
                if let date = appointment.appointmentDate {
                    Text(date).font(.caption2).foregroundColor(.secondary)
                }
            }
            Spacer()
            StatusBadge(text: appointment.statusDisplay, color: appointment.statusColor)
        }
    }
}

// MARK: - Lab result row

struct LabResultRowView: View {
    let result: LabResultDTO
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(result.testName ?? "Test").font(.subheadline).bold()
                Text(result.resultDate ?? result.orderedDate ?? "")
                    .font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(result.result ?? "—").font(.subheadline)
                if result.isCritical {
                    Text("CRITICAL").font(.caption2).bold().foregroundColor(.red)
                } else if result.isAbnormal {
                    Text("ABNORMAL").font(.caption2).bold().foregroundColor(.orange)
                }
            }
        }
    }
}

// MARK: - Status badge

struct StatusBadge: View {
    let text: String
    let color: String

    private var swiftColor: Color {
        switch color {
        case "green":  return .green
        case "red":    return .red
        case "yellow": return .yellow
        case "orange": return .orange
        case "gray":   return .gray
        default:       return .blue
        }
    }

    var body: some View {
        Text(text)
            .font(.caption2).bold()
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(swiftColor.opacity(0.15))
            .foregroundColor(swiftColor)
            .cornerRadius(8)
    }
}

#Preview {
    DashboardView()
        .environmentObject(AuthManager.shared)
}
