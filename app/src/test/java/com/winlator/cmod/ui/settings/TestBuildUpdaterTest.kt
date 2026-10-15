package com.winlator.cmod.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
