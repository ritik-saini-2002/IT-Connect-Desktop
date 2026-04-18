package com.itconnect.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.itconnect.desktop.data.PcControlRepository
import com.itconnect.desktop.data.PcPlan

/**
 * Root shell for the desktop app. For v1 (Phase 3–5 landed) this renders
 * a navigation rail + a Plans list backed by the real SQLite database.
 * Remaining tabs are placeholders — see docs/DESKTOP_PORT_PLAN.md §0.
 */
@Composable
fun AppShell(repo: PcControlRepository) {
    MaterialTheme {
        var selected by remember { mutableStateOf(NavTab.Plans) }
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                NavTab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = "IT Connect — ${selected.label}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(12.dp))
                when (selected) {
                    NavTab.Plans -> PlansPane(repo)
                    else -> PlaceholderPane(selected)
                }
            }
        }
    }
}

private enum class NavTab(val label: String, val icon: ImageVector) {
    Plans("Plans", Icons.Default.PlayArrow),
    Devices("Devices", Icons.Default.Devices),
    Touchpad("Touchpad", Icons.Default.TouchApp),
    Keyboard("Keyboard", Icons.Default.Keyboard),
    Schedules("Schedules", Icons.Default.Schedule),
}

@Composable
private fun PlansPane(repo: PcControlRepository) {
    val plans by repo.allPlans.collectAsState(initial = emptyList())
    if (plans.isEmpty()) {
        Text("No plans yet. Seed is running in the background…")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(plans, key = { it.planId }) { plan -> PlanRow(plan) }
    }
}

@Composable
private fun PlanRow(plan: PcPlan) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(plan.icon, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.planName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${plan.steps.size} step(s) · ${plan.planId}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PlaceholderPane(tab: NavTab) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
    ) {
        Icon(tab.icon, null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text("${tab.label} — coming soon", style = MaterialTheme.typography.titleLarge)
        Text(
            "This module is part of a future phase in docs/DESKTOP_PORT_PLAN.md.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
