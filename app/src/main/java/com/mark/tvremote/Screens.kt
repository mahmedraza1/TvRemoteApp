package com.mark.tvremote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mark.tvremote.core.Key
import kotlin.math.abs
import kotlin.math.sign

private val Card = RoundedCornerShape(20.dp)
private val InkOnAccent = Color(0xFF0E0F13)

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PressBox(
        modifier.fillMaxWidth().height(52.dp),
        RoundedCornerShape(16.dp),
        normal = if (enabled) Palette.Accent else Palette.Surface,
        pressedColor = if (enabled) Color(0xFFA9C3FF) else Palette.Surface,
        onPress = { if (enabled) onClick() },
    ) { Label(text, 17, FontWeight.SemiBold, if (enabled) InkOnAccent else Palette.TextDim) }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PressBox(modifier.fillMaxWidth().height(48.dp), RoundedCornerShape(16.dp), onPress = onClick) {
        Label(text, 16, FontWeight.Medium, Palette.TextDim)
    }
}

@Composable
private fun TvRow(name: String, host: String, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        PressBox(Modifier.weight(1f), Card, onPress = onClick) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Label(name, 17, FontWeight.SemiBold)
                Label(host, 13, color = Palette.TextDim)
            }
        }
        if (onForget != null) {
            Spacer(Modifier.width(8.dp))
            PressBox(Modifier.size(52.dp), CircleShape, onPress = onForget) { Label("✕", 16, color = Palette.TextDim) }
        }
    }
}

@Composable
fun HomeScreen(c: Controller, discovery: Discovery) {
    var ip by remember { mutableStateOf("") }
    val savedHosts = c.saved.map { it.host }
    val nearby = discovery.found.filter { it.host !in savedHosts }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Label("TV Remote", 32, FontWeight.Bold)
        Label("Control an Android TV or Google TV on your Wi-Fi", 15, color = Palette.TextDim)
        Spacer(Modifier.height(8.dp))

        if (c.state == State.Connecting) {
            Column(Modifier.fillMaxWidth().clip(Card).background(Palette.Surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("Connecting to ${c.tvName}…", 16, color = Palette.Accent)
                SecondaryButton("Cancel") { c.disconnect(forget = true) }
            }
        }
        c.message?.let { Label(it, 14, color = Palette.Danger) }

        if (c.saved.isNotEmpty()) {
            Label("Your TVs", 14, FontWeight.SemiBold, Palette.TextDim)
            c.saved.toList().forEach { tv ->
                TvRow(tv.name, tv.host, onClick = { c.connect(tv.host, tv.name) }, onForget = { c.forget(tv) })
            }
        }

        Label("On your network", 14, FontWeight.SemiBold, Palette.TextDim)
        if (nearby.isEmpty()) {
            Label("Searching…  Make sure the TV is on and on the same Wi-Fi.", 14, color = Palette.TextDim)
        }
        nearby.forEach { tv -> TvRow(tv.name, tv.host, onClick = { c.connect(tv.host, tv.name) }) }

        Spacer(Modifier.height(4.dp))
        Label("Add by IP address", 14, FontWeight.SemiBold, Palette.TextDim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = ip,
                onValueChange = { ip = it.filter { ch -> ch.isDigit() || ch == '.' }.take(15) },
                singleLine = true,
                textStyle = TextStyle(color = Palette.Text, fontSize = 17.sp),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ip.isNotBlank()) c.connect(ip) }),
                modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    Box { if (ip.isEmpty()) Label("192.168.1.20", 17, color = Palette.TextDim); inner() }
                },
            )
            PressBox(Modifier.width(110.dp).height(52.dp), RoundedCornerShape(16.dp), normal = Palette.Accent, pressedColor = Color(0xFFA9C3FF), onPress = { if (ip.isNotBlank()) c.connect(ip) }) {
                Label("Connect", 16, FontWeight.SemiBold, InkOnAccent)
            }
        }

        if (c.log.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Label("Connection log", 14, FontWeight.SemiBold, Palette.TextDim)
            c.log.toList().forEach { Label(it, 12, color = Palette.TextDim) }
        }
    }
}

