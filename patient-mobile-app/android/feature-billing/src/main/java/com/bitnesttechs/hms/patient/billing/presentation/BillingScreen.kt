package com.bitnesttechs.hms.patient.billing.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.billing.data.InvoiceDto
import com.bitnesttechs.hms.patient.core.designsystem.*
import java.text.NumberFormat
import java.util.Currency

@Composable
fun BillingScreen(viewModel: BillingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    when {
        state.isLoading -> HmsLoadingView("Loading invoices...")
        state.error != null -> HmsErrorView(state.error!!) { viewModel.load() }
        state.invoices.isEmpty() -> HmsEmptyState(Icons.Default.CreditCard, "No Invoices", "Your billing history will appear here.")
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Summary cards
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryCard(
                            title = "Outstanding",
                            amount = formatCurrency(state.totalOutstanding),
                            color = if (state.totalOutstanding > 0) HmsWarning else HmsSuccess,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryCard(
                            title = "Paid",
                            amount = formatCurrency(state.totalPaid),
                            color = HmsSuccess,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                items(state.invoices, key = { it.id }) { invoice ->
                    InvoiceCard(invoice = invoice)

                    // Pagination trigger
                    if (invoice.id == state.invoices.lastOrNull()?.id) {
                        LaunchedEffect(Unit) { viewModel.loadNextPage() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, amount: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    HmsCard(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = HmsTextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(amount, style = MaterialTheme.typography.headlineSmall, color = color)
    }
}

@Composable
private fun InvoiceCard(invoice: InvoiceDto) {
    HmsCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(invoice.invoiceNumber ?: "Invoice", style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
                invoice.invoiceDate?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                }
            }
            HmsStatusBadge(text = invoice.status ?: "Unknown", color = invoiceStatusColor(invoice.status))
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Total", style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                Text(formatCurrency(invoice.totalAmount ?: 0.0), style = MaterialTheme.typography.titleSmall, color = HmsTextPrimary)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (invoice.balanceDue > 0) {
                Column {
                    Text("Balance Due", style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
                    Text(formatCurrency(invoice.balanceDue), style = MaterialTheme.typography.titleSmall, color = HmsWarning)
                }
            }
        }
        invoice.hospitalName?.let { hospital ->
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Icon(Icons.Default.LocalHospital, contentDescription = null, modifier = Modifier.size(14.dp), tint = HmsTextTertiary)
                Spacer(modifier = Modifier.width(4.dp))
                Text(hospital, style = MaterialTheme.typography.bodySmall, color = HmsTextTertiary)
            }
        }
    }
}

private fun invoiceStatusColor(status: String?) = when (status?.uppercase()) {
    "PAID" -> HmsSuccess
    "PENDING", "PARTIALLY_PAID" -> HmsWarning
    "OVERDUE" -> HmsError
    "CANCELLED" -> HmsTextSecondary
    else -> HmsInfo
}

private fun formatCurrency(amount: Double): String {
    val format = NumberFormat.getCurrencyInstance()
    format.currency = Currency.getInstance("USD")
    return format.format(amount)
}
