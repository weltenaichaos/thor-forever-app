package com.winlator.cmod.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.winlator.cmod.ui.settings.TestBuildUpdater.Note
import org.junit.Test

class TestBuildUpdaterTest {
    @Test
    fun parsesSuccessfulRuns() {
        val json = """{"total_count":2,"workflow_runs":[
            {"id":38285508991,"run_number":23,"head_branch":"debug/window-log","updated_at":"2026-10-12T17:50:00Z",
             "head_commit":{"message":"Debug: log which threads use the CPU\n\nLonger text"}},
            {"id":38282396642,"run_number":21,"head_branch":"debug/window-log","updated_at":"2026-10-12T16:00:00Z"}]}"""
        val builds = TestBuildUpdater.parseRuns(json)
        assertEquals(2, builds.size)
        assertEquals(38285508991L, builds[0].runId)
        assertEquals(23, builds[0].buildNumber)
        assertEquals("debug/window-log", builds[0].branch)
        assertEquals("Debug: log which threads use the CPU", builds[0].title)
        assertEquals("", builds[1].title)
    }

    @Test
    fun picksTheApkArtifactNotTheDriver() {
        val json = """{"artifacts":[
            {"name":"turnip-thor","archive_download_url":"https://example.invalid/turnip","size_in_bytes":10,"expired":false},
            {"name":"Bionic-Build-23","archive_download_url":"https://example.invalid/apk","size_in_bytes":2048,"expired":false}]}"""
        assertEquals("https://example.invalid/apk" to 2048L, TestBuildUpdater.apkArtifactUrl(json, "Bionic-Build-"))
    }

    @Test
    fun expiredApkMeansNothingToDownload() {
        val json = """{"artifacts":[{"name":"Bionic-Build-5","archive_download_url":"https://example.invalid/apk","size_in_bytes":1,"expired":true}]}"""
        assertNull(TestBuildUpdater.apkArtifactUrl(json, "Bionic-Build-"))
    }

    @Test
    fun thorForeverAppTakesOnlyItsOwnArtifactsFromAnyBranch() {
        val json = """{"artifacts":[
            {"name":"Thor-Forever-105","created_at":"2026-10-15T19:20:00Z","expired":false,"workflow_run":{"id":905,"head_branch":"fix/companion-chat-freeze"}},
            {"name":"turnip-thor","created_at":"2026-10-15T19:10:00Z","expired":false,"workflow_run":{"id":905,"head_branch":"fix/companion-chat-freeze"}},
            {"name":"Bionic-Build-104","created_at":"2026-10-15T19:00:00Z","expired":false,"workflow_run":{"id":904,"head_branch":"4.1"}},
            {"name":"Thor-Forever-90","created_at":"2026-10-01T10:00:00Z","expired":true,"workflow_run":{"id":890,"head_branch":"4.1"}},
            {"name":"Thor-Forever-102","created_at":"2026-10-15T18:50:00Z","expired":false,"workflow_run":{"id":902,"head_branch":"release/v0.1.0"}}]}"""
        val builds = TestBuildUpdater.parseArtifacts(json, "Thor-Forever-")
        assertEquals(listOf(105, 102), builds.map { it.buildNumber })
        assertEquals(905L, builds[0].runId)
        assertEquals("fix/companion-chat-freeze", builds[0].branch)
    }

    @Test
    fun releasesSkipDraftsAndReleasesWithoutApk() {
        val json = """[
            {"tag_name":"v0.2.0","draft":true,"prerelease":false,"published_at":null,"assets":[{"name":"Thor-Forever-v0.2.0.apk","browser_download_url":"https://example.invalid/2","size":5}]},
            {"tag_name":"v0.1.1","draft":false,"prerelease":true,"published_at":"2026-10-16T10:00:00Z","assets":[{"name":"notes.txt","browser_download_url":"https://example.invalid/n","size":1}]},
            {"tag_name":"v0.1.0","draft":false,"prerelease":true,"published_at":"2026-10-15T20:00:00Z","assets":[{"name":"Thor-Forever-v0.1.0.apk","browser_download_url":"https://example.invalid/1","size":7}]}]"""
        val releases = TestBuildUpdater.parseReleases(json)
        assertEquals(1, releases.size)
        assertEquals("v0.1.0", releases[0].tag)
        assertEquals("https://example.invalid/1", releases[0].apkUrl)
        assertEquals(7L, releases[0].apkSize)
    }

    @Test
    fun comparesReleaseTagsWithTheInstalledVersion() {
        assertEquals(0, TestBuildUpdater.compareVersion("v0.1.0", "0.1.0"))
        assertEquals(1, TestBuildUpdater.compareVersion("v0.1.1", "0.1.0"))
        assertEquals(1, TestBuildUpdater.compareVersion("v0.10.0", "0.9.3"))
        assertEquals(-1, TestBuildUpdater.compareVersion("v0.1", "0.1.2"))
    }

    @Test
    fun theStartScreenOffersTheNewestNewerRelease() {
        fun release(tag: String) = TestBuildUpdater.Release(tag, "https://example.invalid/$tag", 1, "", true)
        val listed = listOf(release("v0.4.0"), release("v0.5.1"), release("v0.5.0"), release("v0.3.0"))
        assertEquals("v0.5.1", TestBuildUpdater.newestRelease(listed, "0.4.0")?.tag)
        assertNull(TestBuildUpdater.newestRelease(listed, "0.5.1"))
        assertNull(TestBuildUpdater.newestRelease(emptyList(), "0.4.0"))
    }

    @Test
    fun aTestBuildIsOfferedOnlyWhenItsNumberIsHigher() {
        fun build(n: Int) = TestBuildUpdater.CiBuild(n.toLong(), n, "feat/x", "", "")
        val listed = listOf(build(183), build(181), build(182))
        assertEquals(183, TestBuildUpdater.newestBuild(listed, 181)?.buildNumber)
        assertNull(TestBuildUpdater.newestBuild(listed, 183))
        // A local build has no number to compare with.
        assertNull(TestBuildUpdater.newestBuild(listed, 0))
    }

    @Test
    fun whatsNewTakesOnlyTheNewerVersionsWithoutMarkdown() {
        val notes = """# Thor Forever v0.5.0 (alpha, pre-release)

Thor Forever is an Android app.

## New in v0.5.0

The Forever Companion:
- **Round** buttons again.
- The map:
  - Zoom is `smoother`.

The app:
- The start screen says when a new version is out.

Coming from v0.1.1 or later: Settings > **Check for updates** installs it.

## New in v0.4.0

- Older things.

## Install

1. Download the APK.
- Not a change.
""".replace("\n", "\r\n")
        assertEquals(listOf(
            Note("The Forever Companion:", Note.Kind.GROUP),
            Note("Round buttons again.", Note.Kind.POINT),
            Note("The map:", Note.Kind.POINT),
            Note("Zoom is smoother.", Note.Kind.SUB_POINT),
            Note("The app:", Note.Kind.GROUP),
            Note("The start screen says when a new version is out.", Note.Kind.POINT)
        ), TestBuildUpdater.whatsNew(notes, "0.4.0"))
        // Two versions behind: each version under its own heading.
        val both = TestBuildUpdater.whatsNew(notes, "0.3.0")
        assertEquals(Note("New in v0.5.0", Note.Kind.VERSION), both.first())
        assertEquals(listOf(Note("New in v0.4.0", Note.Kind.VERSION), Note("Older things.", Note.Kind.POINT)), both.takeLast(2))
        assertEquals(emptyList<Note>(), TestBuildUpdater.whatsNew(notes, "0.5.0"))
    }
}
