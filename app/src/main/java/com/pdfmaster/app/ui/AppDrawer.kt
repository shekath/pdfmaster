package com.pdfmaster.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfmaster.app.BuildConfig
import com.pdfmaster.app.tools.Category
import com.pdfmaster.app.tools.Tool
import com.pdfmaster.app.tools.Tools

/** Side menu: every tool in a Category → Tool hierarchy, plus appearance and about. */
@Composable
fun AppDrawer(vm: MainVm, onPick: (Tool) -> Unit) {
    val open = remember { mutableStateMapOf<Category, Boolean>() }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.width(320.dp)) {
        LazyColumn(Modifier.padding(horizontal = 12.dp).testTag("drawer")) {
            item {
                Column(Modifier.padding(start = 12.dp, top = 20.dp, bottom = 12.dp)) {
                    Text("PDF Master", style = MaterialTheme.typography.titleLarge)
                    Text("${Tools.all.size} tools in ${Category.entries.size} groups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionLabel("Tools", Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) }
            Category.entries.forEach { c ->
                val tools = Tools.all.filter { it.category == c }
                val expanded = open[c] == true
                item(key = "cat_${c.name}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().testTag("cat_${c.name}").clip16().clickable { open[c] = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        ToolIcon(c.icon, c.tint, 32.dp)
                        Text(c.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("${tools.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand", Modifier.padding(start = 6.dp))
                    }
                }
                item(key = "list_${c.name}") {
                    AnimatedVisibility(expanded) {
                        Column(Modifier.padding(start = 28.dp, bottom = 6.dp)) {
                            tools.forEach { t ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clip16().clickable { onPick(t) }.padding(horizontal = 12.dp, vertical = 9.dp)
                                ) {
                                    Icon(t.icon, null, tint = c.tint, modifier = Modifier.size(20.dp))
                                    Column(Modifier.padding(start = 14.dp)) {
                                        Text(t.title, style = MaterialTheme.typography.bodyMedium)
                                        Text(t.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            item {
                SectionLabel("Appearance", Modifier.padding(start = 12.dp, bottom = 8.dp))
                val modes = listOf(Triple("light", "Light", Icons.Outlined.LightMode), Triple("dark", "Dark", Icons.Outlined.DarkMode), Triple("system", "System", Icons.Outlined.PhoneAndroid))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    modes.forEachIndexed { i, (key, label, icon) ->
                        SegmentedButton(
                            selected = vm.themeMode == key, onClick = { vm.setTheme(key) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = { Icon(icon, null, Modifier.size(16.dp)) },
                        ) { Text(label) }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
            item {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Your files are processed on this phone and never uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

private fun Modifier.clip16() = clip(RoundedCornerShape(14.dp))
