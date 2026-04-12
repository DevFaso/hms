import SwiftUI

struct DashboardView: View {
    @Binding var showMenu: Bool
    @StateObject private var vm = DashboardViewModel()
    @EnvironmentObject var authManager: AuthManager
    @EnvironmentObject var localization: LocalizationManager
    @State private var navigateTo: DashboardDestination?
    @State private var selectedLabResult: LabResultDTO?

    enum DashboardDestination: Hashable {
        case appointments, labResults, medications, billing
        case careTeam, vitals, visits, visitSummaries, documents
        case notifications, healthRecords
        case familyAccess, sharingPrivacy
    }

    private var quickLinks: [(titleKey: String, icon: String, color: Color, dest: DashboardDestination)] {
        [
            ("tab_appointments", "calendar", .green, .appointments),
            ("test_results", "testtube.2", .purple, .labResults),
            ("medications_title", "pill.fill", .teal, .medications),
            ("billing_title", "creditcard.fill", .orange, .billing),
            ("care_team_title", "person.2.fill", .pink, .careTeam),
            ("vitals_title", "heart.fill", .red, .vitals),
            ("visits_title", "building.2.fill", .indigo, .visits),
            ("visit_summaries_title", "doc.text.fill", .brown, .visitSummaries),
            ("documents", "doc.fill", .blue, .documents),
            ("family_access", "person.2.circle", .cyan, .familyAccess),
            ("sharing_privacy", "lock.shield", .mint, .sharingPrivacy),
        ]
    }

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 24) {
                    // MARK: Quick links grid

                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12),
                                        GridItem(.flexible(), spacing: 12),
                                        GridItem(.flexible(), spacing: 12),
                                        GridItem(.flexible(), spacing: 12)],
                              spacing: 14)
                    {
                        ForEach(quickLinks, id: \.titleKey) { link in
                            NavigationLink(value: link.dest) {
                                VStack(spacing: 10) {
                                    Image(systemName: link.icon)
                                        .font(.system(size: 22, weight: .medium))
                                        .foregroundStyle(.white)
                                        .frame(width: 50, height: 50)
                                        .background(
                                            LinearGradient(
                                                colors: [link.color, link.color.opacity(0.7)],
                                                startPoint: .topLeading,
                                                endPoint: .bottomTrailing
                                            )
                                        )
                                        .clipShape(RoundedRectangle(cornerRadius: 14))
                                        .shadow(color: link.color.opacity(0.25), radius: 4, y: 2)
                                    Text(link.titleKey.localized)
                                        .font(.system(size: 11, weight: .medium))
                                        .foregroundStyle(.primary)
                                        .multilineTextAlignment(.center)
                                        .lineLimit(2)
                                }
                            }
                        }
                    }
                    .padding(.horizontal)

                    // MARK: Upcoming appointments

                    if !vm.upcomingAppointments.isEmpty {
                        SectionCard(title: "upcoming_appointments".localized, icon: "calendar") {
                            ForEach(vm.upcomingAppointments) { appt in
                                NavigationLink(destination: AppointmentDetailView(appointment: appt)) {
                                    AppointmentRowView(appointment: appt)
                                }
                                .buttonStyle(.plain)
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
                        SectionCard(title: "recent_lab_results".localized, icon: "testtube.2") {
                            ForEach(vm.labResults.prefix(3)) { lab in
                                Button { selectedLabResult = lab } label: {
                                    LabResultRowView(result: lab)
                                }
                                .buttonStyle(.plain)
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
                        SectionCard(title: "active_conditions".localized, icon: "stethoscope") {
                            ForEach(conditions, id: \.self) { condition in
                                Label(condition, systemImage: "circle.fill")
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                    .padding(.vertical, 2)
                            }
                        }
                        .padding(.horizontal)
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle("tab_dashboard".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        withAnimation(.spring(response: 0.3)) { showMenu.toggle() }
                    } label: {
                        Image(systemName: "line.3.horizontal")
                            .font(.system(size: 18, weight: .medium))
                            .foregroundStyle(.primary)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { Task { await vm.loadAll() } }) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .medium))
                    }
                }
            }
            .refreshable { await vm.loadAll() }
            .sheet(item: $selectedLabResult) { result in
                LabResultDetailSheet(result: result)
            }
            .navigationDestination(for: DashboardDestination.self) { dest in
                switch dest {
                case .appointments: AppointmentsView(embeddedInNav: false)
                case .labResults: LabResultsView(embeddedInNav: false)
                case .medications: MedicationsView(embeddedInNav: false)
                case .billing: BillingView(embeddedInNav: false)
                case .careTeam: CareTeamView(embeddedInNav: false)
                case .vitals: VitalsView(embeddedInNav: false)
                case .visits: VisitHistoryView(embeddedInNav: false)
                case .visitSummaries: VisitSummariesView(embeddedInNav: false)
                case .documents: DocumentsView(embeddedInNav: false)
                case .notifications: NotificationsView(embeddedInNav: false)
                case .healthRecords: HealthRecordsView(embeddedInNav: false)
                case .familyAccess: FamilyAccessView(embeddedInNav: false)
                case .sharingPrivacy: SharingPrivacyView(embeddedInNav: false)
                }
            }
            .overlay {
                if vm.isLoading, vm.healthSummary == nil {
                    VStack(spacing: 12) {
                        ProgressView()
                            .controlSize(.large)
                        Text("loading".localized)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(28)
                    .background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
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
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color("BrandBlue"))
                Text(title)
                    .font(.headline)
            }
            content
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

// MARK: - Appointment row (reused across views)

struct AppointmentRowView: View {
    let appointment: AppointmentDTO
    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 4)
                .fill(appointment.statusColor == "green" ? Color.green : Color("BrandBlue"))
                .frame(width: 4, height: 48)
            VStack(alignment: .leading, spacing: 4) {
                Text(appointment.staffName ?? "Doctor")
                    .font(.subheadline.weight(.semibold))
                Text(appointment.hospitalName ?? "")
                    .font(.caption).foregroundStyle(.secondary)
                if let date = appointment.appointmentDate {
                    HStack(spacing: 4) {
                        Image(systemName: "calendar")
                            .font(.system(size: 9)).foregroundStyle(.secondary)
                        Text(date).font(.caption2).foregroundStyle(.secondary)
                        if let time = appointment.timeRange {
                            Text("·").foregroundStyle(.secondary)
                            Image(systemName: "clock")
                                .font(.system(size: 9)).foregroundStyle(.secondary)
                            Text(time).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
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
            Image(systemName: result.abnormal ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                .foregroundStyle(result.abnormal ? .red : .green)
                .font(.subheadline)
            VStack(alignment: .leading, spacing: 2) {
                Text(result.testName ?? "Test").font(.subheadline.weight(.semibold))
                Text(result.collectedDate ?? result.resultDate ?? result.orderedDate ?? "")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(result.result ?? "—").font(.subheadline)
                if result.isCritical {
                    Text("CRITICAL").font(.system(size: 9, weight: .bold)).foregroundStyle(.red)
                } else if result.abnormal {
                    Text("ABNORMAL").font(.system(size: 9, weight: .bold)).foregroundStyle(.orange)
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
        case "green": .green
        case "red": .red
        case "yellow": .yellow
        case "orange": .orange
        case "gray": .gray
        default: .blue
        }
    }

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(swiftColor.opacity(0.12))
            .foregroundStyle(swiftColor)
            .clipShape(Capsule())
    }
}

#Preview {
    DashboardView(showMenu: .constant(false))
        .environmentObject(AuthManager.shared)
        .environmentObject(LocalizationManager.shared)
}
