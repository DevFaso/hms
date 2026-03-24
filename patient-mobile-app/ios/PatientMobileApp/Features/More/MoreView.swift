import SwiftUI

/// "More" tab — contains links to features not in the bottom 4 tabs
struct MoreView: View {
    @EnvironmentObject var authManager: AuthManager

    var body: some View {
        NavigationStack {
            List {
                Section("Health") {
                    MoreMenuRow(icon: "pill.fill", title: "Medications", color: .hmsAccent) {
                        MedicationListView()
                    }
                    MoreMenuRow(icon: "flask.fill", title: "Lab Results", color: .hmsWarning) {
                        LabResultsView()
                    }
                    MoreMenuRow(icon: "heart.text.square.fill", title: "Vitals", color: .hmsSuccess) {
                        VitalsView()
                    }
                    MoreMenuRow(icon: "stethoscope", title: "Care Team", color: .hmsPrimary) {
                        CareTeamView()
                    }
                    MoreMenuRow(icon: "syringe.fill", title: "Immunizations", color: .hmsAccent) {
                        ImmunizationsView()
                    }
                    MoreMenuRow(icon: "list.clipboard.fill", title: "Treatment Plans", color: .hmsPrimary) {
                        TreatmentPlansView()
                    }
                    MoreMenuRow(icon: "arrow.triangle.branch", title: "Referrals", color: .hmsWarning) {
                        ReferralsView()
                    }
                    MoreMenuRow(icon: "person.2.circle.fill", title: "Consultations", color: .hmsInfo) {
                        ConsultationsView()
                    }
                    MoreMenuRow(icon: "doc.text.fill", title: "Documents", color: .hmsAccent) {
                        DocumentsView()
                    }
                }

                Section("Communication") {
                    MoreMenuRow(icon: "message.fill", title: "Messages", color: .hmsInfo) {
                        ConversationListView()
                    }
                    MoreMenuRow(icon: "bell.fill", title: "Notifications", color: .hmsError) {
                        NotificationListView()
                    }
                }

                Section("Account") {
                    MoreMenuRow(icon: "person.fill", title: "Profile", color: .hmsPrimary) {
                        ProfileView()
                    }
                    MoreMenuRow(icon: "gearshape.fill", title: "Settings", color: .hmsTextSecondary) {
                        SettingsView()
                    }
                    MoreMenuRow(icon: "lock.shield.fill", title: "Privacy & Sharing", color: .hmsAccent) {
                        ConsentsView()
                    }
                    MoreMenuRow(icon: "eye.fill", title: "Access Log", color: .hmsWarning) {
                        AccessLogView()
                    }
                    MoreMenuRow(icon: "questionmark.circle.fill", title: "Help & Support", color: .hmsTextTertiary) {
                        HMSEmptyState(icon: "questionmark.circle", title: "Help", message: "Help center coming soon.")
                    }
                }
            }
            .navigationTitle("More")
        }
    }
}

private struct MoreMenuRow<Destination: View>: View {
    let icon: String
    let title: String
    let color: Color
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink(destination: destination) {
            Label {
                Text(title)
                    .font(.hmsBody)
                    .foregroundColor(.hmsTextPrimary)
            } icon: {
                Image(systemName: icon)
                    .foregroundColor(color)
            }
        }
    }
}