@Composable
fun PairScreen(c: Controller) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(32.dp))
        Label("Pair with ${c.tvName}", 28, FontWeight.Bold)
        when (c.state) {
            State.NeedsPairing -> {
                Label("This phone isn't paired with the TV yet. The TV will show a 6-character code; type it here.", 16, color = Palette.TextDim)
                c.message?.let { Label(it, 14, color = Palette.Danger) }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Start pairing") { c.startPairing() }
            }
            State.StartingPairing -> Label("Contacting the TV…", 16, color = Palette.Accent)
            State.EnterCode, State.FinishingPairing -> {
                Label("Enter the code shown on your TV screen (0-9 and A-F).", 16, color = Palette.TextDim)
                c.message?.let { Label(it, 14, color = Palette.Danger) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                BasicTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { ch -> ch in "0123456789ABCDEF" }.take(6) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 34.sp, fontFamily = FontFamily.Monospace, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6) c.submitCode(code) }),
                    modifier = Modifier.fillMaxWidth().height(72.dp).focusRequester(focus).clip(Card).background(Palette.Surface).padding(vertical = 14.dp, horizontal = 12.dp),
                )
                PrimaryButton(if (c.state == State.FinishingPairing) "Pairing…" else "Pair", enabled = code.length == 6 && c.state == State.EnterCode) { c.submitCode(code) }
            }
            else -> {}
        }
        SecondaryButton("Cancel") { c.disconnect(forget = true) }
        if (c.log.isNotEmpty()) {
            Label("Log", 13, FontWeight.SemiBold, Palette.TextDim)
            c.log.toList().forEach { Label(it, 12, color = Palette.TextDim) }
        }
    }
}

@Composable
private fun TouchPad(modifier: Modifier, onKey: (Int) -> Unit) {
    val density = LocalDensity.current
    val step = with(density) { 46.dp.toPx() }
    val slop = with(density) { 10.dp.toPx() }
    Box(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(Palette.Surface)
            .hairline(RoundedCornerShape(36.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var total = Offset.Zero
                    var acc = Offset.Zero
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.first()
                        if (!change.pressed) {
                            if (!moved) onKey(Key.DPAD_CENTER)
                            break
                        }
                        val delta = change.positionChange()
                        total += delta
                        acc += delta
                        if (!moved && total.getDistance() > slop) moved = true
                        if (moved) {
                            // One D-pad key per `step` of travel, along the dominant axis.
                            while (abs(acc.x) >= step || abs(acc.y) >= step) {
                                if (abs(acc.x) >= abs(acc.y)) {
                                    onKey(if (acc.x > 0) Key.DPAD_RIGHT else Key.DPAD_LEFT)
                                    acc = Offset(acc.x - sign(acc.x) * step, 0f)
                                } else {
                                    onKey(if (acc.y > 0) Key.DPAD_DOWN else Key.DPAD_UP)
                                    acc = Offset(0f, acc.y - sign(acc.y) * step)
                                }
                            }
                        }
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlyphIcon(Glyph.Pad, color = Palette.TextDim, size = 40.dp)
            Label("Swipe to move", 15, color = Palette.TextDim)
            Label("Tap to select", 15, color = Palette.TextDim)
        }
    }
}

private const val SENTINEL = "​"

/**
 * Mirrors what's typed on the phone's keyboard to the TV. A zero-width sentinel keeps the field
 * non-empty so a backspace on an empty field is still seen as a text change.
 */
@Composable
private fun KeyboardPanel(c: Controller) {
    var value by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(1))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    fun reset() { value = TextFieldValue(SENTINEL, TextRange(1)) }

    Column(Modifier.fillMaxWidth().clip(Card).background(Palette.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                if (new.text.isEmpty()) {
                    c.key(Key.DEL)
                    reset()
                } else {
                    val old = value.text.removePrefix(SENTINEL)
                    val typed = new.text.removePrefix(SENTINEL)
                    var common = 0
                    while (common < old.length && common < typed.length && old[common] == typed[common]) common++
                    repeat(old.length - common) { c.key(Key.DEL) }
                    if (typed.length > common) c.text(typed.substring(common))
                    value = new.copy(text = SENTINEL + typed)
                }
            },
            textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { c.key(Key.ENTER); reset() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(12.dp)).background(Palette.Background).padding(14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text == SENTINEL) Label("Type here, it appears on the TV", 18, color = Palette.TextDim)
                    inner()
                }
            },
        )
        Label(
            if (c.textFieldFocused) "Text field open on the TV" else "Open a text field on the TV first (e.g. a search box)",
            13, color = if (c.textFieldFocused) Palette.Accent else Palette.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PressBox(Modifier.weight(1f).height(44.dp), RoundedCornerShape(12.dp), normal = Palette.Background, onPress = { c.key(Key.DEL) }) { Label("Delete", 15, color = Palette.Text) }
            PressBox(Modifier.weight(1f).height(44.dp), RoundedCornerShape(12.dp), normal = Palette.Accent, pressedColor = Color(0xFFA9C3FF), onPress = { c.key(Key.ENTER); reset() }) { Label("Enter", 15, FontWeight.SemiBold, InkOnAccent) }
        }
    }
}

