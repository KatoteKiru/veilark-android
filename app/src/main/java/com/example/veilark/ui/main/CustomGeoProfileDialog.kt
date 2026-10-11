package com.example.veilark.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.veilark.R
import com.example.veilark.profile.CustomGeoProfile
import com.example.veilark.profile.CustomGeoProfileFetcher
import com.example.veilark.profile.CustomGeoProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports data into a preview; only the enclosing routing dialog can apply it. */
@Composable
internal fun CustomGeoProfileDialog(
  initialDirect: String,
  initialVpn: String,
  onUse: (CustomGeoProfile) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var name by rememberSaveable { mutableStateOf("My GEO") }
  var direct by rememberSaveable { mutableStateOf(initialDirect) }
  var vpn by rememberSaveable { mutableStateOf(initialVpn) }
  var input by rememberSaveable { mutableStateOf("") }
  var importedRulesDirect by rememberSaveable { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var preview by remember { mutableStateOf<CustomGeoProfile?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var saved by remember { mutableStateOf(false) }
  var savedProfiles by remember { mutableStateOf<List<CustomGeoProfile>>(emptyList()) }
  val store = remember(context) { CustomGeoProfileStore(context.filesDir) }
  val notFound = stringResource(R.string.custom_geo_not_found)
  fun adopt(profile: CustomGeoProfile) {
    name = profile.name; direct = profile.directEntries; vpn = profile.vpnEntries
    preview = profile; error = null; saved = false
  }
  fun task(action: suspend () -> Unit) {
    if (busy) return
    busy = true; error = null; saved = false
    scope.launch {
      try { action() }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { error = failure.message ?: notFound }
      finally { busy = false }
    }
  }
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(stringResource(R.string.custom_geo_title)) },
    text = {
      Column(
        Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(stringResource(R.string.custom_geo_explanation))
        OutlinedTextField(input, { input = it; preview = null; saved = false },
          label = { Text(stringResource(R.string.custom_geo_source)) },
          modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 4)
        Row {
          Checkbox(checked = importedRulesDirect, enabled = !busy, onCheckedChange = { importedRulesDirect = it })
          Text(stringResource(if (importedRulesDirect) R.string.custom_geo_import_direct else R.string.custom_geo_import_vpn))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(enabled = !busy && input.isNotBlank(), onClick = {
            task {
              val imported = withContext(Dispatchers.IO) {
                if (input.trim().startsWith("https://") && input.trim().endsWith(".txt")) {
                  val rules = CustomGeoProfileFetcher.fetchRules(input.trim())
                  CustomGeoProfile.create(name,
                    if (importedRulesDirect) listOf(direct, rules).filter(String::isNotBlank).joinToString("\n") else direct,
                    if (!importedRulesDirect) listOf(vpn, rules).filter(String::isNotBlank).joinToString("\n") else vpn)
                }
                else if (input.trim().startsWith("https://")) CustomGeoProfileFetcher.fetch(input.trim())
                else CustomGeoProfile.parse(input)
              }
              adopt(imported)
            }
          }) { Text(stringResource(R.string.custom_geo_import)) }
          TextButton(enabled = !busy, onClick = {
            task {
              savedProfiles = withContext(Dispatchers.IO) { store.list() }
              require(savedProfiles.isNotEmpty()) { notFound }
            }
          }) { Text(stringResource(R.string.custom_geo_load)) }
        }
        if (savedProfiles.isNotEmpty()) {
          Text(stringResource(R.string.custom_geo_saved_profiles))
          savedProfiles.forEach { profile ->
            Row {
              TextButton(enabled = !busy, onClick = { adopt(profile) }, modifier = Modifier.weight(1f)) { Text(profile.name) }
              TextButton(enabled = !busy, onClick = {
                task {
                  savedProfiles = withContext(Dispatchers.IO) { store.remove(profile.name); store.list() }
                }
              }) { Text(stringResource(R.string.custom_geo_delete)) }
            }
          }
        }
        OutlinedTextField(name, { name = it; preview = null; saved = false },
          label = { Text(stringResource(R.string.custom_geo_name)) },
          enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(direct, { direct = it; preview = null; saved = false },
          label = { Text(stringResource(R.string.direct_without_vpn)) },
          enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 5)
        OutlinedTextField(vpn, { vpn = it; preview = null; saved = false },
          label = { Text(stringResource(R.string.custom_geo_vpn)) },
          enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 5)
        TextButton(enabled = !busy, onClick = {
          task { adopt(withContext(Dispatchers.IO) { CustomGeoProfile.create(name, direct, vpn) }) }
        }) { Text(stringResource(R.string.custom_geo_preview)) }
        preview?.let { profile ->
          Text(stringResource(R.string.custom_geo_preview_result,
            profile.directEntries.lineSequence().count { it.isNotBlank() },
            profile.vpnEntries.lineSequence().count { it.isNotBlank() }))
          TextButton(enabled = !busy, onClick = {
            task {
              savedProfiles = withContext(Dispatchers.IO) { store.save(profile); store.list() }
              saved = true
            }
          }) { Text(stringResource(R.string.custom_geo_save)) }
        }
        if (saved) Text(stringResource(R.string.custom_geo_saved))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(enabled = preview != null && !busy, onClick = { preview?.let(onUse) }) {
        Text(stringResource(R.string.custom_geo_use))
      }
    },
    dismissButton = {
      TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
    },
  )
}
