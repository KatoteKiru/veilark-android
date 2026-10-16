@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
  androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.veilark.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.veilark.R
import com.example.veilark.profile.CustomGeoProfile
import com.example.veilark.profile.CustomGeoProfileFetcher
import com.example.veilark.profile.CustomGeoProfileStore
import com.example.veilark.profile.GeoCategory
import com.example.veilark.profile.GeoCategoryCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A full-window draft editor. Only the enclosing routing screen applies traffic changes. */
@Composable
internal fun CustomGeoProfileDialog(
  initialDirect: String,
  initialVpn: String,
  geoUpdating: Boolean,
  geoStatusDescription: String?,
  geoUpdateMessage: String?,
  refreshEnabled: Boolean,
  trustTunnelActive: Boolean,
  onRefreshGeo: () -> Unit,
  onUse: (CustomGeoProfile) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
  val scope = rememberCoroutineScope()
  val store = remember(context) { CustomGeoProfileStore(context.filesDir) }
  val catalog = remember(context) { GeoCategoryCatalog.fromContext(context) }
  val defaultName = stringResource(R.string.geo_scenario_default_name)
  var name by rememberSaveable { mutableStateOf(defaultName) }
  var direct by rememberSaveable { mutableStateOf(initialDirect) }
  var vpn by rememberSaveable { mutableStateOf(initialVpn) }
  var query by rememberSaveable { mutableStateOf("") }
  var selectedOnly by rememberSaveable { mutableStateOf(false) }
  var section by rememberSaveable { mutableIntStateOf(0) }
  var input by rememberSaveable { mutableStateOf("") }
  var importedRulesDirect by rememberSaveable { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var loading by remember { mutableStateOf(true) }
  var categories by remember { mutableStateOf<List<GeoCategory>>(emptyList()) }
  var savedProfiles by remember { mutableStateOf<List<CustomGeoProfile>>(emptyList()) }
  var error by remember { mutableStateOf<String?>(null) }
  var saved by remember { mutableStateOf(false) }
  val genericError = stringResource(R.string.geo_scenario_operation_failed)
  fun task(action: suspend () -> Unit) {
    if (busy) return
    busy = true; error = null; saved = false
    scope.launch {
      try { action() }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: Exception) { error = failure.message ?: genericError }
      finally { busy = false }
    }
  }
  fun adopt(profile: CustomGeoProfile) {
    name = profile.name; direct = profile.directEntries; vpn = profile.vpnEntries
    error = null; saved = false; section = 0
  }
  LaunchedEffect(store, catalog) {
    try {
      categories = withContext(Dispatchers.IO) { catalog.load() }
      savedProfiles = withContext(Dispatchers.IO) { store.list() }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { error = failure.message ?: genericError }
    finally { loading = false }
  }
  val directTokens = remember(direct) { direct.split(Regex("[\\s,;]+")).filter(String::isNotBlank).toSet() }
  val vpnTokens = remember(vpn) { vpn.split(Regex("[\\s,;]+")).filter(String::isNotBlank).toSet() }
  val visible = remember(categories, query, selectedOnly, directTokens, vpnTokens, locale) {
    categories.filter { category ->
      (!selectedOnly || category.id in directTokens || category.id in vpnTokens) &&
        (query.isBlank() || category.displayLabel(locale).contains(query.trim(), true) ||
          category.id.contains(query.trim(), true) || category.searchAliases().contains(query.trim(), true))
    }
  }
  fun setRoute(id: String, target: Int) {
    direct = (directTokens - id + if (target == 1) setOf(id) else emptySet()).joinToString("\n")
    vpn = (vpnTokens - id + if (target == 2) setOf(id) else emptySet()).joinToString("\n")
    saved = false; error = null
  }
  Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(), color = MaterialTheme.colorScheme.surface) {
      Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onDismiss, enabled = !busy) {
            Icon(Icons.Rounded.ArrowBack, stringResource(R.string.geo_scenario_back))
          }
          Text(stringResource(R.string.geo_scenario_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(name, { name = it; saved = false }, label = { Text(stringResource(R.string.geo_scenario_name)) },
          singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Text(stringResource(R.string.geo_scenario_selection_summary, directTokens.size, vpnTokens.size),
          Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        TabRow(selectedTabIndex = section) {
          listOf(R.string.geo_scenario_categories, R.string.geo_scenario_saved, R.string.geo_scenario_custom).forEachIndexed { index, label ->
            Tab(selected = section == index, enabled = !busy, onClick = { section = index }, text = { Text(stringResource(label)) })
          }
        }
        if (busy || loading || geoUpdating) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          if (section == 0) {
            item {
              Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.geo_scenario_catalog_description), style = MaterialTheme.typography.bodyMedium)
                  geoStatusDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                  geoUpdateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                  if (trustTunnelActive) Text(stringResource(R.string.geo_scenario_trust_categories),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && !loading, onClick = {
                      task { categories = catalog.refresh() }
                    }) { Text(stringResource(R.string.geo_scenario_download_catalog)) }
                    TextButton(enabled = refreshEnabled && !trustTunnelActive && !geoUpdating && !busy, onClick = onRefreshGeo) {
                      Text(stringResource(R.string.update_geo_data))
                    }
                  }
                }
              }
            }
            item {
              OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.geo_scenario_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
              FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly },
                label = { Text(stringResource(R.string.geo_scenario_selected_only)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (!loading && visible.isEmpty()) item {
              Text(stringResource(if (categories.isEmpty()) R.string.geo_scenario_catalog_empty else R.string.geo_scenario_no_results),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(visible, key = { it.id }) { category ->
              Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                  Text(category.displayLabel(locale), style = MaterialTheme.typography.titleMedium)
                  Text(category.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.geo_scenario_off, R.string.geo_scenario_direct, R.string.geo_scenario_vpn).forEachIndexed { index, label ->
                      val target = when { category.id in directTokens -> 1; category.id in vpnTokens -> 2; else -> 0 }
                      FilterChip(selected = target == index, enabled = !busy && (!trustTunnelActive || index != 2),
                        onClick = { setRoute(category.id, index) }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                  }
                }
              }
            }
          } else if (section == 1) {
            if (!loading && savedProfiles.isEmpty()) item { Text(stringResource(R.string.geo_scenario_saved_empty)) }
            items(savedProfiles, key = { it.name }) { profile ->
              OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                  Text(profile.name, style = MaterialTheme.typography.titleMedium)
                  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { adopt(profile) }) { Text(stringResource(R.string.geo_scenario_open)) }
                    TextButton(enabled = !busy, onClick = { task {
                      savedProfiles = withContext(Dispatchers.IO) { store.remove(profile.name); store.list() }
                    } }) { Text(stringResource(R.string.custom_geo_delete)) }
                  }
                }
              }
            }
          } else {
            item { Text(stringResource(R.string.geo_scenario_custom_description), style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedTextField(direct, { direct = it; saved = false }, label = { Text(stringResource(R.string.direct_without_vpn)) },
              modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 4) }
            item { OutlinedTextField(vpn, { vpn = it; saved = false }, label = { Text(stringResource(R.string.custom_geo_vpn)) },
              modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 4) }
            item {
              Text(stringResource(R.string.geo_scenario_import_description), style = MaterialTheme.typography.bodyMedium)
              OutlinedTextField(input, { input = it }, label = { Text(stringResource(R.string.custom_geo_source)) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 1, maxLines = 3)
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(importedRulesDirect, { importedRulesDirect = it }, enabled = !busy)
                Text(stringResource(if (importedRulesDirect) R.string.custom_geo_import_direct else R.string.custom_geo_import_vpn), style = MaterialTheme.typography.bodyMedium)
              }
              OutlinedButton(enabled = !busy && input.isNotBlank(), onClick = { task {
                val imported = withContext(Dispatchers.IO) {
                  if (input.trim().startsWith("https://") && input.trim().endsWith(".txt")) {
                    val rules = CustomGeoProfileFetcher.fetchRules(input.trim())
                    CustomGeoProfile.create(name,
                      if (importedRulesDirect) listOf(direct, rules).filter(String::isNotBlank).joinToString("\n") else direct,
                      if (!importedRulesDirect) listOf(vpn, rules).filter(String::isNotBlank).joinToString("\n") else vpn)
                  } else if (input.trim().startsWith("https://")) CustomGeoProfileFetcher.fetch(input.trim())
                  else CustomGeoProfile.parse(input)
                }
                adopt(imported)
              } }) { Text(stringResource(R.string.custom_geo_import)) }
            }
          }
          item { Text(stringResource(R.string.geo_scenario_remaining), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          if (saved) item { Text(stringResource(R.string.custom_geo_saved), color = MaterialTheme.colorScheme.primary) }
        }
        HorizontalDivider()
        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
          OutlinedButton(enabled = !busy && !loading, onClick = { task {
            val profile = withContext(Dispatchers.IO) { CustomGeoProfile.create(name, direct, vpn) }
            savedProfiles = withContext(Dispatchers.IO) { store.save(profile); store.list() }; saved = true
          } }) { Text(stringResource(R.string.geo_scenario_save)) }
          Button(enabled = !busy && !loading && (direct.isNotBlank() || vpn.isNotBlank()), onClick = { task {
            val profile = withContext(Dispatchers.IO) { CustomGeoProfile.create(name, direct, vpn) }
            catalog.prepare(profile.directEntries, profile.vpnEntries)
            if (trustTunnelActive) withContext(Dispatchers.IO) {
              catalog.trustDirectExclusions(profile.directEntries, profile.vpnEntries)
            }
            onUse(profile)
          } }) { Text(stringResource(R.string.geo_scenario_choose)) }
        }
      }
    }
  }
}

/** Localize ISO country names without changing the publisher's rule identity. */
private fun GeoCategory.displayLabel(locale: Locale): String {
  val country = id.substringAfter(':')
  if (kind == "geoip" && country.length == 2 && country.uppercase(Locale.ROOT) in Locale.getISOCountries()) {
    return Locale("", country.uppercase(Locale.ROOT)).getDisplayCountry(locale)
  }
  return label
}

/** Search aliases only: publisher identifiers and routing rules remain unchanged. */
private fun GeoCategory.searchAliases(): String = when (id.substringAfter(':').substringBefore('@')) {
  "telegram" -> "телеграм телеграмм тг"
  "youtube" -> "ютуб ютюб"
  "whatsapp" -> "ватсап вотсап"
  "instagram" -> "инстаграм инста"
  else -> ""
}
