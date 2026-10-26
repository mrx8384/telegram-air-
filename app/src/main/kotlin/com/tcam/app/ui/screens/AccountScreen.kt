package com.tcam.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tcam.app.R
import com.tcam.app.ui.MainViewModel
import com.tcam.app.ui.SuccessColor
import com.tcam.tdlib.AuthStep

/** The official Telegram login flow, driven by TDLib's authorization states. */
@Composable
fun AccountScreen(vm: MainViewModel, modifier: Modifier) {
    val auth by vm.auth.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.account_title)) {
            Text(stringResource(R.string.account_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val a = auth) {
                AuthStep.NotStarted -> {
                    Text(stringResource(R.string.not_started))
                    Button(onClick = vm::restartTdlib) { Text(stringResource(R.string.retry)) }
                }
                is AuthStep.NotConfigured -> Text(a.reason, color = MaterialTheme.colorScheme.error)
                AuthStep.Initializing, AuthStep.LoggingOut -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text(stringResource(R.string.initializing))
                    }
                }
                AuthStep.WaitPhoneNumber -> SingleField(
                    label = stringResource(R.string.phone_label), button = stringResource(R.string.send_code),
                    keyboard = KeyboardType.Phone, busy = vm.authBusy, onSubmit = vm::submitPhone,
                )
                is AuthStep.WaitCode -> {
                    Text(stringResource(R.string.code_sent, a.phoneNumber, a.codeType), style = MaterialTheme.typography.bodySmall)
                    SingleField(
                        label = stringResource(R.string.code_label), button = stringResource(R.string.check_code),
                        keyboard = KeyboardType.Number, busy = vm.authBusy, onSubmit = vm::submitCode,
                    )
                    TextButton(onClick = vm::resendCode, enabled = !vm.authBusy) { Text(stringResource(R.string.resend)) }
                }
                AuthStep.WaitEmailAddress -> SingleField(
                    label = stringResource(R.string.email_label), button = stringResource(R.string.check_password),
                    keyboard = KeyboardType.Email, busy = vm.authBusy, onSubmit = vm::submitEmail,
                )
                is AuthStep.WaitEmailCode -> SingleField(
                    label = stringResource(R.string.email_code_label, a.emailPattern), button = stringResource(R.string.check_code),
                    keyboard = KeyboardType.Number, busy = vm.authBusy, onSubmit = vm::submitEmailCode,
                )
                is AuthStep.WaitPassword -> {
                    if (a.hint.isNotBlank()) Text(stringResource(R.string.password_hint, a.hint), style = MaterialTheme.typography.bodySmall)
                    SingleField(
                        label = stringResource(R.string.password_label), button = stringResource(R.string.check_password),
                        keyboard = KeyboardType.Password, busy = vm.authBusy, password = true, onSubmit = vm::submitPassword,
                    )
                }
                is AuthStep.WaitOtherDevice -> SelectionContainer { Text(stringResource(R.string.other_device, a.link)) }
                AuthStep.WaitRegistration -> Text(stringResource(R.string.registration_unsupported), color = MaterialTheme.colorScheme.error)
                AuthStep.WaitPremiumPurchase -> Text(stringResource(R.string.premium_unsupported), color = MaterialTheme.colorScheme.error)
                AuthStep.Ready -> {
                    Text(stringResource(R.string.logged_in), color = SuccessColor)
                    OutlinedButton(onClick = vm::logOut, enabled = !vm.authBusy) { Text(stringResource(R.string.log_out)) }
                }
                AuthStep.Closed -> {
                    Text(stringResource(R.string.closed))
                    Button(onClick = vm::restartTdlib) { Text(stringResource(R.string.retry)) }
                }
                is AuthStep.Error -> {
                    Text(stringResource(R.string.error_prefix, a.message), color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::restartTdlib) { Text(stringResource(R.string.retry)) }
                }
            }
            vm.authError?.let { Text(stringResource(R.string.error_prefix, it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SingleField(
    label: String,
    button: String,
    keyboard: KeyboardType,
    busy: Boolean,
    password: Boolean = false,
    onSubmit: (String) -> Unit,
) {
    // Not saved across process death on purpose: login secrets stay in memory only.
    var value by rememberSaveable(label, saver = androidx.compose.runtime.saveable.autoSaver()) { mutableStateOf("") }
    if (password) {
        var pw by androidx.compose.runtime.remember { mutableStateOf("") }
        OutlinedTextField(
            value = pw, onValueChange = { pw = it }, label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSubmit(pw) }, enabled = !busy && pw.isNotEmpty()) { Text(button) }
        return
    }
    OutlinedTextField(
        value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(value) }, enabled = !busy && value.isNotBlank()) { Text(button) }
}
