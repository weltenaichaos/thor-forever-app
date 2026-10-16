package com.winlator.cmod.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.winlator.cmod.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Lists the newest successful CI builds of this app on GitHub, downloads the APK of the one
 * picked and opens Android's installer for it. GitHub only hands out build files to a
 * signed-in user, so this needs a GitHub access token, which is kept in this app's private
 * no-backup folder: it stays on this device, survives app updates and is gone on uninstall.
 * The Thor Forever app also lists the published releases, which need no token.
 */
object TestBuildUpdater {
    private const val REPO = "weltenaichaos/thor-forever-app"
    /** The normal app lists builds of the test branch only: other branches' builds are CI checks for pull requests. */
    const val TEST_BRANCH = "debug/window-log"
    private const val RUNS_URL = "https://api.github.com/repos/$REPO/actions/workflows/build.yml/runs?status=success&per_page=10&branch=$TEST_BRANCH"
    private const val ARTIFACTS_URL = "https://api.github.com/repos/$REPO/actions/artifacts?per_page=50"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    /** The app's FileProvider, under its own package name (the test copy has its own). */
    private const val FILE_PROVIDER_SUFFIX = ".tileprovider"
    private const val TOKEN_FILE = "github-token"
    private const val NORMAL_APP_ARTIFACT = "Bionic-Build-"

    /**
     * The CI artifact this kind of app comes as: "Bionic-Build-" (the normal app),
     * "Thor-Setup-Test-" or "Thor-Forever-". Only those install over it: the others are
     * separate apps to Android.
     */
    val apkArtifactPrefix: String get() = BuildConfig.APK_ARTIFACT_PREFIX

    /** The branch whose builds are listed, or null for any branch (Thor Forever and the setup test copy are built from several). */
    val buildsBranch: String? get() = if (apkArtifactPrefix == NORMAL_APP_ARTIFACT) TEST_BRANCH else null

    /** The Thor Forever app also lists the published releases. */
    val listsReleases: Boolean get() = BuildConfig.THOR_RELEASE

    data class CiBuild(val runId: Long, val buildNumber: Int, val branch: String, val title: String, val finishedAt: String)

    data class Release(val tag: String, val apkUrl: String, val apkSize: Long, val publishedAt: String, val prerelease: Boolean)

    /** This app's CI build number, 0 for a local build. */
    val installedBuild: Int get() = BuildConfig.CI_BUILD_NUMBER

    fun loadToken(context: Context): String =
        File(context.noBackupFilesDir, TOKEN_FILE).takeIf { it.exists() }?.readText()?.trim().orEmpty()

    fun saveToken(context: Context, token: String) {
        File(context.noBackupFilesDir, TOKEN_FILE).writeText(token.trim())
    }

