package com.knight.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionLabel(text: String, color: Color = Ink.Faint) {
    Text(
        text = text,
        color = color,
        fontFamily = Mono,
        fontSize = LabelSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.8.sp,
    )
}

@Composable
fun StatusChip(label: String, color: Color, dot: Color = color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(label, color = color, fontFamily = Mono, fontSize = MonoSmall, letterSpacing = 1.3.sp)
    }
}

@Composable
fun AppBar(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { left() }
            right()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
    }
}

@Composable
fun Filled(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (enabled) Ink.Amber else Ink.Edge, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Outline(text: String, height: Int = 52, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .border(1.dp, Ink.Edge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ink.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Danger(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, Ink.RedEdge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ink.Red, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    divider: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Column {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Hair))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onChange(!checked) }
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = if (enabled) Ink.Text else Ink.Faint,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Checkbox(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = Ink.Amber,
                    uncheckedColor = Ink.Faint,
                    checkmarkColor = Color.Black,
                    disabledCheckedColor = Ink.Edge,
                    disabledUncheckedColor = Ink.Edge,
                ),
            )
        }
    }
}

@Composable
fun Panel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Ink.Panel, RoundedCornerShape(10.dp))
            .border(1.dp, Ink.Line, RoundedCornerShape(10.dp))
    ) { content() }
}
