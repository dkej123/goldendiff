package com.github.dkwasniak.goldendiff.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class XcodeRunWorkflowTest {
    private fun run(device: XcodeRunDevice?) = XcodeTestRun(
        "run", File("run.xcresult"), 1, device, 1, 0, emptyList(),
    )

    @Test
    fun `exact platform model and os match does not warn`() {
        assertNull(XcodeRunPreflight.mismatch(
            run(XcodeRunDevice("iOS Simulator", "iPhone 16 Pro", "iOS 18.5")),
            XcodeDestination("iOS Simulator", "iPhone 16 Pro", "18.5"),
        ))
    }

    @Test
    fun `platform model or exact os difference warns`() {
        val selected = run(XcodeRunDevice("iOS Simulator", "iPhone 16 Pro", "18.5"))
        assertNotNull(XcodeRunPreflight.mismatch(selected, XcodeDestination("iOS", "iPhone 16 Pro", "18.5")))
        assertNotNull(XcodeRunPreflight.mismatch(selected, XcodeDestination("iOS", "iPhone 16 Pro", null)))
        assertNotNull(XcodeRunPreflight.mismatch(selected, XcodeDestination("iOS Simulator", "iPhone 15", "18.5")))
        assertNotNull(XcodeRunPreflight.mismatch(selected, XcodeDestination("iOS Simulator", "iPhone 16 Pro", "18.5.1")))
    }

    @Test
    fun `unknown run or destination metadata does not create a false mismatch`() {
        assertNull(XcodeRunPreflight.mismatch(run(null), XcodeDestination("iOS Simulator", "iPhone", "18.5")))
        assertNull(XcodeRunPreflight.mismatch(
            run(XcodeRunDevice("iOS Simulator", "iPhone", "18.5")),
            XcodeDestination("iOS Simulator", "iPhone", null),
        ))
    }

    @Test
    fun `initial run catalog loading blocks preflight instead of treating metadata as unknown`() {
        assertTrue(XcodeRunPreflight.awaitsRunCatalog(runsLoading = true))
        assertFalse(XcodeRunPreflight.awaitsRunCatalog(runsLoading = false))
    }

    @Test
    fun `cancel runs nothing and run anyway consumes the pending command exactly once`() {
        val workflow = PendingXcodeCommandWorkflow()
        val pending = PendingSnapshotCommand(
            "/project-a", AppState.CommandKind.VERIFY, "View.swift", "case", listOf("verify"), "Verify", true,
        )
        val selectedRun = run(XcodeRunDevice("iOS Simulator", "iPhone 16", "18.5"))
        val otherTarget = XcodeDestination("iOS Simulator", "iPhone 15", "18.5")

        assertFalse(workflow.request(pending, selectedRun, otherTarget))
        workflow.cancel()
        assertNull(workflow.takeRunAnyway("/project-a"))

        assertFalse(workflow.request(pending, selectedRun, otherTarget))
        assertSame(pending, workflow.takeRunAnyway("/project-a"))
        assertNull(workflow.takeRunAnyway("/project-a"))
        assertTrue(workflow.request(pending, selectedRun, XcodeDestination("iOS Simulator", "iPhone 16", "18.5")))
    }

    @Test
    fun `run anyway cannot cross a project boundary and is consumed`() {
        val workflow = PendingXcodeCommandWorkflow()
        val pending = PendingSnapshotCommand(
            "/project-a", AppState.CommandKind.GENERATE, null, null, listOf("generate"), "Generate", false,
        )
        val selectedRun = run(XcodeRunDevice("iOS Simulator", "iPhone 16", "18.5"))

        assertFalse(
            workflow.request(
                pending,
                selectedRun,
                XcodeDestination("iOS Simulator", "iPhone 15", "18.5"),
            ),
        )
        assertNull(workflow.takeRunAnyway("/project-b"))
        assertNull(workflow.takeRunAnyway("/project-a"))
    }
}
