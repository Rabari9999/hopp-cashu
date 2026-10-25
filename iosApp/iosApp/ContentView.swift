import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
                .ignoresSafeArea()
                // An OPML export shared to Hopp or opened with it: read here, imported after asking.
                .onOpenURL { url in
                    guard url.isFileURL else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    let text = size <= 16 << 20 ? try? String(contentsOf: url, encoding: .utf8) : nil
                    MainViewControllerKt.openIncomingFile(text: text)
                }
    }
}
