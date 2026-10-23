package dev.nexfy.app.ui.design

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** The screen transition's shared-element scope, when screens are shown inside one. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The screen being shown, for shared elements to know when it comes and goes. */
val LocalScreenScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * A cover that travels between screens: the card's cover and the opened
 * page's header carry the same [key], and the transition morphs one into
 * the other. No key, or no transition around, leaves the cover as is.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCover(key: String?, shape: Shape): Modifier {
    val shared = LocalSharedScope.current
    val screen = LocalScreenScope.current
    if (key == null || shared == null || screen == null) return this
    return with(shared) {
        this@sharedCover.sharedElement(
            rememberSharedContentState(key),
            screen,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
