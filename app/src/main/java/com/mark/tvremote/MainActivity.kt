package com.mark.tvremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mark.tvremote.core.Key

class MainActivity : ComponentActivity() {
    private lateinit var controller: Controller
    private lateinit var discovery: Discovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = Controller(applicationContext)
        discovery = Discovery(this)
        setContent { App(controller, discovery) }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        controller.resume()
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
        controller.disconnect()
    }

    // The phone's volume buttons control the TV while connected.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (controller.state == State.Ready) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { controller.key(Key.VOLUME_UP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { controller.key(Key.VOLUME_DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun App(controller: Controller, discovery: Discovery) {
    // Back leaves the pairing and connecting screens instead of closing the app.
    val inFlow = controller.state != State.Idle && controller.state != State.Ready
    BackHandler(enabled = inFlow && !controller.reconnecting) { controller.disconnect(forget = true) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            controller.state == State.Ready || controller.reconnecting -> RemoteScreen(controller)
            controller.state in setOf(State.NeedsPairing, State.StartingPairing, State.EnterCode, State.FinishingPairing) ->
                PairScreen(controller)
            else -> HomeScreen(controller, discovery)
        }
    }
}
