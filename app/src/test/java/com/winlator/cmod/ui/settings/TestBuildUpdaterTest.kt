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
        assertEquals("https://example.invalid/apk" to 2048L, TestBuildUpdater.apkArtifactUrl(json))
    }

    @Test
    fun expiredApkMeansNothingToDownload() {
        val json = """{"artifacts":[{"name":"Bionic-Build-5","archive_download_url":"https://example.invalid/apk","size_in_bytes":1,"expired":true}]}"""
        assertNull(TestBuildUpdater.apkArtifactUrl(json))
    }
}
