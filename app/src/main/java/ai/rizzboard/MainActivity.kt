package ai.rizzboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.rizzboard.ai.*
import ai.rizzboard.data.ProviderStore
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Color(0xFF25213A)
private val Violet = Color(0xFF7054D9)
private val Canvas = Color(0xFFF7F5FC)

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { RizzBoardApp(resumeTick) } }
    override fun onResume() { super.onResume(); resumeTick++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RizzBoardApp(resumeTick: Int) {
    val context = LocalContext.current
    val store = remember { AppGraph.store(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var settings by remember(refresh) { mutableStateOf(store.settings()) }
    var providers by remember(refresh) { mutableStateOf(store.providers()) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imeManager = context.getSystemService(InputMethodManager::class.java)
    val enabled = imeManager.enabledInputMethodList.any { it.packageName == context.packageName }

    MaterialTheme(colorScheme = lightColorScheme(primary = Violet, secondary = Color(0xFFFF7B9C), background = Canvas, surface = Color.White, onSurface = Ink)) {
        Scaffold(containerColor = Canvas) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(50.dp).background(Violet, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) { Text("r.", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium) }
                        Spacer(Modifier.width(13.dp)); Column { Text("RizzBoard AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink); Text("Your words, just a little sharper.", color = Color(0xFF777287)) }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFECE7FF)), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("KEY", color = Violet, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(10.dp)); Text("Set up your keyboard", fontWeight = FontWeight.Bold, color = Ink) }
                            Text(if (enabled) "Keyboard enabled. Choose it from the keyboard switcher in any text field." else "Enable RizzBoard in Android Settings, then switch to it from any text field.", color = Color(0xFF615B70), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, shape = RoundedCornerShape(14.dp)) { Text(if (enabled) "Keyboard settings" else "Enable keyboard") }
                                OutlinedButton(onClick = { imeManager.showInputMethodPicker() }, shape = RoundedCornerShape(14.dp)) { Text("Choose keyboard") }
                            }
                        }
                    }
                }
                item {
                    SectionCard("Writing style", "Choose the default mode used by the keyboard") {
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(value = WritingModes.get(settings.modeId).title, onValueChange = {}, readOnly = true, label = { Text("Default mode") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                WritingModes.all.forEach { mode -> DropdownMenuItem(text = { Text(mode.title) }, onClick = { settings = settings.copy(modeId = mode.id); store.setSettings(settings); expanded = false }) }
                            }
                        }
                        Text("Creativity · ${(settings.creativity * 100).toInt()}%", fontWeight = FontWeight.Medium, color = Ink)
                        Slider(value = settings.creativity, onValueChange = { settings = settings.copy(creativity = it) }, onValueChangeFinished = { store.setSettings(settings) }, valueRange = 0f..1f)
                        Text("Lower values stay closer to your original wording.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777287))
                    }
                }
                item {
                    SectionCard("Keyboard layout", "Adjust the size and feel of the keys") {
                        Text("Key size · ${(settings.keyScale * 100).roundToInt()}%", fontWeight = FontWeight.Medium, color = Ink)
                        Slider(
                            value = settings.keyScale,
                            onValueChange = { settings = settings.copy(keyScale = it) },
                            onValueChangeFinished = { store.setSettings(settings) },
                            valueRange = 0.85f..1.25f,
                            steps = 4
                        )
                        Text("Applies when the keyboard opens again. Hold the delete key to auto-repeat.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777287))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Feedback on key press", fontWeight = FontWeight.Medium, color = Ink)
                                Text("A short vibration every time a key is tapped.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777287))
                            }
                            Switch(checked = settings.haptics, onCheckedChange = { settings = settings.copy(haptics = it); store.setSettings(settings) })
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("AI providers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink); Text("Your endpoint, model, and key stay under your control.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF777287)) }
                        FilledTonalButton(onClick = { creating = true }, shape = RoundedCornerShape(14.dp)) { Text("+ Add") }
                    }
                }
                if (providers.isEmpty()) item { EmptyProvider { creating = true } }
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(provider = provider, selected = settings.defaultProviderId == provider.id, onSelect = { settings = settings.copy(defaultProviderId = provider.id); store.setSettings(settings); refresh++ }, onEdit = { editing = provider }, onDelete = { store.deleteProvider(provider.id); refresh++ }, onTest = {
                        banner = "Testing ${provider.name}…"
                        scope.launch {
                            banner = when (val result = AppGraph.ai.test(provider)) {
                                is AiResult.Success -> "Connection test succeeded."
                                is AiResult.Failure -> "Test failed: ${result.message}"
                            }
                        }
                    })
                }
                item {
                    SectionCard("Privacy & compatibility", null) {
                        Row(verticalAlignment = Alignment.Top) { Text("SAFE", color = Violet, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(10.dp)); Text("Your text and API key are sent only to the provider endpoint you configure. The provider may log submitted content under its own policies. API keys are encrypted with Android Keystore on this device. Never paste secrets into an endpoint you do not trust.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF615B70)) }
                        Text("Keyboard behavior depends on the target app. Some apps restrict selected-text access; when that happens, RizzBoard uses only the available text immediately before the cursor. Generated text is never sent automatically.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF615B70))
                    }
                }
                if (banner.isNotBlank()) item { Text(banner, color = Violet, style = MaterialTheme.typography.bodyMedium) }
                item { Text("OpenAI-compatible Chat Completions adapter · HTTPS only", color = Color(0xFF8A8598), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    if (creating || editing != null) {
        ProviderEditor(initial = editing, onDismiss = { creating = false; editing = null }, onSave = { provider -> store.saveProvider(provider); creating = false; editing = null; refresh++ })
    }
}

@Composable private fun SectionCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF777287))
            content()
        }
    }
}

@Composable private fun EmptyProvider(onAdd: () -> Unit) {
    SectionCard("No provider configured", "The keyboard remains fully usable for ordinary typing without an AI service.") {
        Button(onClick = onAdd) { Text("Add AI provider") }
    }
}

@Composable private fun ProviderCard(provider: ProviderConfig, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onTest: () -> Unit) {
    SectionCard(provider.name + if (selected) " · Default" else "", provider.model.ifBlank { "Model not set" }) {
        Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall, color = Color(0xFF777287))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!selected) TextButton(onClick = onSelect) { Text("Set default") }
            TextButton(onClick = onTest) { Text("Test") }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB42343))) { Text("Delete") }
        }
    }
}