    @JvmStatic
    fun parseRuns(json: String): List<CiBuild> {
        val runs = JSONObject(json).optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until runs.length()).map { i ->
            val run = runs.getJSONObject(i)
            CiBuild(
                runId = run.getLong("id"),
                buildNumber = run.optInt("run_number"),
                branch = run.optString("head_branch"),
                title = run.optJSONObject("head_commit")?.optString("message").orEmpty().lineSequence().firstOrNull().orEmpty(),
                finishedAt = run.optString("updated_at")
            )
        }
    }

    /**
     * The newest builds from the list of all the repository's artifacts: only this kind of
     * app's APKs, from any branch, newest first.
     */
    @JvmStatic
    @JvmOverloads
    fun parseArtifacts(json: String, prefix: String = apkArtifactPrefix): List<CiBuild> {
        val artifacts = JSONObject(json).optJSONArray("artifacts") ?: return emptyList()
        return (0 until artifacts.length()).mapNotNull { i ->
            val artifact = artifacts.getJSONObject(i)
            val name = artifact.optString("name")
            val number = if (name.startsWith(prefix)) name.removePrefix(prefix).toIntOrNull() else null
            val run = artifact.optJSONObject("workflow_run")
            if (number == null || run == null || artifact.optBoolean("expired")) return@mapNotNull null
            CiBuild(
                runId = run.getLong("id"),
                buildNumber = number,
                branch = run.optString("head_branch"),
                title = "",
                finishedAt = artifact.optString("created_at")
            )
        }.distinctBy { it.runId }.sortedByDescending { it.buildNumber }.take(10)
    }

    /** Published releases (drafts aside) that carry a Thor Forever APK, newest first as GitHub lists them. */
    @JvmStatic
    fun parseReleases(json: String): List<Release> {
        val releases = JSONArray(json)
        return (0 until releases.length()).mapNotNull { i ->
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                val name = it.optString("name")
                name.startsWith("Thor-Forever") && name.endsWith(".apk", ignoreCase = true)
            } ?: return@mapNotNull null
            Release(
                tag = release.optString("tag_name"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                publishedAt = release.optString("published_at"),
                prerelease = release.optBoolean("prerelease")
            )
        }
    }

    /** A release tag ("v0.1.2") against an installed version ("0.1.0"): 1 newer, 0 the same, -1 older. */
    @JvmStatic
    @JvmOverloads
    fun compareVersion(tag: String, installed: String = BuildConfig.VERSION_NAME): Int {
        fun parts(version: String) = version.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d.coerceIn(-1, 1)
        }
        return 0
    }

    /** The download address of the run's APK artifact, or null when it has none (or it expired). */
    @JvmStatic
    @JvmOverloads
    fun apkArtifactUrl(json: String, prefix: String = apkArtifactPrefix): Pair<String, Long>? {
        val artifacts = JSONObject(json).optJSONArray("artifacts") ?: return null
        for (i in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(i)
            if (!artifact.optString("name").startsWith(prefix) || artifact.optBoolean("expired")) continue
            return artifact.getString("archive_download_url") to artifact.optLong("size_in_bytes")
        }
        return null
    }

    /** A GitHub API answer; without a token only public things (like releases) can be read. */
    private fun get(url: String, token: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            when (val code = connection.responseCode) {
                200 -> return connection.inputStream.bufferedReader().use { it.readText() }
                403 -> if (token == null) throw IllegalStateException("GitHub is busy right now (answered 403). Try again in a few minutes.")
                    else throw IllegalStateException("The token has no access to the builds (GitHub answered 403). It needs read access to Actions on $REPO.")
                401 -> throw IllegalStateException("GitHub did not accept the token. Check that it is copied completely and not expired.")
                404 -> throw IllegalStateException("The token has no access to the builds (GitHub answered $code). It needs read access to Actions on $REPO.")
                else -> throw IllegalStateException("GitHub answered $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchBuilds(token: String): List<CiBuild> =
        if (buildsBranch != null) parseRuns(get(RUNS_URL, token)) else parseArtifacts(get(ARTIFACTS_URL, token))

    fun fetchReleases(): List<Release> = parseReleases(get(RELEASES_URL, null))

    /** Downloads the release's APK (a public file, no token) into our own cache folder, replacing the previous download. */
    fun downloadRelease(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "test-build").apply { mkdirs() }
        val apk = File(dir, "thor-forever-release.apk")
        // GitHub redirects to its file storage; HttpURLConnection follows https to https.
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            if (connection.responseCode != 200) throw IllegalStateException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            val counting = CountingStream(connection.inputStream) { done ->
                if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
            }
            counting.use { input -> apk.outputStream().use { input.copyTo(it, 64 * 1024) } }
            return apk
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the build's zip and unpacks only the APK from it while downloading, into our
     * own cache folder, replacing the previous download.
     */
    fun download(context: Context, build: CiBuild, token: String, onProgress: (Float) -> Unit): File {
        val (zipUrl, zipSize) = apkArtifactUrl(get("https://api.github.com/repos/$REPO/actions/runs/${build.runId}/artifacts", token))
            ?: throw IllegalStateException("This build has no APK any more (GitHub keeps build files for a limited time).")

        // GitHub answers with a redirect to a storage address that must be fetched without the token.
        val first = URL(zipUrl).openConnection() as HttpURLConnection
        val location: String
        try {
            first.instanceFollowRedirects = false
            first.connectTimeout = 15000
            first.readTimeout = 15000
            first.setRequestProperty("Authorization", "Bearer $token")
            val code = first.responseCode
            location = first.getHeaderField("Location")
                ?: throw IllegalStateException("Download failed (GitHub answered $code)")
        } finally {
            first.disconnect()
        }

        val dir = File(context.cacheDir, "test-build").apply { mkdirs() }
        val apk = File(dir, "thor-forever-test.apk")
        val connection = URL(location).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            if (connection.responseCode != 200) throw IllegalStateException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: zipSize
            val counting = CountingStream(connection.inputStream) { done ->
                if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
            }
            ZipInputStream(counting).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: throw IllegalStateException("The build's zip has no APK in it.")
                    if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) continue
                    apk.outputStream().use { zip.copyTo(it, 64 * 1024) }
                    return apk
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Android asks once whether this app may install apps; until then we open that setting. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** "2026-10-13T15:41:50Z" -> "13 Oct 17:41" in the phone's time zone. */
    @JvmStatic
    fun shortTime(iso: String): String = try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(parser.parse(iso)!!)
    } catch (e: Exception) {
        iso
    }

    private class CountingStream(private val input: java.io.InputStream, private val onCount: (Long) -> Unit) : java.io.FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count - lastReported >= 1024 * 1024) {
                lastReported = count
                onCount(count)
            }
        }
    }
}

