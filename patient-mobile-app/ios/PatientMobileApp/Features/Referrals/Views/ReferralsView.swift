import SwiftUI

struct ReferralsView: View {
    @StateObject private var viewModel = ReferralsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.referrals.isEmpty {
                    HMSLoadingView(message: "Loading referrals...")
                } else if let error = viewModel.error, viewModel.referrals.isEmpty {
                    HMSErrorView(message: error) { Task { await viewModel.loadReferrals() } }
                } else if viewModel.referrals.isEmpty {
                    HMSEmptyState(
                        icon: "arrow.triangle.branch",
                        title: "No Referrals",
                        message: "You don't have any referrals."
                    )
                } else {
                    referralList
                }
            }
            .navigationTitle("Referrals")
            .refreshable { await viewModel.loadReferrals() }
            .task { await viewModel.loadReferrals() }
        }
    }

    private var referralList: some View {
        List(viewModel.referrals) { referral in
            NavigationLink {
                ReferralDetailView(referral: referral)
            } label: {
                referralRow(referral)
            }
        }
        .listStyle(.plain)
    }

    private func referralRow(_ referral: ReferralDto) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: viewModel.urgencyIcon(referral.urgency))
                    .foregroundStyle(urgencyColor(referral.urgency))
                Text(referral.referralReason ?? referral.referralType ?? "Referral")
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                HMSStatusBadge(status: referral.status ?? "unknown")
            }
            if let toDoctor = referral.referredToDoctorName {
                Label(toDoctor, systemImage: "stethoscope")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if let toDept = referral.referredToDepartment {
                Label(toDept, systemImage: "building.2")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                if let date = referral.referralDate {
                    Text(String(date.prefix(10)))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let number = referral.referralNumber {
                    Text("#\(number)")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func urgencyColor(_ urgency: String?) -> Color {
        switch urgency?.lowercased() {
        case "urgent", "emergency": return .red
        case "high": return .orange
        case "routine", "normal": return .green
        default: return .secondary
        }
    }
}

// MARK: - Detail View

struct ReferralDetailView: View {
    let referral: ReferralDto

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(referral.referralReason ?? "Referral")
                                .font(.title2.bold())
                            Spacer()
                            HMSStatusBadge(status: referral.status ?? "unknown")
                        }
                        if let number = referral.referralNumber {
                            Label("Ref #\(number)", systemImage: "number")
                                .foregroundStyle(.secondary)
                        }
                        if let urgency = referral.urgency {
                            Label(urgency.capitalized, systemImage: "exclamationmark.circle")
                                .foregroundStyle(.secondary)
                        }
                        if let date = referral.referralDate {
                            Label(String(date.prefix(10)), systemImage: "calendar")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // Referring From
                HMSSectionHeader(title: "Referred By")
                HMSCard {
                    VStack(alignment: .leading, spacing: 4) {
                        if let doctor = referral.referringDoctorName {
                            Label(doctor, systemImage: "stethoscope")
                        }
                        if let dept = referral.referringDepartment {
                            Label(dept, systemImage: "building.2")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        if let hospital = referral.referringHospital {
                            Label(hospital, systemImage: "cross.circle")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // Referred To
                HMSSectionHeader(title: "Referred To")
                HMSCard {
                    VStack(alignment: .leading, spacing: 4) {
                        if let doctor = referral.referredToDoctorName {
                            Label(doctor, systemImage: "stethoscope")
                        }
                        if let dept = referral.referredToDepartment {
                            Label(dept, systemImage: "building.2")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        if let hospital = referral.referredToHospital {
                            Label(hospital, systemImage: "cross.circle")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // Diagnosis
                if let diagnosis = referral.diagnosisDescription ?? referral.diagnosisCode {
                    HMSSectionHeader(title: "Diagnosis")
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(diagnosis)
                            if let code = referral.diagnosisCode, referral.diagnosisDescription != nil {
                                Text("Code: \(code)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                // Clinical Notes
                if let notes = referral.clinicalNotes, !notes.isEmpty {
                    HMSSectionHeader(title: "Clinical Notes")
                    HMSCard { Text(notes).font(.body) }
                }

                // Appointment
                if let apptDate = referral.appointmentDate {
                    HMSSectionHeader(title: "Appointment")
                    HMSCard {
                        Label(String(apptDate.prefix(16)), systemImage: "calendar.badge.clock")
                    }
                }

                // Expiry
                if let expiry = referral.expiryDate {
                    HMSCard {
                        Label("Expires: \(String(expiry.prefix(10)))", systemImage: "clock.badge.exclamationmark")
                            .foregroundStyle(.orange)
                    }
                }

                // Completion
                if let completedDate = referral.completedDate {
                    HMSSectionHeader(title: "Completion")
                    HMSCard {
                        VStack(alignment: .leading, spacing: 4) {
                            Label("Completed: \(String(completedDate.prefix(10)))", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                            if let notes = referral.completionNotes {
                                Text(notes).font(.body).padding(.top, 2)
                            }
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Referral Details")
        .navigationBarTitleDisplayMode(.inline)
    }
}
