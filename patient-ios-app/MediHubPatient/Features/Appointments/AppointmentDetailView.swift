import SwiftUI

struct AppointmentDetailView: View {
    let appointment: AppointmentDTO

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // ── Status Header ──
                statusHeader

                // ── Doctor & Hospital ──
                detailCard(title: "Provider", icon: "stethoscope") {
                    detailRow(label: "Doctor", value: appointment.staffName)
                    detailRow(label: "Email", value: appointment.staffEmail)
                    detailRow(label: "Hospital", value: appointment.hospitalName)
                    detailRow(label: "Address", value: appointment.hospitalAddress)
                }

                // ── Schedule ──
                detailCard(title: "Schedule", icon: "calendar") {
                    detailRow(label: "Date", value: formatDate(appointment.appointmentDate))
                    detailRow(label: "Time", value: appointment.timeRange)
                }

                // ── Visit Details ──
                if appointment.reason != nil || appointment.notes != nil || appointment.treatmentName != nil {
                    detailCard(title: "Visit Details", icon: "doc.text") {
                        if let reason = appointment.reason, !reason.isEmpty {
                            detailRow(label: "Reason", value: reason)
                        }
                        if let notes = appointment.notes, !notes.isEmpty {
                            detailRow(label: "Notes", value: notes)
                        }
                        if let treatment = appointment.treatmentName, !treatment.isEmpty {
                            detailRow(label: "Treatment", value: treatment)
                            if let desc = appointment.treatmentDescription, !desc.isEmpty {
                                detailRow(label: "Description", value: desc)
                            }
                        }
                    }
                }

                // ── Patient Info ──
                detailCard(title: "Patient", icon: "person.fill") {
                    detailRow(label: "Name", value: appointment.patientName)
                    detailRow(label: "Email", value: appointment.patientEmail)
                    detailRow(label: "Phone", value: appointment.patientPhone)
                }

                // ── Record Info ──
                detailCard(title: "Record", icon: "info.circle") {
                    if let createdBy = appointment.createdByName, !createdBy.isEmpty {
                        detailRow(label: "Created By", value: createdBy)
                    }
                    detailRow(label: "Created", value: formatDateTime(appointment.createdAt))
                    if appointment.updatedAt != appointment.createdAt {
                        detailRow(label: "Updated", value: formatDateTime(appointment.updatedAt))
                    }
                }
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Appointment Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Status Header

    private var statusHeader: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(statusSwiftColor.opacity(0.15))
                    .frame(width: 64, height: 64)
                Image(systemName: statusIcon)
                    .font(.system(size: 28))
                    .foregroundColor(statusSwiftColor)
            }

            Text(appointment.statusDisplay)
                .font(.title3).bold()
                .foregroundColor(statusSwiftColor)

            if let doctor = appointment.staffName {
                Text("with \(doctor)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }

            if let date = appointment.appointmentDate, let time = appointment.timeRange {
                Text("\(formatDate(date)) · \(time)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.05), radius: 8, y: 2)
        )
    }

    // MARK: - Detail Card

    private func detailCard<Content: View>(title: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.subheadline)
                    .foregroundColor(.accentColor)
                Text(title)
                    .font(.subheadline).bold()
                    .foregroundColor(.primary)
            }
            .padding(.bottom, 2)

            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.04), radius: 4, y: 1)
        )
    }

    // MARK: - Detail Row

    private func detailRow(label: String, value: String?) -> some View {
        Group {
            if let val = value, !val.isEmpty {
                HStack(alignment: .top) {
                    Text(label)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(width: 80, alignment: .leading)
                    Text(val)
                        .font(.subheadline)
                        .foregroundColor(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    // MARK: - Helpers

    private var statusSwiftColor: Color {
        switch appointment.status?.uppercased() {
        case "SCHEDULED", "CONFIRMED": return .green
        case "PENDING":                return .yellow
        case "CANCELLED", "NO_SHOW":   return .red
        case "COMPLETED":              return .gray
        case "RESCHEDULED":            return .orange
        default:                       return .blue
        }
    }

    private var statusIcon: String {
        switch appointment.status?.uppercased() {
        case "SCHEDULED", "CONFIRMED": return "calendar.badge.checkmark"
        case "PENDING":                return "clock.badge.questionmark"
        case "CANCELLED":              return "calendar.badge.minus"
        case "NO_SHOW":                return "person.slash"
        case "COMPLETED":              return "checkmark.circle.fill"
        case "RESCHEDULED":            return "calendar.badge.clock"
        default:                       return "calendar"
        }
    }

    private func formatDate(_ iso: String?) -> String {
        guard let iso = iso else { return "—" }
        let inFmt = DateFormatter()
        inFmt.dateFormat = "yyyy-MM-dd"
        guard let date = inFmt.date(from: iso) else { return iso }
        let outFmt = DateFormatter()
        outFmt.dateStyle = .medium
        return outFmt.string(from: date)
    }

    private func formatDateTime(_ iso: String?) -> String {
        guard let iso = iso else { return "—" }
        let inFmt = DateFormatter()
        inFmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS"
        if let date = inFmt.date(from: iso) {
            let outFmt = DateFormatter()
            outFmt.dateStyle = .medium
            outFmt.timeStyle = .short
            return outFmt.string(from: date)
        }
        // Try without fractional seconds
        inFmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        if let date = inFmt.date(from: String(iso.prefix(19))) {
            let outFmt = DateFormatter()
            outFmt.dateStyle = .medium
            outFmt.timeStyle = .short
            return outFmt.string(from: date)
        }
        return iso
    }
}
