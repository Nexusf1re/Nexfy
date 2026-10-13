package dev.sonata.app

import android.app.Application
import dev.sonata.app.playback.CoreBridge
import dev.sonata.core.NativeInit
import uniffi.sonata_core.SonataCore

/** Owns the single Rust core, shared by the UI and the playback service. */
class SonataApp : Application() {
    val core: SonataCore by lazy {
        NativeInit.init(this)
        SonataCore(filesDir.resolve("spotify").path)
    }

    val bridge: CoreBridge by lazy { CoreBridge(core) }

    val library: LibraryPrefs by lazy { LibraryPrefs(this) }
}
