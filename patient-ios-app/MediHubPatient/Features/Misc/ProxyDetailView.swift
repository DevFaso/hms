import SwiftUI

struct ProxyDetailView: View {
    let proxy: ProxyResponse
    /// true when viewing from "Granted by Me" (grantor perspective)
    let isGrantor: Bool
    var revokeAction: (() -> Void)?

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // ── Header ──
                statusHeader

                // ── Person Info ──
                detailCard(title: isGrantor ? "Proxy User" : "Grantor", icon: "person.fill") {
                    if isGrantor {
                        detailRow(label: "Name", value: proxy.proxyDisplayName ?? proxy.proxyUsername)
                        detailRow(label: "Username", value: proxy.proxyUsername)
                    } else {
                        detailRow(label: "Name", value: proxy.grantorName)
                    }
                    detailRow(label: "Relationship", value: proxy.relationship?.replacingOccurrences(of: "_", with: " ").capitalized)
                }

                // ── Permissions ──
                detailCard(title: "Permissions", icon: "lock.shield") {
                    if proxy.permissionsList.isEmpty {
                        HStack {
                            Image(systemName: "exclamationmark.triangle")
                                .foregroundColor(.orange)
                            Text("No permissions specified")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    } else {
                        ForEach(proxy.permissionsList, id: \.self) { perm in
                            HStack(spacing: 10) {
                                Image(systemName: permissionIcon(perm))
                                    .foregroundColor(.accentColor)
                                    .frame(width: 24)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(permissionTitle(perm))
                                        .font(.subheadline).bold()
                                    Text(permissionDescription(perm))
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }

                // ── Dates ──
                detailCard(title: "Timeline", icon: "clock") {
                    if let created = proxy.createdAt {
                        detailRow(label: "Granted", value: formatDateTime(created))
                    }
                    if let expires = proxy.expiresAt {
                        detailRow(label: "Expires", value: formatDateTime(expires))
                        if isExpiringSoon(expires) {
                            HStack(spacing: 4) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundColor(.orange)
                                    .font(.caption)
                                Text("Expiring soon")
                                    .font(.caption).bold()
                                    .foregroundColor(.orange)
                            }
                        }
                    } else {
                        detailRow(label: "Expires", value: "No expiry set")
                    }
                    if let revoked = proxy.revokedAt {
                        detailRow(label: "Revoked", value: formatDateTime(revoked))
                    }
                }

                // ── Notes ──
                if let notes = proxy.notes, !notes.isEmpty {
                    detailCard(title: "Notes", icon: "note.text") {
                        Text(notes)
                            .font(.subheadline)
                            .foregroundColor(.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                // ── Revoke Button ──
                if isGrantor, proxy.status?.uppercased() == "ACTIVE", let revoke = revokeAction {
                    Button(role: .destructive) {
                        revoke()
                    } label: {
                        HStack {
                            Image(systemName: "xmark.shield")
                            Text("Revoke Access")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.red.opacity(0.1))
                        .foregroundColor(.red)
                        .cornerRadius(12)
                    }
                    .padding(.top, 8)
                }
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Access Details")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Status Header

    private var statusHeader: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(statusColor.opacity(0.15))
                    .frame(width: 64, height: 64)
                Image(systemName: isGrantor ? "person.badge.key" : "key.fill")
                    .font(.system(size: 28))
                    .foregroundColor(statusColor)
            }

            Text(isGrantor
                 ? (proxy.proxyDisplayName ?? proxy.proxyUsername ?? "User")
                 : (proxy.grantorName ?? "Patient"))
                .font(.title2).bold()

            if let relationship = proxy.relationship {
                Text(relationship.replacingOccurrences(of: "_", with: " ").capitalized)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }

            StatusBadge(
                text: proxy.status?.capitalized ?? "Active",
                color: proxy.status?.uppercased() == "ACTIVE" ? "green" : "gray"
            )
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
                        .frame(width: 90, alignment: .leading)
                    Text(val)
                        .font(.subheadline)
                        .foregroundColor(.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    // MARK: - Helpers

    private var statusColor: Color {
        switch proxy.status?.uppercased() {
        case "ACTIVE":  return .green
        case "REVOKED": return .red
        case "EXPIRED": return .orange
        default:        return .gray
        }
    }

    private func permissionIcon(_ perm: String) -> String {
        switch perm.uppercased() {
        case "VIEW_RECORDS":       return "doc.text.fill"
        case "VIEW_APPOINTMENTS":  return "calendar"
        case "VIEW_MEDICATIONS":   return "pills.fill"
        case "VIEW_LAB_RESULTS":   return "testtube.2"
        case "VIEW_BILLING":       return "creditcard.fill"
        default:                   return "eye.fill"
        }
    }

    private func permissionTitle(_ perm: String) -> String {
        perm.replacingOccurrences(of: "_", with: " ").capitalized
    }

    private func permissionDescription(_ perm: String) -> String {
        switch perm.uppercased() {
        case "VIEW_RECORDS":       return "Access to medical records and health summary"
        case "VIEW_APPOINTMENTS":  return "View upcoming and past appointments"
        case "VIEW_MEDICATIONS":   return "View current medications and prescriptions"
        case "VIEW_LAB_RESULTS":   return "View lab test results"
        case "VIEW_BILLING":       return "View invoices and payment history"
        default:                   return "General access permission"
        }
    }

    private func formatDateTime(_ isoString: String) -> String {
        let prefix = String(isoString.prefix(10))
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        if let date = formatter.date(from: prefix) {
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: date)
        }
        return prefix
    }

    private func isExpiringSoon(_ isoString: String) -> Bool {
        let prefix = String(isoString.prefix(10))
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        guard let expiryDate = formatter.date(from: prefix) else { return false }
        let daysUntilExpiry = Calendar.current.dateComponents([.day], from: Date(), to: expiryDate).day ?? 0
        return daysUntilExpiry >= 0 && daysUntilExpiry <= 30
    }
}
