package com.bitnesttechs.hms.patient.features.billing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.core.models.InvoiceDto
import com.bitnesttechs.hms.patient.features.dashboard.StatusBadge
import com.bitnesttechs.hms.patient.ui.theme.*
import java.util.Locale

/** Same choices the web portal and the iOS payment sheet offer. */
private val PAYMENT_METHODS = listOf("MOBILE_MONEY", "CASH", "CARD", "BANK_TRANSFER")

@Composable
private fun methodLabel(method: String): String = when (method) {
    "MOBILE_MONEY" -> stringResource(R.string.payment_method_mobile_money)
    "CASH" -> stringResource(R.string.payment_method_cash)
    "CARD" -> stringResource(R.string.payment_method_card)
    "BANK_TRANSFER" -> stringResource(R.string.payment_method_bank_transfer)
    else -> method
}

/** PatientPaymentRequestDTO: @Size(max = 500) reference, @Size(max = 1000) notes. */
private const val REFERENCE_MAX = 500
private const val NOTES_MAX = 1000

private fun money(amount: Double): String = String.format(Locale.getDefault(), "%,.0f FCFA", amount)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(onBack: () -> Unit = {}, viewModel: BillingViewModel = hiltViewModel()) {
    val invoices by viewModel.invoices.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val isPaying by viewModel.isPaying.collectAsState()
    var payTarget by remember { mutableStateOf<InvoiceDto?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val recorded = stringResource(R.string.payment_recorded)
    val failed = stringResource(R.string.payment_failed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Snackbars run in their own coroutine so this collector never stalls.
        viewModel.events.collect { event ->
            when (event) {
                BillingEvent.PaymentRecorded -> {
                    // The sheet closes here, not on tap: closing early re-enabled
                    // the card's Pay button while the POST was in flight, and the
                    // backend has no lock, so a second tap recorded the same
                    // payment twice.
                    payTarget = null
                    scope.launch { snackbarHostState.showSnackbar(recorded) }
                }
                is BillingEvent.PaymentFailed ->
                    scope.launch { snackbarHostState.showSnackbar(event.detail?.let { "$failed ($it)" } ?: failed) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.billing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandBlue, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandBlue)
            }
            return@Scaffold
        }

        loadError?.let { error ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.CloudOff, null, tint = ErrorRed, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.invoices_load_failed, error), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                            Text(stringResource(R.string.outstanding_balance), style = MaterialTheme.typography.bodyMedium)
                            Text(money(outstanding), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = WarningAmber)
                        }
                    }
                }
            }

            if (invoices.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_invoices), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            items(invoices) { invoice ->
                InvoiceCard(invoice = invoice, payEnabled = !isPaying, onPay = { payTarget = invoice })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    payTarget?.let { invoice ->
        PaymentSheet(
            invoice = invoice,
            isPaying = isPaying,
            onDismiss = { payTarget = null },
            onPay = { amount, method, reference, notes ->
                viewModel.pay(invoice.id, amount, method, reference, notes)
            }
        )
    }
}

@Composable
private fun InvoiceCard(invoice: InvoiceDto, payEnabled: Boolean, onPay: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(invoice.invoiceNumber, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
            invoice.invoiceDate?.let {
                Text(stringResource(R.string.invoice_date, it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            AmountRow(stringResource(R.string.invoice_total), money(invoice.totalAmount))
            if (invoice.paidAmount > 0) {
                AmountRow(stringResource(R.string.invoice_paid), money(invoice.paidAmount), SuccessGreen)
            }
            // The endpoint accepts SENT and PARTIALLY_PAID only; a DRAFT would
            // reach a 400 after the sheet, so it gets no Pay button.
            val payable = invoice.status.uppercase() in listOf("SENT", "PARTIALLY_PAID") && invoice.balanceDue > 0
            if (payable) {
                AmountRow(stringResource(R.string.balance_due), money(invoice.balanceDue), WarningAmber, bold = true)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onPay,
                    enabled = payEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Icon(Icons.Default.Payments, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pay_invoice))
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(value, style = MaterialTheme.typography.bodySmall, color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

/**
 * Records a payment made outside the app (mobile money, cash at the desk,
 * a card terminal, a transfer). There is no gateway here. The form matches
 * the web's, but the backend persists only the amount today: method,
 * reference and notes are accepted and dropped (PatientPortalController
 * .payMyInvoice forwards dto.getAmount() alone). The hint says so rather
 * than promising the cashier a reference they will never see; the backend
 * gap is recorded in tasklist.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(
    invoice: InvoiceDto,
    isPaying: Boolean,
    onDismiss: () -> Unit,
    onPay: (amount: Double, method: String, reference: String?, notes: String?) -> Unit
) {
    var amountText by remember { mutableStateOf(String.format(Locale.US, "%.2f", invoice.balanceDue)) }
    var method by remember { mutableStateOf(PAYMENT_METHODS.first()) }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val normalised = amountText.replace(',', '.')
    val amount = normalised.toDoubleOrNull()
    // The backend validates @Digits(integer = 10, fraction = 2); reject a third
    // decimal here rather than round-trip for a 400.
    val twoDecimals = normalised.substringAfter('.', "").length <= 2
    val amountValid = amount != null && amount > 0 && amount <= invoice.balanceDue + 0.005 && twoDecimals

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.pay_invoice), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.payment_sheet_hint, invoice.invoiceNumber, money(invoice.balanceDue)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.payment_amount)) },
                isError = !amountValid,
                supportingText = if (!amountValid) ({ Text(stringResource(R.string.payment_amount_invalid)) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = methodLabel(method),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.payment_method)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    PAYMENT_METHODS.forEach { m ->
                        DropdownMenuItem(text = { Text(methodLabel(m)) }, onClick = { method = m; expanded = false })
                    }
                }
            }

            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it.take(REFERENCE_MAX) },
                label = { Text(stringResource(R.string.payment_reference)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(NOTES_MAX) },
                label = { Text(stringResource(R.string.payment_notes)) },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { onPay(amount ?: 0.0, method, reference, notes) },
                enabled = amountValid && !isPaying,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                if (isPaying) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.record_payment), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
