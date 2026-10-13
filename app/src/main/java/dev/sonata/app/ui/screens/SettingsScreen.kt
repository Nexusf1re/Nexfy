package dev.sonata.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonata.app.ui.AppActions
import kotlinx.coroutines.launch
import uniffi.sonata_core.WebApp
import uniffi.sonata_core.WebStatus

@Composable
fun SettingsScreen(actions: AppActions) {
    val scope = rememberCoroutineScope()
    val core = actions.core
    var user by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebStatus?>(null) }
    var clientId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        user = runCatching { core.restoreSession() }.getOrNull()
        web = core.webStatus().also { if (clientId.isEmpty()) clientId = it.personalClientId.orEmpty() }
    }

    /** Run a browser sign-in and report how it went. */
    fun signIn(label: String, begin: suspend () -> String, finish: suspend () -> Unit) {
        scope.launch {
            message = try {
                actions.openBrowser(begin())
                message = "Aguardando o Spotify no navegador…"
                finish()
                "$label conectado"
            } catch (e: Exception) {
                "Erro: ${e.message}"
            }
            reload++
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Text("Reprodução", style = MaterialTheme.typography.titleMedium)
        Text(user?.let { "Conectado como $it" } ?: "Não conectado")
        if (user == null) {
            Button(onClick = {
                signIn("Reprodução", { core.beginLogin() }, { core.finishLogin() })
            }) { Text("Entrar com Spotify") }
        }

        HorizontalDivider()
        Text("Biblioteca e busca (Web API)", style = MaterialTheme.typography.titleMedium)
        Text(
            "O app pessoal tem cota só tua. O compartilhado cobre playlists da Spotify e a busca de playlists.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val status = web
        AppRow(
            title = "App pessoal",
            signedIn = status?.personalSignedIn == true,
            enabled = status?.personalClientId != null,
            onSignIn = {
                signIn("App pessoal", { core.beginWebLogin(WebApp.PERSONAL) }, { core.finishWebLogin(WebApp.PERSONAL) })
            },
            onSignOut = { scope.launch { core.webSignOut(WebApp.PERSONAL); reload++ } },
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim() },
            label = { Text("Client ID do app pessoal") },
            supportingText = { Text("Redirect URI no app: http://127.0.0.1:8989/login") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            scope.launch {
                core.setPersonalClientId(clientId.ifBlank { null })
                message = "Client ID salvo"
                reload++
            }
        }) { Text("Salvar Client ID") }

        AppRow(
            title = "App compartilhado",
            signedIn = status?.sharedSignedIn == true,
            enabled = true,
            onSignIn = {
                signIn("App compartilhado", { core.beginWebLogin(WebApp.SHARED) }, { core.finishWebLogin(WebApp.SHARED) })
            },
            onSignOut = { scope.launch { core.webSignOut(WebApp.SHARED); reload++ } },
        )
    }
}

@Composable
private fun AppRow(
    title: String,
    signedIn: Boolean,
    enabled: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                if (signedIn) "Conectado" else "Não conectado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signedIn) {
            OutlinedButton(onClick = onSignOut) { Text("Sair") }
        } else {
            Button(enabled = enabled, onClick = onSignIn) { Text("Entrar") }
        }
    }
}