@Composable
fun RemoteScreen(c: Controller) {
    var touchpad by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    val round = RoundedCornerShape(18.dp)
    val key = c::key

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (c.reconnecting) Palette.Danger else Color(0xFF4ADE80)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label(c.tvName, 18, FontWeight.SemiBold)
                if (c.reconnecting) Label("Reconnecting…", 13, color = Palette.Danger)
            }
            GlyphButton(Glyph.Power, CircleShape, Modifier.size(46.dp), tint = Palette.Danger) { key(Key.POWER) }
            Spacer(Modifier.width(8.dp))
            PressBox(Modifier.height(46.dp), RoundedCornerShape(23.dp), onPress = { c.disconnect(forget = true) }) {
                Label("TVs", 15, FontWeight.Medium, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(4.dp)) {
            listOf(false to "Buttons", true to "Touchpad").forEach { (isPad, title) ->
                PressBox(
                    Modifier.weight(1f).height(40.dp), RoundedCornerShape(12.dp),
                    normal = if (touchpad == isPad) Palette.SurfacePressed else Palette.Surface,
                    onPress = { touchpad = isPad },
                ) { Label(title, 15, FontWeight.Medium, if (touchpad == isPad) Palette.Text else Palette.TextDim) }
            }
        }

        if (keyboard) KeyboardPanel(c)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (touchpad) {
                TouchPad(Modifier.size(width = 300.dp, height = 270.dp), key)
            } else {
                Box(Modifier.size(280.dp).clip(CircleShape).background(Palette.Surface).hairline(CircleShape), contentAlignment = Alignment.Center) {
                    GlyphButton(Glyph.Up, CircleShape, Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(76.dp), repeat = true, normal = Color.Transparent) { key(Key.DPAD_UP) }
                    GlyphButton(Glyph.Down, CircleShape, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size(76.dp), repeat = true, normal = Color.Transparent) { key(Key.DPAD_DOWN) }
                    GlyphButton(Glyph.Left, CircleShape, Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(76.dp), repeat = true, normal = Color.Transparent) { key(Key.DPAD_LEFT) }
                    GlyphButton(Glyph.Right, CircleShape, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(76.dp), repeat = true, normal = Color.Transparent) { key(Key.DPAD_RIGHT) }
                    PressBox(Modifier.size(96.dp), CircleShape, normal = Palette.Accent, pressedColor = Color(0xFFA9C3FF), onPress = { key(Key.DPAD_CENTER) }) {
                        Label("OK", 22, FontWeight.Bold, InkOnAccent)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphButton(Glyph.Back, round, Modifier.weight(1f).height(58.dp)) { key(Key.BACK) }
            GlyphButton(Glyph.Home, round, Modifier.weight(1f).height(58.dp)) { key(Key.HOME) }
            GlyphButton(Glyph.Menu, round, Modifier.weight(1f).height(58.dp)) { key(Key.MENU) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphButton(Glyph.VolDown, round, Modifier.weight(1f).height(58.dp), repeat = true) { key(Key.VOLUME_DOWN) }
            GlyphButton(Glyph.Mute, round, Modifier.weight(1f).height(58.dp)) { key(Key.MUTE) }
            GlyphButton(Glyph.VolUp, round, Modifier.weight(1f).height(58.dp), repeat = true) { key(Key.VOLUME_UP) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlyphButton(Glyph.Previous, round, Modifier.weight(1f).height(52.dp)) { key(Key.MEDIA_PREVIOUS) }
            GlyphButton(Glyph.Rewind, round, Modifier.weight(1f).height(52.dp)) { key(Key.MEDIA_REWIND) }
            GlyphButton(Glyph.PlayPause, round, Modifier.weight(1.2f).height(52.dp)) { key(Key.MEDIA_PLAY_PAUSE) }
            GlyphButton(Glyph.Forward, round, Modifier.weight(1f).height(52.dp)) { key(Key.MEDIA_FAST_FORWARD) }
            GlyphButton(Glyph.Next, round, Modifier.weight(1f).height(52.dp)) { key(Key.MEDIA_NEXT) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PressBox(
                Modifier.weight(1f).height(54.dp), round,
                normal = if (keyboard) Palette.SurfacePressed else Palette.Surface,
                onPress = { keyboard = !keyboard },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlyphIcon(Glyph.Keyboard, color = Palette.Accent)
                    Label(if (keyboard) "Hide keyboard" else "Keyboard", 16, FontWeight.Medium)
                }
            }
            GlyphButton(Glyph.Search, round, Modifier.width(76.dp).height(54.dp)) { key(Key.SEARCH) }
        }
        Spacer(Modifier.height(8.dp))
    }
}
