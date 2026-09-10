import SwiftUI

struct ContentView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Image(systemName: "pills.fill")
                    .font(.system(size: 48))
                    .accessibilityHidden(true)
                Text("Dosefolk")
                    .font(.largeTitle.bold())
                Text("İlaç takibin hazır.")
                    .foregroundStyle(.secondary)
            }
            .padding()
            .navigationTitle("Bugün")
        }
    }
}

#Preview {
    ContentView()
}
