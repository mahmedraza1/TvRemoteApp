package com.mark.tvremote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Palette {
    val Background = Color(0xFF0E0F13)
    val Surface = Color(0xFF1A1C23)
    val SurfacePressed = Color(0xFF2A2D38)
    val Accent = Color(0xFF7AA2FF)
    val Text = Color(0xFFEDEEF2)
    val TextDim = Color(0xFF8D92A3)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun Label(
    text: String,
    size: Int = 16,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Palette.Text,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, textAlign = align))
}

/**
 * A touch target that fires [onPress] immediately on touch and, with [repeat], keeps firing
 * while held (for volume and D-pad keys).
 */
@Composable
fun PressBox(
    modifier: Modifier = Modifier,
    shape: Shape,
    normal: Color = Palette.Surface,
    pressedColor: Color = Palette.SurfacePressed,
    repeat: Boolean = false,
    onPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(onPress)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (pressed) pressedColor else normal)
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    action()
                    val job = if (repeat) scope.launch {
                        delay(400)
                        while (true) {
                            action()
                            delay(110)
                        }
                    } else null
                    waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

enum class Glyph { Up, Down, Left, Right, Back, Home, Menu, PlayPause, Next, Previous, Forward, Rewind, Mute, VolUp, VolDown, Power, Keyboard, Pad, Search }

/** Simple vector icons drawn with paths so the app needs no icon library or image assets. */
@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = Palette.Text, size: Dp = 26.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

private fun DrawScope.triangle(a: Offset, b: Offset, c: Offset, color: Color, fill: Boolean = true) {
    val p = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }
    drawPath(p, color, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else Stroke(size.minDimension * 0.09f, cap = StrokeCap.Round))
}

