import SwiftUI

struct HomeView: View {
    @EnvironmentObject var authManager: AuthManager
    @StateObject private var viewModel = HomeViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    // Greeting
                    HMSCard {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Welcome back,")
                                    .font(.hmsCaption)
                                    .foregroundColor(.hmsTextSecondary)
                                Text(authManager.currentUser?.displayName ?? "Patient")
                                    .font(.hmsHeadline)
                                    .foregroundColor(.hmsTextPrimary)
                            }
                            Spacer()
                            Image(systemName: "person.circle.fill")
                                .font(.system(size: 44))
                                .foregroundColor(.hmsPrimary)
                        }
                    }

                    // Quick Actions
                    HMSSectionHeader(title: "Quick Actions")

                    LazyVGrid(columns: [
                        GridItem(.flexible()),
                        GridItem(.flexible()),
                    ], spacing: 12) {
                        QuickActionCard(icon: "calendar.badge.plus", title: "Book\nAppointment", color: .hmsPrimary)
                        QuickActionCard(icon: "pill", title: "My\nMedications", color: .hmsAccent)
                        QuickActionCard(icon: "flask", title: "Lab\nResults", color: .hmsWarning)
                        QuickActionCard(icon: "message", title: "Messages", color: .hmsInfo)
                    }

                    // Health Summary
                    if let summary = viewModel.healthSummary {
                        HMSSectionHeader(title: "Health Summary")

                        HMSCard {
                            VStack(spacing: 12) {
                                SummaryRow(label: "Upcoming Appointments", value: "\(summary.upcomingAppointments)")
                                Divider()
                                SummaryRow(label: "Active Medications", value: "\(summary.activeMedications)")
                                Divider()
                                SummaryRow(label: "Pending Lab Results", value: "\(summary.pendingLabResults)")
                                Divider()
                                SummaryRow(label: "Unread Messages", value: "\(summary.unreadMessages)")
                            }
                        }
                    }

                    // Recent Activity
                    HMSSectionHeader(title: "Recent Activity")

                    if viewModel.isLoading {
                        HMSLoadingView(message: "Loading your health summary...")
                    } else if let error = viewModel.errorMessage {
                        HMSErrorView(message: error) {
                            Task { await viewModel.load() }
                        }
                    } else {
                        HMSCard {
                            VStack(spacing: 8) {
                                ForEach(viewModel.recentActivity, id: \.id) { item in
                                    ActivityRow(item: item)
                                    if item.id != viewModel.recentActivity.last?.id {
                                        Divider()
                                    }
                                }

                                if viewModel.recentActivity.isEmpty {
                                    Text("No recent activity")
                                        .font(.hmsCaption)
                                        .foregroundColor(.hmsTextTertiary)
                                        .padding(.vertical, 12)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Color.hmsBackground)
            .navigationTitle("Home")
            .refreshable {
                await viewModel.load()
            }
            .task {
                await viewModel.load()
            }
        }
    }
}

// MARK: - Sub-views

private struct QuickActionCard: View {
    let icon: String
    let title: String
    let color: Color

    var body: some View {
        HMSCard {
            VStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 28))
                    .foregroundColor(color)
                Text(title)
                    .font(.hmsCaptionMedium)
                    .foregroundColor(.hmsTextPrimary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }
}

private struct SummaryRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.hmsBody)
                .foregroundColor(.hmsTextSecondary)
            Spacer()
            Text(value)
                .font(.hmsBodyBold)
                .foregroundColor(.hmsTextPrimary)
        }
    }
}

private struct ActivityRow: View {
    let item: ActivityItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.icon)
                .font(.system(size: 20))
                .foregroundColor(.hmsPrimary)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsTextPrimary)
                Text(item.subtitle)
                    .font(.hmsCaption)
                    .foregroundColor(.hmsTextSecondary)
            }
            Spacer()
            Text(item.timeAgo)
                .font(.hmsCaption)
                .foregroundColor(.hmsTextTertiary)
        }
        .padding(.vertical, 4)
    }
}
