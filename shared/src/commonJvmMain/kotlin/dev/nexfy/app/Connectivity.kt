package dev.nexfy.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Whether the machine reaches the internet now: kept by the app, read by the screens. */
object Connectivity {
    val online = MutableStateFlow(true)
}
