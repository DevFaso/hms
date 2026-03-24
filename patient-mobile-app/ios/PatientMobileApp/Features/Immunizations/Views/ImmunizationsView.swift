import SwiftUI

struct ImmunizationsView: View {
    @StateObject private var viewModel = ImmunizationsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    HMSLoadingView(message: "Loading immunizations...")
                } else if let error = viewModel.errorMessage {
                    HMSErrorView(message: error) { Task { await viewModel.loadImmunizations() } }
                } else if viewModel.immunizations.isEmpty {
                    HMSEmptyState(icon: "syringe", title: "No Immunizations", message: "Your immunization records will appear here.")
                } else {
                    immunizationsList
                }
            }
            .navigationTitle("Immunizations")
            .task { await viewModel.loadImmunizations() }
            .refreshable { await viewModel.loadImmunizations() }
        }
    }

    private var immunizationsList: some View {
        List(viewModel.immunizations) { imm in
            NavigationLink {
                ImmunizationDetailView(immunization: imm)
            } label: {
                immunizationRow(imm)
            }
        }
        .listStyle(.plain)
    }

    private func immunizationRow(_ imm: ImmunizationDto) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "syringe.fill")
                .foregroundColor(.hmsAccent)
                .frame(width: 32, height: 32)
                .background(Color.hmsAccent.opacity(0.1))
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 4) {
                Text(imm.vaccineName ?? "Vaccine")
                    .font(.hmsBodyMedium)
                    .foregroundColor(.hmsTextPrimary)

                HStack(spacing: 8) {
                    if let dose = imm.doseNumber, let total = imm.totalDoses {
                        Text("Dose \(dose)/\(total)")
                            .font(.hmsCaption)
                            .foregroundColor(.hmsAccent)
                    }
                    if let date = imm.administeredDate {
                        Text(date)
                            .font(.hmsCaption)
                            .foregroundColor(.hmsTextTertiary)
                    }
                }
            }

            Spacer()

            HMSStatusBadge(
                text: imm.status ?? "Given",
                color: imm.status?.uppercased() == "COMPLETED" ? .hmsSuccess : .hmsInfo
            )
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Detail View

struct ImmunizationDetailView: View {
    let immunization: ImmunizationDto

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Header
                HMSCard {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "syringe.fill")
                                .foregroundColor(.hmsAccent)
                                .font(.title2)
                            Text(immunization.vaccineName ?? "Vaccine")
                                .font(.hmsHeadline)
                                .foregroundColor(.hmsTextPrimary)
                        }

                        if let code = immunization.vaccineCode {
                            Text("Code: \(code)")
                                .font(.hmsCaption)
                                .foregroundColor(.hmsTextTertiary)
                        }
                    }
                }

                // Details
                VStack(spacing: 12) {
                    if let dose = immunization.doseNumber, let total = immunization.totalDoses {
                        detailRow(icon: "number.circle", label: "Dose", value: "\(dose) of \(total)")
                    }
                    if let date = immunization.administeredDate {
                        detailRow(icon: "calendar", label: "Administered", value: date)
                    }
                    if let by = immunization.administeredBy {
                        detailRow(icon: "person", label: "Administered By", value: by)
                    }
                    if let site = immunization.site {
                        detailRow(icon: "mappin.circle", label: "Site", value: site)
                    }
                    if let lot = immunization.lotNumber {
                        detailRow(icon: "barcode", label: "Lot Number", value: lot)
                    }
                    if let mfr = immunization.manufacturer {
                        detailRow(icon: "building.2", label: "Manufacturer", value: mfr)
                    }
                    if let exp = immunization.expirationDate {
                        detailRow(icon: "clock", label: "Expiration", value: exp)
                    }
                }

                if let notes = immunization.notes, !notes.isEmpty {
                    HMSSectionHeader(title: "Notes")
                    HMSCard {
                        Text(notes)
                            .font(.hmsBody)
                            .foregroundColor(.hmsTextPrimary)
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Immunization Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailRow(icon: String, label: String, value: String) -> some View {
        HMSCard {
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
                Spacer()
            }
        }
    }
}
