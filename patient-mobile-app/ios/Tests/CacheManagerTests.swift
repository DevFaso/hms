import XCTest
@testable import PatientMobileApp

final class CacheManagerTests: XCTestCase {
    let cache = CacheManager.shared
    let testKey = "test_key"

    override func setUp() {
        super.setUp()
        cache.clearAll()
    }

    func testStoreAndRetrieve() {
        let value = "Hello, HMS!"
        cache.store(value, forKey: testKey, ttl: 10)
        let retrieved: String? = cache.retrieve(forKey: testKey, as: String.self)
        XCTAssertEqual(retrieved, value)
    }

    func testExpiration() {
        let value = "Expiring soon"
        cache.store(value, forKey: testKey, ttl: 0.1)
        sleep(1)
        let retrieved: String? = cache.retrieve(forKey: testKey, as: String.self)
        XCTAssertNil(retrieved)
    }

    func testRemove() {
        let value = "To be removed"
        cache.store(value, forKey: testKey, ttl: 10)
        cache.remove(forKey: testKey)
        let retrieved: String? = cache.retrieve(forKey: testKey, as: String.self)
        XCTAssertNil(retrieved)
    }

    func testClearAll() {
        cache.store("A", forKey: "A", ttl: 10)
        cache.store("B", forKey: "B", ttl: 10)
        cache.clearAll()
        XCTAssertNil(cache.retrieve(forKey: "A", as: String.self))
        XCTAssertNil(cache.retrieve(forKey: "B", as: String.self))
    }

    func testHasKey() {
        cache.store("exists", forKey: testKey, ttl: 10)
        XCTAssertTrue(cache.has(key: testKey))
        cache.remove(forKey: testKey)
        XCTAssertFalse(cache.has(key: testKey))
    }
}