private sealed interface UpdaterState {
    data object NeedsToken : UpdaterState
    data object Checking : UpdaterState
    /** [builds] is null without a token. */
    data class Listed(val releases: List<TestBuildUpdater.Release>, val builds: List<TestBuildUpdater.CiBuild>?) : UpdaterState
    data class Downloading(val label: String, val progress: Float) : UpdaterState
    data class Ready(val label: String, val apk: File) : UpdaterState
    data class Failed(val message: String) : UpdaterState
}

@Composable
fun TestBuildUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(TestBuildUpdater.loadToken(context)) }
    val releases = TestBuildUpdater.listsReleases
    var state by remember { mutableStateOf<UpdaterState>(if (token.isEmpty() && !releases) UpdaterState.NeedsToken else UpdaterState.Checking) }
    var refresh by remember { mutableIntStateOf(0) }
    val installed = TestBuildUpdater.installedBuild

    LaunchedEffect(refresh, token) {
        if (token.isEmpty() && !releases) return@LaunchedEffect
        state = UpdaterState.Checking
        state = try {
            withContext(Dispatchers.IO) {
                UpdaterState.Listed(
                    if (releases) TestBuildUpdater.fetchReleases() else emptyList(),
                    if (token.isNotEmpty()) TestBuildUpdater.fetchBuilds(token) else null
                )
            }
        } catch (e: Exception) {
            UpdaterState.Failed(e.message ?: "Could not reach GitHub.")
        }
    }

    fun install(apk: File) {
        if (!TestBuildUpdater.canInstall(context)) {
            TestBuildUpdater.openInstallPermission(context)
            return
        }
        try {
            TestBuildUpdater.openInstaller(context, apk)
        } catch (e: ActivityNotFoundException) {
            state = UpdaterState.Failed("No installer found on this device.")
        }
    }

    fun download(label: String, fetch: (onProgress: (Float) -> Unit) -> File) {
        state = UpdaterState.Downloading(label, 0f)
        scope.launch {
            state = try {
                val apk = withContext(Dispatchers.IO) {
                    fetch { progress ->
                        scope.launch { if (state is UpdaterState.Downloading) state = UpdaterState.Downloading(label, progress) }
                    }
                }
                UpdaterState.Ready(label, apk)
            } catch (e: Exception) {
                UpdaterState.Failed(e.message ?: "Download failed.")
            }
        }
    }

    val current = state
    var tokenDraft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (current !is UpdaterState.Downloading) onDismiss() },
        title = { Text(if (releases) "Updates" else "Test builds") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    (if (releases) "Installed: version ${BuildConfig.VERSION_NAME}, " else "Installed: ") +
                        (if (installed > 0) "build $installed (${BuildConfig.CI_BRANCH})" else "local build")
                )
                when (current) {
                    UpdaterState.NeedsToken -> {
                        Text(
                            "GitHub only gives build files to a signed-in user, so this needs a GitHub access token once. " +
                                "Create a fine-grained token on github.com (Settings › Developer settings › Personal access tokens), " +
                                "for the repository thor-forever-app only, with \"Actions: Read-only\". " +
                                "It is stored only in this app on this device and is deleted when you uninstall the app.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = tokenDraft,
                            onValueChange = { tokenDraft = it },
                            label = { Text("GitHub token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    UpdaterState.Checking -> Text("Checking GitHub for builds…")
                    is UpdaterState.Listed -> {
                        if (releases) {
                            Text("Releases", fontWeight = FontWeight.SemiBold)
                            if (current.releases.isEmpty()) Text("No release published yet.", style = MaterialTheme.typography.bodySmall)
                            else Text("Tap one to download and install it:", style = MaterialTheme.typography.bodySmall)
                            current.releases.forEachIndexed { index, release ->
                                if (index > 0) HorizontalDivider()
                                ReleaseRow(release) {
                                    download(release.tag) { onProgress -> TestBuildUpdater.downloadRelease(context, release, onProgress) }
                                }
                            }
                            Text("Test builds", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        }
                        val builds = current.builds
                        when {
                            builds == null -> Text("Test builds need a GitHub token: tap Add token.", style = MaterialTheme.typography.bodySmall)
                            builds.isEmpty() -> Text("No successful builds found.")
                            else -> Text(
                                TestBuildUpdater.buildsBranch?.let { "Test builds ($it). Tap one to download and install it:" }
                                    ?: "Tap one to download and install it:",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        builds.orEmpty().forEachIndexed { index, build ->
                            if (index > 0) HorizontalDivider()
                            BuildRow(build, installed) {
                                download("build ${build.buildNumber}") { onProgress -> TestBuildUpdater.download(context, build, token, onProgress) }
                            }
                        }
                    }
                    is UpdaterState.Downloading -> {
                        Text("Downloading ${current.label}… ${(current.progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdaterState.Ready -> Text(
                        if (TestBuildUpdater.canInstall(context)) "Downloaded ${current.label}. Tap Install, then confirm in Android's installer. Your containers and settings stay."
                        else "Downloaded ${current.label}. Android first needs your OK to let this app install apps: tap Allow, switch it on, come back and tap Install."
                    )
                    is UpdaterState.Failed -> Text(current.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (current) {
                UpdaterState.NeedsToken -> TextButton(
                    enabled = tokenDraft.isNotBlank(),
                    onClick = {
                        TestBuildUpdater.saveToken(context, tokenDraft)
                        token = tokenDraft.trim()
                        tokenDraft = ""
                    }
                ) { Text("Save") }
                is UpdaterState.Ready -> TextButton(onClick = { install(current.apk) }) {
                    Text(if (TestBuildUpdater.canInstall(context)) "Install" else "Allow")
                }
                is UpdaterState.Failed, is UpdaterState.Listed -> TextButton(onClick = { refresh++ }) { Text("Refresh") }
                else -> {}
            }
        },
        dismissButton = {
            if (current !is UpdaterState.Downloading) {
                Column {
                    if (current is UpdaterState.Failed || current is UpdaterState.Listed) {
                        TextButton(onClick = { tokenDraft = ""; state = UpdaterState.NeedsToken }) {
                            Text(if (token.isEmpty()) "Add token" else "Change token")
                        }
                    }
                    if (current is UpdaterState.NeedsToken && releases) {
                        TextButton(onClick = { refresh++ }) { Text("Back") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    )
}

@Composable
private fun ReleaseRow(release: TestBuildUpdater.Release, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        val marker = when (TestBuildUpdater.compareVersion(release.tag)) {
            0 -> "  (installed)"
            1 -> "  (newer)"
            else -> ""
        }
        Text("${release.tag}$marker", fontWeight = FontWeight.SemiBold)
        Text(
            (if (release.prerelease) "Test release · " else "") + TestBuildUpdater.shortTime(release.publishedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BuildRow(build: TestBuildUpdater.CiBuild, installed: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        val marker = when {
            build.buildNumber == installed -> "  (installed)"
            build.buildNumber > installed -> "  (newer)"
            else -> ""
        }
        Text("Build ${build.buildNumber}$marker", fontWeight = FontWeight.SemiBold)
        Text("${build.branch} · ${TestBuildUpdater.shortTime(build.finishedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (build.title.isNotBlank()) Text(build.title, style = MaterialTheme.typography.bodySmall)
    }
}
