import Foundation

/// Disk-backed JSON cache with TTL for offline support
final class CacheManager {
    static let shared = CacheManager()

    private let cacheDirectory: URL
    private let defaultTTL: TimeInterval = 3600 // 1 hour
    private let fileManager = FileManager.default

    private init() {
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDirectory = caches.appendingPathComponent("hms_api_cache", isDirectory: true)
        try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    // MARK: - Public API

    /// Store an encodable value with a cache key
    func store<T: Encodable>(_ value: T, forKey key: String, ttl: TimeInterval? = nil) {
        let entry = CacheEntry(
            data: (try? JSONEncoder().encode(value)) ?? Data(),
            expiresAt: Date().addingTimeInterval(ttl ?? defaultTTL)
        )
        let fileURL = fileURL(for: key)
        try? JSONEncoder().encode(entry).write(to: fileURL, options: .atomic)
    }

    /// Retrieve a cached value if it exists and hasn't expired
    func retrieve<T: Decodable>(forKey key: String, as type: T.Type) -> T? {
        let fileURL = fileURL(for: key)
        guard let data = try? Data(contentsOf: fileURL),
              let entry = try? JSONDecoder().decode(CacheEntry.self, from: data) else {
            return nil
        }
        guard entry.expiresAt > Date() else {
            try? fileManager.removeItem(at: fileURL)
            return nil
        }
        return try? JSONDecoder().decode(T.self, from: entry.data)
    }

    /// Remove a specific cache entry
    func remove(forKey key: String) {
        try? fileManager.removeItem(at: fileURL(for: key))
    }

    /// Clear all cached data
    func clearAll() {
        try? fileManager.removeItem(at: cacheDirectory)
        try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    /// Check if a non-expired cache entry exists
    func has(key: String) -> Bool {
        retrieve(forKey: key, as: Data.self) != nil
    }

    // MARK: - Helpers

    private func fileURL(for key: String) -> URL {
        let safeKey = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key
        return cacheDirectory.appendingPathComponent(safeKey)
    }
}

// MARK: - Cache Entry

private struct CacheEntry: Codable {
    let data: Data
    let expiresAt: Date
}