private fun DrawScope.speaker(color: Color) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.10f, h * 0.38f); lineTo(w * 0.30f, h * 0.38f); lineTo(w * 0.52f, h * 0.18f)
        lineTo(w * 0.52f, h * 0.82f); lineTo(w * 0.30f, h * 0.62f); lineTo(w * 0.10f, h * 0.62f); close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round)
    when (glyph) {
        Glyph.Up -> triangle(Offset(w * .5f, h * .22f), Offset(w * .15f, h * .78f), Offset(w * .85f, h * .78f), color)
        Glyph.Down -> triangle(Offset(w * .5f, h * .78f), Offset(w * .15f, h * .22f), Offset(w * .85f, h * .22f), color)
        Glyph.Left -> triangle(Offset(w * .22f, h * .5f), Offset(w * .78f, h * .15f), Offset(w * .78f, h * .85f), color)
        Glyph.Right -> triangle(Offset(w * .78f, h * .5f), Offset(w * .22f, h * .15f), Offset(w * .22f, h * .85f), color)
        Glyph.Back -> {
            val p = Path().apply {
                moveTo(w * .78f, h * .18f); lineTo(w * .78f, h * .82f); lineTo(w * .18f, h * .5f); close()
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.Home -> {
            val p = Path().apply {
                moveTo(w * .5f, h * .15f); lineTo(w * .88f, h * .48f); lineTo(w * .78f, h * .48f)
                lineTo(w * .78f, h * .85f); lineTo(w * .22f, h * .85f); lineTo(w * .22f, h * .48f)
                lineTo(w * .12f, h * .48f); close()
            }
            drawPath(p, color, style = stroke)
        }
        Glyph.Menu -> for (i in 0..2) {
            val y = h * (0.28f + i * 0.22f)
            drawLine(color, Offset(w * .15f, y), Offset(w * .85f, y), w * 0.09f, StrokeCap.Round)
        }
        Glyph.PlayPause -> {
            triangle(Offset(w * .08f, h * .22f), Offset(w * .08f, h * .78f), Offset(w * .48f, h * .5f), color)
            drawRect(color, Offset(w * .58f, h * .22f), Size(w * .11f, h * .56f))
            drawRect(color, Offset(w * .78f, h * .22f), Size(w * .11f, h * .56f))
        }
        Glyph.Next -> {
            triangle(Offset(w * .15f, h * .22f), Offset(w * .15f, h * .78f), Offset(w * .65f, h * .5f), color)
            drawRect(color, Offset(w * .72f, h * .22f), Size(w * .11f, h * .56f))
        }
        Glyph.Previous -> {
            triangle(Offset(w * .85f, h * .22f), Offset(w * .85f, h * .78f), Offset(w * .35f, h * .5f), color)
            drawRect(color, Offset(w * .17f, h * .22f), Size(w * .11f, h * .56f))
        }
        Glyph.Forward -> {
            triangle(Offset(w * .08f, h * .24f), Offset(w * .08f, h * .76f), Offset(w * .48f, h * .5f), color)
            triangle(Offset(w * .5f, h * .24f), Offset(w * .5f, h * .76f), Offset(w * .9f, h * .5f), color)
        }
        Glyph.Rewind -> {
            triangle(Offset(w * .92f, h * .24f), Offset(w * .92f, h * .76f), Offset(w * .52f, h * .5f), color)
            triangle(Offset(w * .5f, h * .24f), Offset(w * .5f, h * .76f), Offset(w * .1f, h * .5f), color)
        }
        Glyph.Mute -> {
            speaker(color)
            drawLine(color, Offset(w * .62f, h * .36f), Offset(w * .9f, h * .64f), w * .09f, StrokeCap.Round)
            drawLine(color, Offset(w * .9f, h * .36f), Offset(w * .62f, h * .64f), w * .09f, StrokeCap.Round)
        }
        Glyph.VolUp -> {
            speaker(color)
            drawLine(color, Offset(w * .62f, h * .5f), Offset(w * .92f, h * .5f), w * .09f, StrokeCap.Round)
            drawLine(color, Offset(w * .77f, h * .35f), Offset(w * .77f, h * .65f), w * .09f, StrokeCap.Round)
        }
        Glyph.VolDown -> {
            speaker(color)
            drawLine(color, Offset(w * .62f, h * .5f), Offset(w * .92f, h * .5f), w * .09f, StrokeCap.Round)
        }
        Glyph.Power -> {
            drawArc(color, -60f, 300f, false, Offset(w * .2f, h * .22f), Size(w * .6f, h * .6f), style = stroke)
            drawLine(color, Offset(w * .5f, h * .12f), Offset(w * .5f, h * .48f), w * .09f, StrokeCap.Round)
        }
        Glyph.Keyboard -> {
            drawRoundRect(color, Offset(w * .08f, h * .25f), Size(w * .84f, h * .5f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = Stroke(w * .07f))
            for (i in 0..3) drawCircle(color, w * .035f, Offset(w * (0.24f + i * 0.17f), h * .41f))
            drawLine(color, Offset(w * .28f, h * .62f), Offset(w * .72f, h * .62f), w * .07f, StrokeCap.Round)
        }
        Glyph.Pad -> {
            drawRoundRect(color, Offset(w * .1f, h * .1f), Size(w * .8f, h * .8f), androidx.compose.ui.geometry.CornerRadius(w * .14f), style = Stroke(w * .07f))
            drawCircle(color, w * .07f, Offset(w * .5f, h * .5f))
        }
        Glyph.Search -> {
            drawCircle(color, w * .22f, Offset(w * .42f, h * .42f), style = stroke)
            drawLine(color, Offset(w * .6f, h * .6f), Offset(w * .85f, h * .85f), w * .09f, StrokeCap.Round)
        }
    }
}

@Composable
fun GlyphButton(
    glyph: Glyph,
    shape: Shape,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    normal: Color = Palette.Surface,
    tint: Color = Palette.Text,
    onPress: () -> Unit,
) {
    PressBox(modifier, shape, normal = normal, repeat = repeat, onPress = onPress) {
        GlyphIcon(glyph, color = tint)
    }
}

fun Modifier.hairline(shape: Shape): Modifier = this.border(1.dp, Color(0x22FFFFFF), shape)
