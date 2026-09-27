import XCTest
@testable import MediHubPatient

/// The wire half of APNs registration: the device token as lowercase hex, the
/// body `PUT /me/push-devices/{installationId}` expects, and an installation
/// id that is stable per install and not tied to a user.
final class PushRegistrationTests: XCTestCase {

    func testDeviceTokenIsLowercaseHexWithNoSeparators() {
        let token = Data([0x00, 0x0a, 0xff, 0x7f, 0xab])
        XCTAssertEqual(PushRegistration.hexString(token), "000aff7fab")
    }

    func testRegistrationBodyMatchesTheContract() throws {
        let body = PushRegistration.body(token: Data([0xde, 0xad, 0xbe, 0xef]), locale: "fr", appVersion: "1.0.3")
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: String]
        )
        XCTAssertEqual(json, ["token": "deadbeef", "platform": "IOS", "locale": "fr", "appVersion": "1.0.3"])
    }

    func testInstallationIdIsGeneratedOnceAndKept() throws {
        let suite = "push-registration-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let first = PushRegistration.installationId(defaults: defaults)
        let second = PushRegistration.installationId(defaults: defaults)

        XCTAssertEqual(first, second)
        XCTAssertNotNil(UUID(uuidString: first))
        XCTAssertEqual(first, first.lowercased())
    }

    func testPushDevicePath() {
        XCTAssertEqual(APIEndpoints.pushDevice(installationId: "abc"), "/me/push-devices/abc")
    }

    @MainActor
    func testATappedChatNotificationRequestsTheSendersThread() {
        let push = PushManager.shared
        push.messagesRequest = nil
        defer { push.messagesRequest = nil }

        push.handleNotificationTap(type: "APPOINTMENT_REMINDER", senderId: "x")
        XCTAssertNil(push.messagesRequest, "only chat notifications open Messages")

        push.handleNotificationTap(type: "CHAT_MESSAGE", senderId: "doctor-1")
        XCTAssertEqual(push.messagesRequest?.senderId, "doctor-1")
    }
}
