import Foundation
import CryptoKit

/// Byte/schema/signature interoperability only. This is NOT an iOS HPKE transport, pairing
/// activation service, expiry/replay engine, or a claim of Apple-device E2E coverage.
enum RelayProtocol {
    enum Failure: Error { case invalidEnvelope }

    struct VerifiedEnvelope: CustomStringConvertible {
        let canonicalPayload: String
        let signingInput: Data
        var description: String { "VerifiedEnvelope(redacted)" }
    }

    struct VerifiedPairing: CustomStringConvertible {
        let offerId: String
        let installId: String
        let keyVersion: Int
        let signingInput: Data
        var description: String { "VerifiedPairing(redacted)" }
    }

    static func canonicalJSON(_ bytes: Data) throws -> Data {
        Data(try canonicalObject(bytes).encoded().utf8)
    }

    static func hpkeContextInfo(_ context: Data) throws -> Data {
        let value = try canonicalObject(context)
        try validateContext(value)
        return Data(("dosefolk-relay-hpke-v1\0" + value.encoded()).utf8)
    }

    static func verifyEnvelope(_ bytes: Data, senderSigningPublicKey: String, expectedContext: Data) throws -> VerifiedEnvelope {
        let context = try canonicalObject(expectedContext)
        try validateContext(context)
        let inner = try canonicalObject(bytes)
        try inner.requireFields(["context", "payload", "signature", "version"])
        try require(inner.field("version")?.integer == 1)
        guard let signedContext = inner.field("context"), let payload = inner.field("payload"),
              case .object = payload else { throw Failure.invalidEnvelope }
        try require(Data(signedContext.encoded().utf8) == Data(context.encoded().utf8))
        let signature = try base64URL(inner.string("signature"), count: 64)
        let signingInput = Data(("dosefolk-relay-signature-v1\0" + inner.removing("signature").encoded()).utf8)
        try verify(signature, signingInput, publicKey: senderSigningPublicKey)
        return VerifiedEnvelope(canonicalPayload: payload.encoded(), signingInput: signingInput)
    }

    static func verifyPairing(_ bytes: Data) throws -> VerifiedPairing {
        let offer = try canonicalObject(bytes)
        try offer.requireFields(["version", "offerId", "installId", "encryptionPublicKey", "signingPublicKey",
                                 "keyVersion", "pairingSecret", "expiresAt", "signature"])
        try require(offer.field("version")?.integer == 2)
        let version = try positiveVersion(offer.field("keyVersion"))
        let offerId = try offer.string("offerId")
        let installId = try offer.string("installId")
        try opaqueId(offerId)
        try opaqueId(installId)
        let encryption = try base64URL(offer.string("encryptionPublicKey"), count: 32)
        let signingKey = try offer.string("signingPublicKey")
        let signing = try base64URL(signingKey, count: 32)
        try require(encryption.contains { $0 != 0 } && signing.contains { $0 != 0 } && encryption != signing)
        _ = try base64URL(offer.string("pairingSecret"), count: 32)
        let expiry = try offer.string("expiresAt")
        try require(expiry.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"#, options: .regularExpression) != nil)
        let formatter = ISO8601DateFormatter()
        guard let date = formatter.date(from: expiry) else { throw Failure.invalidEnvelope }
        try require(formatter.string(from: date) == expiry)
        let signature = try base64URL(offer.string("signature"), count: 64)
        let input = Data(("dosefolk-relay-pairing-v2\0" + offer.removing("signature").encoded()).utf8)
        try verify(signature, input, publicKey: signingKey)
        return VerifiedPairing(offerId: offerId, installId: installId, keyVersion: version, signingInput: input)
    }

    private static func verify(_ signature: Data, _ message: Data, publicKey: String) throws {
        // CryptoKit Curve25519.Signing is Ed25519, not X25519 key agreement.
        let raw = try base64URL(publicKey, count: 32)
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw),
              key.isValidSignature(signature, for: message) else { throw Failure.invalidEnvelope }
    }

