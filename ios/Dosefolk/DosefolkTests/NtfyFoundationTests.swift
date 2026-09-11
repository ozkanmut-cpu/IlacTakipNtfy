import XCTest
@testable import Dosefolk

final class NtfyFoundationTests: XCTestCase {
    func testSelfHostedEndpointMatchesAndroid() throws {
        XCTAssertEqual(NtfyEndpoint.baseURL, "https://ntfy.field-maintenance-prod.com")
        XCTAssertEqual(
            NtfyEndpoint.topicURL("dosefolk-a")?.absoluteString,
            "https://ntfy.field-maintenance-prod.com/dosefolk-a"
        )
        XCTAssertEqual(
            NtfyEndpoint.pollURL(topics: ["dosefolk-a", "dosefolk-b"], since: "24h")?.absoluteString,
            "https://ntfy.field-maintenance-prod.com/dosefolk-a,dosefolk-b/json?poll=1&since=24h"
        )
        XCTAssertEqual(
            NtfyEndpoint.streamURL(topics: ["dosefolk-a"], since: "10s")?.absoluteString,
            "https://ntfy.field-maintenance-prod.com/dosefolk-a/json?since=10s"
        )
    }

    func testRetryAfterSecondsUsesAndroidClamps() {
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertEqual(NtfyRateGate.retryAfterDate("1", now: now).timeIntervalSince(now), 30, accuracy: 0.01)
        XCTAssertEqual(NtfyRateGate.retryAfterDate("120", now: now).timeIntervalSince(now), 120, accuracy: 0.01)
        XCTAssertEqual(NtfyRateGate.retryAfterDate("999999", now: now).timeIntervalSince(now), 21_600, accuracy: 0.01)
    }

    func testMissingRetryAfterDefaultsToSixtySeconds() {
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertEqual(NtfyRateGate.retryAfterDate(nil, now: now).timeIntervalSince(now), 60, accuracy: 0.01)
    }

    func testRetryAfterHTTPDateIsAccepted() throws {
        let now = Date(timeIntervalSince1970: 0)
        let until = NtfyRateGate.retryAfterDate("Thu, 01 Jan 1970 00:02:00 GMT", now: now)
        XCTAssertEqual(until.timeIntervalSince(now), 120, accuracy: 0.01)
    }
}
