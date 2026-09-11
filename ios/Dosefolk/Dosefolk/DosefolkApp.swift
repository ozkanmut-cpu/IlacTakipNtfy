import SwiftUI

@main
struct DosefolkApp: App {
    private let runtime: DosefolkRuntime?
    private let startupError: String?

    init() {
        do {
            runtime = try DosefolkRuntime()
            startupError = nil
        } catch {
            runtime = nil
            startupError = error.localizedDescription
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView(runtime: runtime, startupError: startupError)
        }
    }
}
