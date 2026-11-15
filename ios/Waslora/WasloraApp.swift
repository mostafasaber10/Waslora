import SwiftUI
import UIKit

@main
struct WasloraApp: App {
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
