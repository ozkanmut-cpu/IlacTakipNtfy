import Foundation

struct LocalStore {
    enum Collection: String {
        case medications
        case medicationMeta
        case doseEvents
        case circlePeers
        case stock
        case programRules
        case remoteEventReceipts
        case revokedPeers
        case doseRuntime
        case programOrdering
        case circlePresence
        case remoteCapabilities
        case capabilityOrdering
    }

    private let directory: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(directory: URL? = nil) throws {
        if let directory {
            self.directory = directory
        } else {
            let base = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            self.directory = base.appendingPathComponent("Dosefolk", isDirectory: true)
        }
        try FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
    }

    func load<T: Decodable>(_ type: T.Type, from collection: Collection, default defaultValue: T) throws -> T {
        let url = fileURL(for: collection)
        guard FileManager.default.fileExists(atPath: url.path) else { return defaultValue }
        return try decoder.decode(T.self, from: Data(contentsOf: url))
    }

    func save<T: Encodable>(_ value: T, to collection: Collection) throws {
        let data = try encoder.encode(value)
        try data.write(to: fileURL(for: collection), options: [.atomic])
    }

    func remove(_ collection: Collection) throws {
        let url = fileURL(for: collection)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try FileManager.default.removeItem(at: url)
    }

    private func fileURL(for collection: Collection) -> URL {
        directory.appendingPathComponent("\(collection.rawValue).json")
    }
}
