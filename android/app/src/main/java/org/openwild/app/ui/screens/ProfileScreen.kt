package org.openwild.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openwild.app.OpenWildApp
import org.openwild.app.R
import org.openwild.app.core.AcademicDomains
import org.openwild.app.core.Attribution
import org.openwild.app.core.LocalProfile
import org.openwild.app.core.PhotoLicense
import org.openwild.app.ui.Contribute
import org.openwild.app.ui.Footer
import org.openwild.app.ui.ProfileForm
import org.openwild.app.ui.ProfileSync
import org.openwild.app.ui.ScrollColumn
import org.openwild.app.ui.SectionCard
import org.openwild.app.ui.toForm

sealed interface ProfileEvent {
    data class Name(val value: String) : ProfileEvent
    data class Title(val value: String) : ProfileEvent
    data class Affiliation(val value: String) : ProfileEvent
    data class Email(val value: String) : ProfileEvent
    data class Anonymous(val value: Boolean) : ProfileEvent
    data class License(val code: String) : ProfileEvent
    data object Save : ProfileEvent
    data object CheckDomain : ProfileEvent
    data object Export : ProfileEvent
    data object Delete : ProfileEvent
    data class Report(val id: Long) : ProfileEvent
}

@Composable
fun ProfileRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(ProfileForm(onlineEnabled = ProfileSync.enabled())) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun reload(hint: String = form.domainHint) {
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                val store = (context.applicationContext as OpenWildApp).profiles
                (store.current() ?: LocalProfile()).toForm(store.list(), ProfileSync.enabled(), hint)
            }
            form = next
        }
    }
    LaunchedEffect(Unit) { reload("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { jpegWithoutExif(context, uri) }
            if (jpeg != null) {
                withContext(Dispatchers.IO) {
                    val store = (context.applicationContext as OpenWildApp).profiles
                    store.save(form.toProfile(store.current()?.hasPhoto == true))
                    store.saveAvatar(jpeg)
                }
                reload(form.domainHint)
            }
        }
    }
    ProfileScreen(form, onEvent = { event ->
        when (event) {
            is ProfileEvent.Name -> form = form.copy(displayName = event.value.take(60))
            is ProfileEvent.Title -> form = form.copy(title = event.value.take(80))
            is ProfileEvent.Affiliation -> form = form.copy(affiliation = event.value.take(120))
            is ProfileEvent.Email -> form = form.copy(email = event.value.take(200), domainHint = "")
            is ProfileEvent.Anonymous -> form = form.copy(anonymous = event.value)
            is ProfileEvent.License -> form = form.copy(license = event.code)
            ProfileEvent.Save -> scope.launch {
                withContext(Dispatchers.IO) {
                    val store = (context.applicationContext as OpenWildApp).profiles
                    store.save(form.toProfile(store.current()?.hasPhoto == true))
                }
                Toast.makeText(context, context.getString(R.string.profile_saved), Toast.LENGTH_SHORT).show()
                reload(form.domainHint)
            }
            ProfileEvent.CheckDomain -> scope.launch {
                val hint = withContext(Dispatchers.IO) { domainHint(context, form.email) }
                form = form.copy(domainHint = hint)
            }
            ProfileEvent.Export -> scope.launch {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    val out = File(dir, "openwild-profile.json")
                    out.writeText((context.applicationContext as OpenWildApp).profiles.exportJson())
                    out
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, context.getString(R.string.profile_export)))
            }
            ProfileEvent.Delete -> confirmDelete = true
            is ProfileEvent.Report -> scope.launch {
                withContext(Dispatchers.IO) {
                    (context.applicationContext as OpenWildApp).profiles.markReported(event.id)
                }
                reload(form.domainHint)
            }
        }
    }, onPickPhoto = {
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profile_delete)) },
            text = { Text(stringResource(R.string.profile_delete_body)) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { (context.applicationContext as OpenWildApp).profiles.deleteAll() }
                        reload("")
                    }
                }) { Text(stringResource(R.string.profile_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun jpegWithoutExif(context: android.content.Context, uri: Uri): ByteArray? {
    val bitmap = Contribute.decodeUpright(context, uri, 1024) ?: return null
    val bytes = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
    bitmap.recycle()
    return bytes
}

private fun domainHint(context: android.content.Context, email: String): String {
    val domains = runCatching { context.assets.open("academic-domains.txt").bufferedReader().readText() }.getOrDefault("")
    val abused = runCatching { context.assets.open("academic-domains-abused.txt").bufferedReader().readText() }.getOrDefault("")
    val result = AcademicDomains.check(email, AcademicDomains.parseList(domains), AcademicDomains.parseList(abused))
    return when (result.reason) {
        "listed", "suffix" -> context.getString(R.string.profile_domain_yes, result.matchedDomain ?: "")
        "free-mail" -> context.getString(R.string.profile_domain_free)
        "abused" -> context.getString(R.string.profile_domain_abused)
        "not-an-email" -> context.getString(R.string.profile_domain_bad)
        else -> context.getString(R.string.profile_domain_no)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(form: ProfileForm, onEvent: (ProfileEvent) -> Unit, onPickPhoto: () -> Unit = {}) {
    ScrollColumn {
        Text(stringResource(R.string.profile_intro), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (form.onlineEnabled) R.string.profile_online_on else R.string.profile_online_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Footer()

        SectionCard(stringResource(R.string.profile_you), Icons.Filled.Person, MaterialTheme.colorScheme.primary) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(form.displayName), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                OutlinedButton(onClick = onPickPhoto) { Text(stringResource(R.string.profile_photo)) }
            }
            if (form.hasPhoto) {
                Text(stringResource(R.string.profile_photo_saved), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = form.displayName,
                onValueChange = { onEvent(ProfileEvent.Name(it)) },
                label = { Text(stringResource(R.string.profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.title,
                onValueChange = { onEvent(ProfileEvent.Title(it)) },
                label = { Text(stringResource(R.string.profile_title_field)) },
                supportingText = { Text(stringResource(R.string.profile_title_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.affiliation,
                onValueChange = { onEvent(ProfileEvent.Affiliation(it)) },
                label = { Text(stringResource(R.string.profile_affiliation)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_anonymous), modifier = Modifier.weight(1f))
                Switch(checked = form.anonymous, onCheckedChange = { onEvent(ProfileEvent.Anonymous(it)) })
            }
            Text(
                stringResource(if (form.anonymous) R.string.profile_anonymous_on else R.string.profile_anonymous_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.profile_shown, form.photoCreditLine()), fontWeight = FontWeight.Bold)
            Text(form.reviewCreditLine(), style = MaterialTheme.typography.bodySmall)
            if (form.badgeVisible) {
                Text(
                    stringResource(R.string.profile_badge, form.verifiedDomain),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        SectionCard(stringResource(R.string.profile_license), Icons.Filled.Badge, MaterialTheme.colorScheme.primary) {
            Text(stringResource(R.string.profile_license_help), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoLicense.entries.forEach { license ->
                    FilterChip(
                        selected = form.license == license.code,
                        onClick = { onEvent(ProfileEvent.License(license.code)) },
                        label = { Text(license.label) },
                    )
                }
            }
        }

        SectionCard(stringResource(R.string.profile_school), Icons.Filled.Verified, MaterialTheme.colorScheme.primary) {
            if (form.badgeVisible) {
                Text(
                    stringResource(R.string.profile_badge, form.verifiedDomain),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(stringResource(R.string.profile_badge_off), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = form.email,
                onValueChange = { onEvent(ProfileEvent.Email(it)) },
                label = { Text(stringResource(R.string.profile_email)) },
                supportingText = { Text(stringResource(R.string.profile_email_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.domainHint.isNotBlank()) Text(form.domainHint, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEvent(ProfileEvent.CheckDomain) }) {
                    Text(stringResource(R.string.profile_check))
                }
                Button(onClick = {}, enabled = form.onlineEnabled) {
                    Text(stringResource(R.string.profile_confirm))
                }
            }
            if (!form.onlineEnabled) {
                Text(stringResource(R.string.profile_confirm_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (form.contributions.isNotEmpty()) {
            SectionCard(stringResource(R.string.profile_contributions), Icons.Filled.Flag, MaterialTheme.colorScheme.primary) {
                form.contributions.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (row.reported) stringResource(R.string.profile_reported, row.line) else row.line)
                        if (!row.reported) {
                            TextButton(onClick = { onEvent(ProfileEvent.Report(row.id)) }) {
                                Text(stringResource(R.string.profile_report))
                            }
                        }
                    }
                }
            }
        }

        Button(onClick = { onEvent(ProfileEvent.Save) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_save))
        }
        OutlinedButton(onClick = { onEvent(ProfileEvent.Export) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_export))
        }
        OutlinedButton(onClick = { onEvent(ProfileEvent.Delete) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
            Text(stringResource(R.string.profile_delete), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Credit line for a saved profile. Empty when the person has not created one, so existing screens stay the same. */
@Composable
fun ProfileCreditNote(kind: String) {
    val context = LocalContext.current
    val line by produceState("") {
        value = withContext(Dispatchers.IO) {
            val profile = (context.applicationContext as OpenWildApp).profiles.current() ?: return@withContext ""
            val name = Attribution.creditName(profile.visibility, profile.displayName)
            val license = PhotoLicense.fromCode(profile.license) ?: PhotoLicense.DEFAULT
            if (kind == "photo") Attribution.photoCredit(name, license) else Attribution.reviewCredit(name)
        }
    }
    if (line.isNotBlank()) {
        Text(stringResource(R.string.profile_credit_line, line), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return parts.take(2).joinToString("") { it.take(1).uppercase() }
}
