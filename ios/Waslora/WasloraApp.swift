import SwiftUI
import UIKit

@main
struct WasloraApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor(WasloraAppDelegate.self) private var appDelegate
    @StateObject private var downloads = DownloadManager.shared
    @StateObject private var library = LibraryStore()
    @StateObject private var transfer = LocalTransferServer()

    var body: some Scene {
        WindowGroup {
            MainTabsView()
                .environmentObject(downloads)
                .environmentObject(library)
                .environmentObject(transfer)
                .tint(Color(red: 0.12, green: 0.70, blue: 0.65))
                .onAppear { library.reload() }
                .onChange(of: scenePhase) { phase in
                    if phase == .background { transfer.stop() }
                    if phase == .active { library.reload() }
                }
        }
    }
}

final class WasloraAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        DownloadManager.shared.setBackgroundCompletionHandler(completionHandler)
    }
}
