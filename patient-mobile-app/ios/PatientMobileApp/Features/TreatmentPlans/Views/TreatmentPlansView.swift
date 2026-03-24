import SwiftUI

struct TreatmentPlansView: View {
    @StateObject private var viewModel = TreatmentPlansViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.plans.isEmpty {
                    HMSLoadingView(message: "Loading treatment plans...")
                } else if let error = viewModel.error, viewModel.plans.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadPlans(reset: true) } }
                } else if viewModel.plans.isEmpty {
                    HMSEmptyState(
                        icon: "list.clipboard",
                        title: "No Treatment Plans",
                        message: "You don't have any treatment plans yet."
                    )
                } else {
                    planList
                }
            }
            .navigationTitle("Treatment Plans")
            .refreshable { await viewModel.loadPlans(reset: true) }
            .task { await viewModel.loadPlans(reset: true) }
        }
    }

    private var planList: some View {
        List {
            ForEach(viewModel.plans) { plan in
                NavigationLink {
                    TreatmentPlanDetailView(plan: plan)
                } label: {
                    planRow(plan)
                }
            }
            if viewModel.hasMore {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .task { await viewModel.loadPlans() }
            }
        }
        .listStyle(.plain)
    }

    private func planRow(_ plan: TreatmentPlanDto) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(plan.planName ?? "Treatment Plan")
                    .font(.headline)
                Spacer()
                HMSStatusBadge(status: plan.status ?? "unknown")
            }
            if let diagnosis = plan.diagnosis {
                Text(diagnosis)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            HStack {
                if let doctor = plan.createdByName {
                    Label(doctor, systemImage: "stethoscope")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let start = plan.startDate {
                    Text(start.prefix(10))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let goals = plan.goals, !goals.isEmpty {
                let completed = goals.filter { $0.status?.lowercased() == "completed" }.count
                ProgressView(value: Double(completed), total: Double(goals.count))
                    .tint(.accentColor)
                Text("\(completed)/\(goals.count) goals completed")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Detail View

struct TreatmentPlanDetailView: View {
    let plan: TreatmentPlanDto

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(plan.planName ?? "Treatment Plan")
                                .font(.title2.bold())
                            Spacer()
                            HMSStatusBadge(status: plan.status ?? "unknown")
                        }
                        if let diagnosis = plan.diagnosis {
                            Label(diagnosis, systemImage: "heart.text.square")
                                .foregroundStyle(.secondary)
                        }
                        if let doctor = plan.createdByName {
                            Label(doctor, systemImage: "stethoscope")
                                .foregroundStyle(.secondary)
                        }
                        if let specialty = plan.createdBySpecialty {
                            Label(specialty, systemImage: "building.2")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                        HStack {
                            if let start = plan.startDate {
                                Label(String(start.prefix(10)), systemImage: "calendar")
                                    .font(.caption)
                            }
                            if let end = plan.endDate {
                                Label("to \(String(end.prefix(10)))", systemImage: "calendar.badge.checkmark")
                                    .font(.caption)
                            }
                        }
                        .foregroundStyle(.secondary)
                    }
                }

                if let desc = plan.description, !desc.isEmpty {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            HMSSectionHeader(title: "Description")
                            Text(desc)
                                .font(.body)
                        }
                    }
                }

                // Goals
                if let goals = plan.goals, !goals.isEmpty {
                    HMSSectionHeader(title: "Goals")
                    ForEach(goals) { goal in
                        HMSCard {
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Image(systemName: goal.status?.lowercased() == "completed" ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(goal.status?.lowercased() == "completed" ? .green : .secondary)
                                    Text(goal.goalDescription ?? "Goal")
                                        .font(.subheadline)
                                    Spacer()
                                    HMSStatusBadge(status: goal.status ?? "pending")
                                }
                                if let progress = goal.progressPercentage {
                                    ProgressView(value: Double(progress), total: 100)
                                        .tint(.accentColor)
                                    Text("\(progress)% complete")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                if let target = goal.targetDate {
                                    Label("Target: \(String(target.prefix(10)))", systemImage: "calendar")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                // Activities
                if let activities = plan.activities, !activities.isEmpty {
                    HMSSectionHeader(title: "Activities")
                    ForEach(activities) { activity in
                        HMSCard {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(activity.description ?? activity.activityType ?? "Activity")
                                        .font(.subheadline.bold())
                                    Spacer()
                                    HMSStatusBadge(status: activity.status ?? "pending")
                                }
                                if let freq = activity.frequency {
                                    Label(freq, systemImage: "repeat")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if let duration = activity.duration {
                                    Label(duration, systemImage: "clock")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if let assignee = activity.assignedTo {
                                    Label(assignee, systemImage: "person")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if let notes = plan.notes, !notes.isEmpty {
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            HMSSectionHeader(title: "Notes")
                            Text(notes).font(.body)
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Plan Details")
        .navigationBarTitleDisplayMode(.inline)
    }
}
