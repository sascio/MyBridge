package com.nuvio.app.features.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.navigation.MediaEnvironment
import com.nuvio.app.core.logging.InAppLogger
import platform.UIKit.UIColor
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIUserInterfaceStyle

/** A full-screen presentation, never an adaptive iPad sheet or a navigation-stack push. */
@Composable
internal actual fun MediaEnvironmentHost(environment: MediaEnvironment, onExit: () -> Unit) {
    val parent = LocalUIViewController.current
    val latestExit = rememberUpdatedState(onExit)
    DisposableEffect(parent, environment) {
        val controller = ComposeUIViewController {
            MediaEnvironmentContent(environment) { latestExit.value() }
        }
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        controller.overrideUserInterfaceStyle = UIUserInterfaceStyle.UIUserInterfaceStyleDark
        // Exit is explicit. Do not let a sheet swipe dismiss UIKit without clearing shared state.
        controller.isModalInPresentation = true
        controller.view.backgroundColor = UIColor.blackColor
        if (parent.presentedViewController != null || parent.view.window == null) {
            InAppLogger.warn("Media/Navigation", "Cannot present media over an occupied or detached iOS controller")
            latestExit.value()
        } else {
            // No asynchronous animation window for rapid Exit/open requests to race against.
            parent.presentViewController(controller, animated = false, completion = null)
        }
        onDispose {
            if (controller.presentingViewController != null) {
                controller.dismissViewControllerAnimated(flag = false, completion = null)
            }
        }
    }
}
