import XCTest
@testable import Dosefolk

final class NtfyLiveStreamTests: XCTestCase {
    func testReconnectPolicyUsesBoundedExponentialBackoff() {
        var policy = NtfyReconnectPolicy()
        let seconds = (0..<8).map { _ in policy.nextDelayNanoseconds() / 1_000_000_000 }
        XCTAssertEqual(seconds, [1, 2, 4, 8, 16, 30, 30, 30])

        policy.reset()
        XCTAssertEqual(policy.nextDelayNanoseconds(), 1_000_000_000)
    }

    func testStreamEndpointUsesSelfHostedServer() throws {
        let url = try XCTUnwrap(NtfyEndpoint.streamURL(topics: ["peer-a", "peer-b"], since: "10s"))
        XCTAssertEqual(url.host, "ntfy.field-maintenance-prod.com")
        XCTAssertFalse(url.absoluteString.contains("poll=1"))
        XCTAssertTrue(url.absoluteString.contains("peer-a,peer-b/json"))
        XCTAssertTrue(url.absoluteString.contains("since=10s"))
    }
}
