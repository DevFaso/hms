import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var authManager: AuthManager
    @ObservedObject private var profileImageManager = ProfileImageManager.shared
    @State private var selectedTab: Tab = .dashboard
    @State private var showMenu = false

    enum Tab: Hashable {
        case dashboard, appointments, messages, profile
    }

    var body: some View {
        ZStack(alignment: .leading) {
            VStack(spacing: 0) {
                // Content area
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

                Divider()

                // Custom tab bar
                HStack {
                    tabButton(icon: "house.fill", title: "Dashboard", tab: .dashboard)
                    tabButton(icon: "calendar", title: "Appointments", tab: .appointments)
                    tabButton(icon: "message.fill", title: "Messages", tab: .messages)
                    profileTabButton
                }
                .padding(.top, 6)
                .padding(.bottom, 2)
                .background(Color(.systemBackground))
            }
            .disabled(showMenu)

            // Slide-out side menu
            if showMenu {
                Color.black.opacity(0.3)
                    .ignoresSafeArea()
                    .onTapGesture { withAnimation { showMenu = false } }

                SideMenuView(selectedTab: $selectedTab, isShowing: $showMenu)
                    .transition(.move(edge: .leading))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: showMenu)
    }

    // MARK: - Standard tab button

    private func tabButton(icon: String, title: String, tab: Tab) -> some View {
        Button {
            selectedTab = tab
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                Text(title)
                    .font(.caption2)
            }
            .foregroundColor(selectedTab == tab ? Color("BrandBlue") : .gray)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Profile tab button with photo

    private var profileTabButton: some View {
        Button {
            selectedTab = .profile
        } label: {
            VStack(spacing: 4) {
                if let url = profileImageManager.resolvedURL {
                    AsyncImage(url: url) { phase in
                        if let img = phase.image {
                            img.resizable().scaledToFill()
                        } else {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.system(size: 20))
                        }
                    }
                    .frame(width: 24, height: 24)
                    .clipShape(Circle())
                    .overlay(
                        Circle().stroke(selectedTab == .profile ? Color("BrandBlue") : .clear, lineWidth: 2)
                    )
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 20))
                }
                Text("Profile")
                    .font(.caption2)
            }
            .foregroundColor(selectedTab == .profile ? Color("BrandBlue") : .gray)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Side Menu

struct SideMenuView: View {
    @EnvironmentObject var authManager: AuthManager
    @ObservedObject private var profileImageManager = ProfileImageManager.shared
    @Binding var selectedTab: MainTabView.Tab
    @Binding var isShowing: Bool

    private let menuItems: [(title: String, icon: String, tab: MainTabView.Tab)] = [
        ("Dashboard",    "house.fill",               .dashboard),
        ("Appointments", "calendar",                  .appointments),
        ("Messages",     "message.fill",              .messages),
        ("Profile",      "person.crop.circle.fill",   .profile),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            VStack(alignment: .leading, spacing: 8) {
                if let url = profileImageManager.resolvedURL {
                    AsyncImage(url: url) { phase in
                        if let img = phase.image {
                            img.resizable().scaledToFill()
                        } else {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.system(size: 48))
                                .foregroundColor(.white)
                        }
                    }
                    .frame(width: 56, height: 56)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(Color.white.opacity(0.5), lineWidth: 2))
                } else {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 48))
                        .foregroundColor(.white)
                }
                Text(authManager.currentUser?.fullName ?? "Patient")
                    .font(.title3).bold().foregroundColor(.white)
                if let email = authManager.currentUser?.email {
                    Text(email).font(.caption).foregroundColor(.white.opacity(0.8))
                }
            }
            .padding()
            .padding(.top, 44) // safe area
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.accentColor)

            // Menu items
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(menuItems, id: \.title) { item in
                        Button {
                            withAnimation {
                                selectedTab = item.tab
                                isShowing = false
                            }
                        } label: {
                            HStack(spacing: 16) {
                                Image(systemName: item.icon)
                                    .frame(width: 24)
                                    .foregroundColor(.primary)
                                Text(item.title)
                                    .foregroundColor(.primary)
                                Spacer()
                            }
                            .padding(.horizontal)
                            .padding(.vertical, 14)
                            .background(
                                selectedTab == item.tab
                                    ? Color.accentColor.opacity(0.1)
                                    : Color.clear
                            )
                            .cornerRadius(10)
                        }
                    }

                    Divider().padding(.vertical, 8)

                    // Quick links
                    MenuLink(icon: "pill.fill", title: "Medications") {
                        selectedTab = .dashboard
                        isShowing = false
                    }
                    MenuLink(icon: "creditcard.fill", title: "Billing") {
                        selectedTab = .dashboard
                        isShowing = false
                    }
                    MenuLink(icon: "heart.fill", title: "Vitals") {
                        selectedTab = .dashboard
                        isShowing = false
                    }
                    MenuLink(icon: "testtube.2", title: "Lab Results") {
                        selectedTab = .dashboard
                        isShowing = false
                    }
                    MenuLink(icon: "person.2.fill", title: "Care Team") {
                        selectedTab = .dashboard
                        isShowing = false
                    }
                    MenuLink(icon: "doc.fill", title: "Documents") {
                        selectedTab = .profile
                        isShowing = false
                    }

                    Divider().padding(.vertical, 8)

                    MenuLink(icon: "gearshape.fill", title: "Settings") {
                        selectedTab = .profile
                        isShowing = false
                    }
                }
                .padding(.vertical, 8)
            }

            Spacer()

            // Logout
            Button(role: .destructive) {
                authManager.logout()
            } label: {
                HStack(spacing: 16) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .frame(width: 24)
                    Text("Log Out")
                }
                .foregroundColor(.red)
                .padding()
            }
        }
        .frame(width: 280)
        .background(Color(.systemBackground))
        .ignoresSafeArea(.all, edges: .vertical)
    }
}

struct MenuLink: View {
    let icon: String
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .frame(width: 24)
                    .foregroundColor(.secondary)
                Text(title)
                    .foregroundColor(.primary)
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
    }
}
