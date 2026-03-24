import Foundation

/// API environment configuration
enum AppEnvironment: String, CaseIterable {
    case dev
    case uat
    case prod

    var baseURL: URL {
        switch self {
        case .dev:  return URL(string: "https://hms.dev.bitnesttechs.com/api")!
        case .uat:  return URL(string: "https://hms.uat.bitnesttechs.com/api")!
        case .prod: return URL(string: "https://hms.bitnesttechs.com/api")!
        }
    }

    /// Resolved from build config or defaults to .dev
    static var current: AppEnvironment {
        #if DEBUG
        return .dev
        #else
        if let raw = Bundle.main.infoDictionary?["HMS_ENVIRONMENT"] as? String,
           let env = AppEnvironment(rawValue: raw) {
            return env
        }
        return .prod
        #endif
    }
}
