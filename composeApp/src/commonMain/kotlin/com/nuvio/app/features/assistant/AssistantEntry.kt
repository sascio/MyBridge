package com.nuvio.app.features.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.assistant_api_key
import nuvio.composeapp.generated.resources.assistant_close
import nuvio.composeapp.generated.resources.assistant_description
import nuvio.composeapp.generated.resources.assistant_error
import nuvio.composeapp.generated.resources.assistant_model
import nuvio.composeapp.generated.resources.assistant_question
import nuvio.composeapp.generated.resources.assistant_send
import nuvio.composeapp.generated.resources.assistant_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AssistantEntry(meta: MetaDetails) {
    var open by remember(meta.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.assistant_title))
    }
    if (open) AssistantDialog(meta = meta, onDismiss = { open = false })
}

@Composable
private fun AssistantDialog(meta: MetaDetails, onDismiss: () -> Unit) {
    // Not saveable: key and conversation are discarded when this dialog closes.
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<AssistantMessage>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = remember { AssistantClient() }
    DisposableEffect(client) { onDispose { client.close() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.assistant_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.assistant_description), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.take(512) },
                    label = { Text(stringResource(Res.string.assistant_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.take(120) },
                    label = { Text(stringResource(Res.string.assistant_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                history.forEach { message ->
                    Text(message.text, style = if (message.role == "user") MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(4000) },
                    label = { Text(stringResource(Res.string.assistant_question)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) CircularProgressIndicator()
                if (failed) Text(stringResource(Res.string.assistant_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && apiKey.isNotBlank() && model.isNotBlank() && question.isNotBlank(),
                onClick = {
                    val userMessage = AssistantMessage("user", question.trim())
                    val requestHistory = history.takeLast(18) + userMessage
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            val reply = client.ask(apiKey.trim(), model.trim(), meta, requestHistory)
                            history = (requestHistory + AssistantMessage("assistant", reply.take(4000))).takeLast(20)
                            question = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.assistant_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.assistant_close)) } },
    )
}
