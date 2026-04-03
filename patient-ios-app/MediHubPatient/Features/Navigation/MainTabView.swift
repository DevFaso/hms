import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var authManager: AuthManager
    @EnvironmentObject var localization: LocalizationManager
    @ObservedObject private var profileImageManager = ProfileImageManager.shared
    @State private var selectedTab: Tab = .dashboard
    @State private var showMenu = false

    enum Tab: Hashable {
        case dashboard, appointments, messages, profile
    }

    var body: some View {
        ZStack(alignment: .leading) {
            VStack(spacing: 0) {
                Group {
                    switch selectedTab {
                    case .dashboard:
                        DashboardView(showMenu: $showMenu)
                    case .appointments:
                        AppointmentsView()
                    case .messages:
                        MessagesView()
                    case .profile:
                        ProfileView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                // Tab bar
                VStack(spacing: 0) {
                    Rectangle()
                        .fill(Color(.separator).opacity(0.3))
                        .frame(height: 0.5)

                    HStack(spacing: 0) {
                        tabButton(icon: "house.fill", titleKey: "tab_dashboard", tab: .dashboard)
                        tabButton(icon: "calendar", titleKey: "tab_appointments", tab: .appointments)
                        tabButton(icon: "message.fill", titleKey: "tab_messages", tab: .messages)
                        profileTabButton
                    }
                    .padding(.top, 8)
                    .padding(.bottom, 4)
                    .background(.ultraThinMaterial)
                }
            }
            .disabled(showMenu)

            if showMenu {
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .onTapGesture { withAnimation(.spring(response: 0.3)) { showMenu = false } }

                SideMenuView(selectedTab: $selectedTab, isShowing: $showMenu)
                    .transition(.move(edge: .leading))
            }
        }
        .animation(.spring(response: 0.3), value: showMenu)
    }

    private func tabButton(icon: String, titleKey: String, tab: Tab) -> some View {
        let isSelected = selectedTab == tab
        return Button {
            withAnimation(.spring(response: 0.25)) { selectedTab = tab }
        } label: {
            VStack(spacing: 5) {
                ZStack {
                    if isSelected {
                        Capsule()
                            .fill(Color("BrandBlue").opacity(0.12))
                            .frame(width: 48, height: 28)
                    }
                    Image(systemName: icon)
                        .font(.system(size: 18, weight: isSelected ? .semibold : .regular))
                        .symbolRenderingMode(.hierarchical)
                }
                Text(titleKey.localized)
                    .font(.system(size: 10, weight: isSelected ? .semibold : .regular))
            }
            .foregroundStyle(isSelected ? Color("BrandBlue") : .secondary)
            .frame(maxWidth: .infinity)
        }
    }

    private var profileTabButton: some View {
        let isSelected = selectedTab == .profile
        return Button {
            withAnimation(.spring(response: 0.25)) { selectedTab = .profile }
        } label: {
            VStack(spacing: 5) {
                ZStack {
                    if isSelected {
                        Capsule()
                            .fill(Color("BrandBlue").opacity(0.12))
                            .frame(width: 48, height: 28)
                    }
                    if let url = profileImageManager.resolvedURL {
                        AsyncImage(url: url) { phase in
                            if let img = phase.image {
                                img.resizable().scaledToFill()
                            } else {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.system(size: 18))
                            }
                        }
                        .frame(width: 22, height: 22)
                        .clipShape(Circle())
                        .overlay(
                            Circle().stroke(isSelected ? Color("BrandBlue") : .clear, lineWidth: 1.5)
                        )
                    } else {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.system(size: 18, weight: isSelected ? .semibold : .regular))
                            .symbolRenderingMode(.hierarchical)
                    }
                }
                Text("tab_profile".localized)
                    .font(.system(size: 10, weight: isSelected ? .semibold : .regular))
            }
            .foregroundStyle(isSelected ? Color("BrandBlue") : .secondary)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Side Menu

struct SideMenuView: View {
    @EnvironmentObject var authManager: AuthManager
    @EnvironmentObject var localization: LocalizationManager
    @ObservedObject private var profileImageManager = ProfileImageManager.shared
    @Binding var selectedTab: MainTabView.Tab
    @Binding var isShowing: Bool

    private var menuItems: [(titleKey: String, icon: String, tab: MainTabView.Tab)] {
        [
            ("tab_dashboard", "house.fill", .dashboard),
            ("tab_appointments", "calendar", .appointments),
            ("tab_messages", "message.fill", .messages),
            ("tab_profile", "person.crop.circle.fill", .profile),
        ]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            VStack(alignment: .leading, spacing: 10) {
                ZStack(alignment: .bottomTrailing) {
                    if let url = profileImageManager.resolvedURL {
                        AsyncImage(url: url) { phase in
                            if let img = phase.image {
                                img.resizable().scaledToFill()
                            } else {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.system(size: 52))
                                    .foregroundStyle(.white.opacity(0.9))
                            }
                        }
                        .frame(width: 64, height: 64)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white.opacity(0.4), lineWidth: 2))
                    } else {
                        Image(systemName: "person.crop.circle.fill")
                            .font(.system(size: 52))
                            .foregroundStyle(.white.opacity(0.9))
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(authManager.currentUser?.fullName ?? "patient".localized)
                        .font(.title3.weight(.bold))
                        .foregroundStyle(.white)
                    if let email = authManager.currentUser?.email {
                        Text(email)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.75))
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 56)
            .padding(.bottom, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                LinearGradient(colors: [Color("BrandBlue"), Color("BrandDarkBlue")],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
            )

            // Menu items
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(menuItems, id: \.titleKey) { item in
                        Button {
                            withAnimation(.spring(response: 0.3)) {
                                selectedTab = item.tab
                                isShowing = false
                            }
                        } label: {
                            HStack(spacing: 14) {
                                Image(systemName: item.icon)
                                    .font(.system(size: 16, weight: .medium))
                                    .frame(width: 28)
                                    .foregroundStyle(selectedTab == item.tab ? Color("BrandBlue") : .primary)
                                Text(item.titleKey.localized)
                                    .font(.subheadline.weight(selectedTab == item.tab ? .semibold : .regular))
                                    .foregroundStyle(selectedTab == item.tab ? Color("BrandBlue") : .primary)
                                Spacer()
                                if selectedTab == item.tab {
                                    Circle()
                                        .fill(Color("BrandBlue"))
                                        .frame(width: 6, height: 6)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 13)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(selectedTab == item.tab ? Color("BrandBlue").opacity(0.08) : .clear)
                            )
                        }
                    }

                    Rectangle()
                        .fill(Color(.separator).opacity(0.5))
                        .frame(height: 0.5)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 16)

                    MenuLink(icon: "pill.fill", title: "medications_title".localized) {
                        selectedTab = .dashboard; isShowing = false
                    }
                    MenuLink(icon: "creditcard.fill", title: "billing_title".localized) {
                        selectedTab = .dashboard; isShowing = false
                    }
                    MenuLink(icon: "heart.fill", title: "vitals_title".localized) {
                        selectedTab = .dashboard; isShowing = false
                    }
                    MenuLink(icon: "testtube.2", title: "lab_results_title".localized) {
                        selectedTab = .dashboard; isShowing = false
                    }
                    MenuLink(icon: "person.2.fill", title: "care_team_title".localized) {
                        selectedTab = .dashboard; isShowing = false
                    }
                    MenuLink(icon: "doc.fill", title: "documents".localized) {
                        selectedTab = .profile; isShowing = false
                    }

                    Rectangle()
                        .fill(Color(.separator).opacity(0.5))
                        .frame(height: 0.5)
                        .padding(.vertical, 10)
                        .padding(.horizontal, 16)

                    MenuLink(icon: "gearshape.fill", title: "settings".localized) {
                        selectedTab = .profile; isShowing = false
                    }
                }
                .padding(.vertical, 12)
            }

            Spacer()

            // Logout
            Button(role: .destructive) {
                authManager.logout()
            } label: {
                HStack(spacing: 14) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 16, weight: .medium))
                        .frame(width: 28)
                    Text("logout".localized)
                        .font(.subheadline.weight(.medium))
                }
                .foregroundStyle(.red)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
            }

            Spacer().frame(height: 8)
        }
        .frame(width: 290)
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 0))
        .shadow(color: .black.opacity(0.15), radius: 20, x: 5, y: 0)
        .ignoresSafeArea(.all, edges: .vertical)
    }
}

struct MenuLink: View {
    let icon: String
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .frame(width: 28)
                    .foregroundStyle(.secondary)
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
        }
    }
}
