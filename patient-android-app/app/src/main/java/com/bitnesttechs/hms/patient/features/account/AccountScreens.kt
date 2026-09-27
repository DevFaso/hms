package com.bitnesttechs.hms.patient.features.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bitnesttechs.hms.patient.BuildConfig
import com.bitnesttechs.hms.patient.R
import com.bitnesttechs.hms.patient.ui.theme.BrandPrimary
import com.bitnesttechs.hms.patient.core.models.StatusTone
import com.bitnesttechs.hms.patient.ui.theme.onBadge

// ── Shared pieces ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFormScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandPrimary, titleContentColor = Color.White)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(if (visible) R.string.hide_password else R.string.show_password)
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun FormErrorText(error: FormError?) {
    error ?: return
    val headline = stringResource(error.messageRes)
    Text(
        error.detail?.takeIf { it.isNotBlank() }?.let { "$headline\n$it" } ?: headline,
        // Not colorScheme.error: #EA4335 is 3.9:1 on white.
        color = StatusTone.NEGATIVE.onBadge(),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
fun ConfirmationText(text: String) {
    Text(
        text,
        color = StatusTone.POSITIVE.onBadge(),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
fun BusyButton(label: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label)
    }
}

/** The web portal (the same origin as the API, without `/api`), as a fallback for any account step. */
fun openWebPortal(context: Context, path: String = "") {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.WEB_PORTAL_URL.trimEnd('/') + path))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No browser on the phone: nothing else to offer.
    }
}

// ── Forgot password ─────────────────────────────────────────────────────────

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, viewModel: ForgotPasswordViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    AccountFormScaffold(stringResource(R.string.reset_title), onBack) {
        if (state.done) {
            Icon(Icons.Default.CheckCircle, null, tint = StatusTone.POSITIVE.onBadge(), modifier = Modifier.size(48.dp))
            ConfirmationText(stringResource(R.string.reset_done))
            BusyButton(stringResource(R.string.account_back_to_sign_in), busy = false, onClick = onBack)
            return@AccountFormScaffold
        }
        Text(stringResource(R.string.reset_instruction), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmail,
            label = { Text(stringResource(R.string.email_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        BusyButton(stringResource(R.string.reset_send_link), busy = state.requesting, onClick = viewModel::requestLink)
        if (state.linkSent) ConfirmationText(stringResource(R.string.reset_link_sent))

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Text(stringResource(R.string.reset_code_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.reset_code_instruction), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::onCode,
            label = { Text(stringResource(R.string.reset_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        PasswordField(state.newPassword, viewModel::onNewPassword, stringResource(R.string.new_password))
        PasswordField(state.confirmPassword, viewModel::onConfirmPassword, stringResource(R.string.confirm_password))
        FormErrorText(state.error)
        BusyButton(stringResource(R.string.reset_submit), busy = state.confirming, onClick = viewModel::confirm)
        TextButton(onClick = {
            val token = com.bitnesttechs.hms.patient.core.auth.AccountRepository.fromLink(state.code, "token")
            openWebPortal(context, if (token != null) "/reset-password?token=" + Uri.encode(token) else "/login")
        }) { Text(stringResource(R.string.open_web_portal)) }
        Spacer(Modifier.height(12.dp))
    }
}

// ── Activation ──────────────────────────────────────────────────────────────

@Composable
fun ActivationScreen(onBack: () -> Unit, viewModel: ActivationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    AccountFormScaffold(stringResource(R.string.activation_title), onBack) {
        if (state.activated) {
            Icon(Icons.Default.CheckCircle, null, tint = StatusTone.POSITIVE.onBadge(), modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.activation_done_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ConfirmationText(stringResource(R.string.activation_done_message))
            BusyButton(stringResource(R.string.account_back_to_sign_in), busy = false, onClick = onBack)
            return@AccountFormScaffold
        }
        Text(stringResource(R.string.activation_instruction), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmail,
            label = { Text(stringResource(R.string.email_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        BusyButton(stringResource(R.string.activation_send_link), busy = state.sending, onClick = viewModel::resend)
        if (state.linkSent) ConfirmationText(stringResource(R.string.activation_link_sent))

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Text(stringResource(R.string.activation_have_link_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.activation_link_instruction), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.link,
            onValueChange = viewModel::onLink,
            label = { Text(stringResource(R.string.activation_link_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FormErrorText(state.error)
        BusyButton(stringResource(R.string.activation_submit), busy = state.activating, onClick = viewModel::activate)
        Spacer(Modifier.height(12.dp))
    }
}

// ── Change password ─────────────────────────────────────────────────────────

@Composable
fun ChangePasswordScreen(onBack: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    AccountFormScaffold(stringResource(R.string.change_password_title), onBack) {
        if (state.done) {
            Icon(Icons.Default.CheckCircle, null, tint = StatusTone.POSITIVE.onBadge(), modifier = Modifier.size(48.dp))
            ConfirmationText(stringResource(R.string.change_password_done))
            BusyButton(stringResource(R.string.back), busy = false, onClick = onBack)
            return@AccountFormScaffold
        }
        PasswordField(state.current, viewModel::onCurrent, stringResource(R.string.current_password))
        PasswordField(state.newPassword, viewModel::onNewPassword, stringResource(R.string.new_password))
        PasswordField(state.confirmPassword, viewModel::onConfirmPassword, stringResource(R.string.confirm_password))
        Text(stringResource(R.string.password_min_length), style = MaterialTheme.typography.bodySmall)
        FormErrorText(state.error)
        BusyButton(stringResource(R.string.change_password_submit), busy = state.saving, onClick = viewModel::submit)
    }
}
