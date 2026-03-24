// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PatientMobileApp",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "Core", targets: ["Core"]),
        .library(name: "DesignSystem", targets: ["DesignSystem"]),
        .library(name: "FeatureAuth", targets: ["FeatureAuth"]),
        .library(name: "FeatureHome", targets: ["FeatureHome"]),
        .library(name: "FeatureAppointments", targets: ["FeatureAppointments"]),
        .library(name: "FeatureRecords", targets: ["FeatureRecords"]),
        .library(name: "FeatureBilling", targets: ["FeatureBilling"]),
        .library(name: "FeatureProfile", targets: ["FeatureProfile"]),
        .library(name: "FeatureSettings", targets: ["FeatureSettings"]),
    ],
    targets: [
        .target(name: "Core", path: "PatientMobileApp/Core"),
        .target(name: "DesignSystem", dependencies: ["Core"], path: "PatientMobileApp/DesignSystem"),
        .target(name: "FeatureAuth", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Auth"),
        .target(name: "FeatureHome", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Home"),
        .target(name: "FeatureAppointments", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Appointments"),
        .target(name: "FeatureRecords", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Records"),
        .target(name: "FeatureBilling", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Billing"),
        .target(name: "FeatureProfile", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Profile"),
        .target(name: "FeatureSettings", dependencies: ["Core", "DesignSystem"], path: "PatientMobileApp/Features/Settings"),
    ]
)
