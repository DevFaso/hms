package com.bitnesttechs.hms.patient.features.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(viewModel: BillingViewModel = hiltViewModel()) {
    val invoices by viewModel.invoices.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Billing & Invoices") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Outstanding balance banner
            val outstanding = viewModel.totalOutstanding
            if (outstanding > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Outstanding Balance", style = MaterialTheme.typography.bodyMedium)
                            Text("${"%.2f".format(outstanding)}", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = WarningAmber)
                        }
                    }
                }
            }

            if (invoices.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No invoices found", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            items(invoices) { invoice ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(invoice.invoiceNumber, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            StatusBadge(
                                text = invoice.statusDisplay,
                                color = when {
                                    invoice.isPaid -> SuccessGreen
                                    invoice.isCancelled -> NeutralGrey
                                    else -> WarningAmber
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        invoice.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        invoice.invoiceDate?.let { Text("Date: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total", style = MaterialTheme.typography.bodySmall)
                            Text("${"%.2f".format(invoice.totalAmount)}", style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                        if (invoice.paidAmount > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Paid", style = MaterialTheme.typography.bodySmall)
                                Text("${"%.2f".format(invoice.paidAmount)}", style = MaterialTheme.typography.bodySmall,
                                    color = SuccessGreen)
                            }
                        }
                        if (!invoice.isPaid && invoice.balanceDue > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Balance Due", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text("${"%.2f".format(invoice.balanceDue)}", style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold, color = WarningAmber)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
