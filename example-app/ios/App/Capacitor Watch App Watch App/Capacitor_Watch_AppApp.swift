//
//  Capacitor_Watch_AppApp.swift
//  Capacitor Watch App Watch App
//
//  Created by Michał Tremblay on 06/01/2026.
//

import SwiftUI
import WatchConnectivity
import CapgoWatchSDK

@main
struct Capacitor_Watch_App_Watch_AppApp: App {
    init() {
        // Activate the watch connector
        WatchConnector.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
