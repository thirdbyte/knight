package com.knight.console.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * True black so the OLED pixels are actually off. Amber carries phone to
 * console, sky carries console to phone: they differ in lightness as well as
 * hue, so direction reads without relying on colour.
 */
object Ink {
    val Ground = Color(0xFF000000)
    val Panel = Color(0xFF0B0B0B)
    val Line = Color(0xFF1C1C1C)
    val Hair = Color(0xFF141414)
    val Edge = Color(0xFF2A2A2A)
    val Text = Color(0xFFF2F2F2)
    val Dim = Color(0xFF8A8A8A)
    val Faint = Color(0xFF737373)
    val Amber = Color(0xFFFF9F0A)
    val AmberSkin = Color(0xFF16120A)
    val AmberEdge = Color(0xFF2A2009)
    val Red = Color(0xFFFF6B6B)
    val RedEdge = Color(0xFF2A1414)
}

val Mono = FontFamily.Monospace

val LabelSize = 10.sp
val MonoSmall = 11.sp
