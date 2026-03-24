package com.bitnesttechs.hms.patient.treatmentplans.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.core.designsystem.*
import com.bitnesttechs.hms.patient.treatmentplans.data.TreatmentPlanDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreatmentPlansScreen(viewModel: TreatmentPlansViewModel = hiltViewModel()) {
    val plans by viewModel.plans.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    var selectedPlan by remember { mutableStateOf<TreatmentPlanDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Treatment Plans") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
            )
        }
    ) { padding ->
        when {
            isLoading && plans.isEmpty() -> HmsLoadingView("Loading treatment plans...", modifier = Modifier.padding(padding))
            error != null && plans.isEmpty() -> HmsErrorView(
                message = error ?: "Failed to load treatment plans",
                onRetry = { viewModel.refresh() },
                modifier = Modifier.padding(padding)
            )
            plans.isEmpty() -> HmsEmptyState(
                icon = Icons.Default.ListAlt,
                title = "No Treatment Plans",
                message = "You have no active treatment plans.",
                modifier = Modifier.padding(padding)
            )
            selectedPlan != null -> {
                PlanDetailContent(
                    plan = selectedPlan!!,
                    onBack = { selectedPlan = null },
                    modifier = Modifier.padding(padding)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(plans, key = { it.id }) { plan ->
                        PlanCard(plan) { selectedPlan = plan }
                    }
                    if (hasMore) {
                        item {
                            LaunchedEffect(Unit) { viewModel.loadPlans() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = HmsPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(plan: TreatmentPlanDto, onClick: () -> Unit) {
    HmsCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.planName ?: "Treatment Plan",
                    style = MaterialTheme.typography.titleSmall,
                    color = HmsTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(plan.status ?: "unknown")
            }
            plan.diagnosis?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
            }
            Row {
                plan.createdByName?.let {
                    Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = HmsTextTertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                    Spacer(Modifier.width(12.dp))
                }
                plan.startDate?.let {
                    Icon(Icons.Default.CalendarToday, null, Modifier.size(14.dp), tint = HmsTextTertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                }
            }
            plan.goals?.let { goals ->
                if (goals.isNotEmpty()) {
                    val completed = goals.count { it.status?.lowercase() == "completed" }
                    LinearProgressIndicator(
                        progress = { completed.toFloat() / goals.size },
                        modifier = Modifier.fillMaxWidth(),
                        color = HmsPrimary,
                        trackColor = HmsPrimary.copy(alpha = 0.15f)
                    )
                    Text(
                        "$completed/${goals.size} goals completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = HmsTextTertiary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDetailContent(plan: TreatmentPlanDto, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier) {
        TopAppBar(
            title = { Text("Plan Details") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = HmsSurface)
        )
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                HmsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(plan.planName ?: "Treatment Plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(plan.status ?: "unknown")
                        }
                        plan.diagnosis?.let { Text("Diagnosis: $it", style = MaterialTheme.typography.bodyMedium, color = HmsTextSecondary) }
                        plan.createdByName?.let { Row { Icon(Icons.Default.Person, null, Modifier.size(16.dp), tint = HmsTextTertiary); Spacer(Modifier.width(4.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary) } }
                        plan.createdBySpecialty?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) }
                        Row {
                            plan.startDate?.let { Text("Start: ${it.take(10)}", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary); Spacer(Modifier.width(16.dp)) }
                            plan.endDate?.let { Text("End: ${it.take(10)}", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) }
                        }
                    }
                }
            }
            plan.description?.let { desc ->
                item {
                    HmsSectionHeader(title = "Description")
                    HmsCard { Text(desc, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            plan.goals?.let { goals ->
                if (goals.isNotEmpty()) {
                    item { HmsSectionHeader(title = "Goals") }
                    items(goals, key = { it.id }) { goal ->
                        HmsCard {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (goal.status?.lowercase() == "completed") Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        null, Modifier.size(20.dp),
                                        tint = if (goal.status?.lowercase() == "completed") HmsSuccess else HmsTextTertiary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(goal.goalDescription ?: "Goal", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    StatusChip(goal.status ?: "pending")
                                }
                                goal.progressPercentage?.let { pct ->
                                    LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = HmsPrimary,
                                        trackColor = HmsPrimary.copy(alpha = 0.15f)
                                    )
                                    Text("$pct% complete", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary)
                                }
                                goal.targetDate?.let { Text("Target: ${it.take(10)}", style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) }
                            }
                        }
                    }
                }
            }
            plan.activities?.let { activities ->
                if (activities.isNotEmpty()) {
                    item { HmsSectionHeader(title = "Activities") }
                    items(activities, key = { it.id }) { activity ->
                        HmsCard {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(activity.description ?: activity.activityType ?: "Activity", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    StatusChip(activity.status ?: "pending")
                                }
                                activity.frequency?.let { Row { Icon(Icons.Default.Repeat, null, Modifier.size(14.dp), tint = HmsTextTertiary); Spacer(Modifier.width(4.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) } }
                                activity.duration?.let { Row { Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = HmsTextTertiary); Spacer(Modifier.width(4.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) } }
                                activity.assignedTo?.let { Row { Icon(Icons.Default.Person, null, Modifier.size(14.dp), tint = HmsTextTertiary); Spacer(Modifier.width(4.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = HmsTextTertiary) } }
                            }
                        }
                    }
                }
            }
            plan.notes?.let { notes ->
                item {
                    HmsSectionHeader(title = "Notes")
                    HmsCard { Text(notes, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status.lowercase()) {
        "active" -> HmsSuccess
        "completed" -> HmsInfo
        "cancelled" -> HmsError
        "on_hold", "on hold" -> HmsWarning
        else -> HmsTextTertiary
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            status.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
