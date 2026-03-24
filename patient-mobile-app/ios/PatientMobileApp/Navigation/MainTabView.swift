import SwiftUI

/// 5-tab bottom navigation: Home, Appointments, Records, Billing, More
struct MainTabView: View {
    @State private var selectedTab: Tab = .home
    @EnvironmentObject var authManager: AuthManager

    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem {
                    Label("Home", systemImage: "house.fill")
                }
                .tag(Tab.home)

            AppointmentListView()
                .tabItem {
                    Label("Appointments", systemImage: "calendar")
                }
                .tag(Tab.appointments)

            RecordsView()
                .tabItem {
                    Label("Records", systemImage: "heart.text.clipboard")
                }
                .tag(Tab.records)

            BillingView()
                .tabItem {
                    Label("Billing", systemImage: "creditcard")
                }
                .tag(Tab.billing)

            MoreView()
                .tabItem {
                    Label("More", systemImage: "ellipsis.circle")
                }
                .tag(Tab.more)
        }
        .tint(.hmsPrimary)
    }
}

enum Tab: Hashable {
    case home, appointments, records, billing, more
}