    private static func validateContext(_ value: Value) throws {
        try value.requireFields(["messageId", "routeId", "senderInstallId", "recipientInstallId", "senderKeyVersion", "recipientKeyVersion"])
        for field in ["messageId", "routeId", "senderInstallId", "recipientInstallId"] { try opaqueId(value.string(field)) }
        _ = try positiveVersion(value.field("senderKeyVersion"))
        _ = try positiveVersion(value.field("recipientKeyVersion"))
    }

    private static func positiveVersion(_ value: Value?) throws -> Int {
        guard let result = value?.integer, result > 0, result <= Int(Int32.max) else { throw Failure.invalidEnvelope }
        return result
    }

    private static func opaqueId(_ value: String) throws {
        try require(!value.isEmpty && value.utf16.count <= 256 && !value.unicodeScalars.contains {
            CharacterSet.whitespacesAndNewlines.contains($0) || $0.value < 32 || (127...159).contains($0.value)
        })
    }

    private static func base64URL(_ text: String, count: Int) throws -> Data {
        try require(!text.isEmpty && text.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        })
        let standard = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        guard let data = Data(base64Encoded: standard + String(repeating: "=", count: (4 - standard.count % 4) % 4)),
              data.count == count else { throw Failure.invalidEnvelope }
        let canonical = data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        try require(canonical == text)
        return data
    }

    private static func require(_ condition: Bool) throws { if !condition { throw Failure.invalidEnvelope } }

    private static func canonicalObject(_ bytes: Data) throws -> Value {
        try require(bytes.count <= 512 * 1024)
        var parser = Parser(bytes: Array(bytes))
        let value = try parser.read(depth: 0)
        guard case .object = value else { throw Failure.invalidEnvelope }
        try require(parser.offset == bytes.count && Data(value.encoded().utf8) == bytes)
        return value
    }

    // Objects deliberately use ordered pairs, not [String: Value]: Swift String equality folds
    // canonically equivalent Unicode, whereas this protocol preserves distinct UTF-8 key bytes.
    private indirect enum Value {
        case object([(String, Value)]), array([Value]), string(String), number(String), bool(Bool), null

        func field(_ name: String) -> Value? {
            guard case let .object(members) = self else { return nil }
            return members.first { Array($0.0.utf8) == Array(name.utf8) }?.1
        }
        var integer: Int? { if case let .number(value) = self { return Int(value) }; return nil }
        func string(_ name: String) throws -> String {
            guard let value = field(name), case let .string(result) = value else { throw Failure.invalidEnvelope }
            return result
        }
        func requireFields(_ names: Set<String>) throws {
            guard case let .object(members) = self else { throw Failure.invalidEnvelope }
            try require(members.count == names.count && members.allSatisfy { names.contains($0.0) })
        }
        func removing(_ name: String) -> Value {
            guard case let .object(members) = self else { return self }
            return .object(members.filter { Array($0.0.utf8) != Array(name.utf8) })
        }
        func encoded() -> String {
            switch self {
            case let .object(members):
                return "{" + members.sorted { $0.0.utf16.lexicographicallyPrecedes($1.0.utf16) }
                    .map { quote($0.0) + ":" + $0.1.encoded() }.joined(separator: ",") + "}"
            case let .array(values): return "[" + values.map { $0.encoded() }.joined(separator: ",") + "]"
            case let .string(value): return quote(value)
            case let .number(value): return value
            case let .bool(value): return value ? "true" : "false"
            case .null: return "null"
            }
        }
    }

    private static func quote(_ text: String) -> String {
        var result = "\""
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 34: result += "\\\""
            case 92: result += "\\\\"
            case 8: result += "\\b"
            case 12: result += "\\f"
            case 10: result += "\\n"
            case 13: result += "\\r"
            case 9: result += "\\t"
            case 0..<32: result += "\\u" + String(repeating: "0", count: 4 - String(scalar.value, radix: 16).count) + String(scalar.value, radix: 16)
            default: result.unicodeScalars.append(scalar)
            }
        }
        return result + "\""
    }

    /// Strict token parser, including duplicate keys and exact decimal tokens. JSONSerialization /
    /// NSNumber are not used for authenticated bytes: they lose precision and duplicate members.
    private struct Parser {
        let bytes: [UInt8]
        var offset = 0

        mutating func read(depth: Int) throws -> Value {
            try require(depth <= 64 && offset < bytes.count)
            switch bytes[offset] {
            case 123:
                offset += 1
                var members: [(String, Value)] = []
                var seen = Set<Data>()
                if take(125) { return .object(members) }
                repeat {
                    let key = try string()
                    try require(seen.insert(Data(key.utf8)).inserted && take(58))
                    members.append((key, try read(depth: depth + 1)))
                } while take(44)
                try require(take(125))
                return .object(members)
            case 91:
                offset += 1
                var values: [Value] = []
                if take(93) { return .array(values) }
                repeat { values.append(try read(depth: depth + 1)) } while take(44)
                try require(take(93))
                return .array(values)
            case 34: return .string(try string())
            case 116: try literal("true"); return .bool(true)
            case 102: try literal("false"); return .bool(false)
            case 110: try literal("null"); return .null
            default:
                let start = offset
                while offset < bytes.count && Array("-+0123456789.eE".utf8).contains(bytes[offset]) { offset += 1 }
                let token = String(decoding: bytes[start..<offset], as: UTF8.self)
                try validateNumber(token)
                return .number(token)
            }
        }

        // Canonical decimal-only form. Keep the token verbatim, never convert it to Double.
        private func validateNumber(_ token: String) throws {
            try require(token.utf8.count <= 220 && token.range(of: #"^-?(0|[1-9][0-9]*)(\.[0-9]+)?$"#, options: .regularExpression) != nil)
            try require(token != "-0")
            let unsigned = token.hasPrefix("-") ? String(token.dropFirst()) : token
            let parts = unsigned.split(separator: ".", omittingEmptySubsequences: false)
            if parts.count == 2 { try require(parts[1].last != "0") }
            let digits = parts.joined()
            let nonzero = digits.drop(while: { $0 == "0" })
            let trailing = nonzero.reversed().prefix(while: { $0 == "0" }).count
            let precision = max(1, nonzero.count - trailing)
            let scale = (parts.count == 2 ? parts[1].count : 0) - trailing
            try require(precision <= 100 && (-100...100).contains(scale))
        }

        mutating func take(_ byte: UInt8) -> Bool {
            guard offset < bytes.count && bytes[offset] == byte else { return false }
            offset += 1
            return true
        }

        mutating func literal(_ text: String) throws {
            for byte in text.utf8 { try require(take(byte)) }
        }

        mutating func string() throws -> String {
            try require(take(34))
            var result: [UInt8] = []
            while offset < bytes.count {
                let byte = bytes[offset]
                offset += 1
                if byte == 34 {
                    guard let string = String(bytes: result, encoding: .utf8) else { throw Failure.invalidEnvelope }
                    return string
                }
                try require(byte >= 32)
                if byte != 92 { result.append(byte); continue }
                try require(offset < bytes.count)
                let escaped = bytes[offset]
                offset += 1
                switch escaped {
                case 34, 92, 47: result.append(escaped)
                case 98: result.append(8)
                case 102: result.append(12)
                case 110: result.append(10)
                case 114: result.append(13)
                case 116: result.append(9)
                case 117:
                    var code = try hex4()
                    if (0xd800...0xdbff).contains(code) {
                        try require(take(92) && take(117))
                        let low = try hex4()
                        try require((0xdc00...0xdfff).contains(low))
                        code = 0x10000 + (code - 0xd800) * 0x400 + low - 0xdc00
                    }
                    guard let scalar = UnicodeScalar(code) else { throw Failure.invalidEnvelope }
                    result.append(contentsOf: String(scalar).utf8)
                default: throw Failure.invalidEnvelope
                }
            }
            throw Failure.invalidEnvelope
        }

        mutating func hex4() throws -> UInt32 {
            try require(offset + 4 <= bytes.count)
            let digits = String(decoding: bytes[offset..<offset + 4], as: UTF8.self)
            try require(digits.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) })
            guard let value = UInt32(digits, radix: 16) else { throw Failure.invalidEnvelope }
            offset += 4
            return value
        }
    }
}