@Composable private fun ProviderEditor(initial: ProviderConfig?, onDismiss: () -> Unit, onSave: (ProviderConfig) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var key by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "") }
    var timeout by remember { mutableStateOf((initial?.timeoutSeconds ?: 30).toString()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Add provider" else "Edit provider") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("Base URL or full endpoint") }, supportingText = { Text("HTTPS only. Base URL such as https://api.example/v1; /chat/completions is appended. A complete Chat Completions URL is also accepted.") })
            OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text(if (initial?.apiKey?.isNotEmpty() == true) "API key · leave blank to keep current" else "API key") }, singleLine = true)
            OutlinedTextField(headers, { headers = it }, label = { Text("Optional headers (one Name: value per line)") }, minLines = 2)
            OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(3) }, label = { Text("Timeout seconds (5–120)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Enabled", Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = { enabled = it }) }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val parsedHeaders = headers.lines().mapNotNull { line -> val i = line.indexOf(':'); if (i > 0) line.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { it to line.substring(i + 1).trim() } else null }.toMap()
        if (name.isBlank() || model.isBlank()) error = "Display name and model ID are required."
        else if (!url.trim().startsWith("https://", true)) error = "Provider endpoint must use HTTPS."
        else if (initial == null && key.isBlank()) error = "API key is required."
        else {
            val previous = initial
            onSave(ProviderConfig(id = previous?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), baseUrl = url.trim(), model = model.trim(), apiKey = key.ifBlank { previous?.apiKey.orEmpty() }, headers = parsedHeaders, timeoutSeconds = timeout.toIntOrNull()?.coerceIn(5, 120) ?: 30, enabled = enabled))
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
