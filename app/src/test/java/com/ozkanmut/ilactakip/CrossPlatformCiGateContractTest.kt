package com.ozkanmut.ilactakip

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformCiGateContractTest {
    @Test
    fun protocolChangesMustRunBothPlatformTestSuites() {
        val workflow = File("../.github/workflows/cross-platform-protocol-gate.yml")
        assertTrue("Cross-platform protocol gate workflow is missing", workflow.isFile)

        val text = workflow.readText()
        assertTrue("Gate must watch Android changes", text.contains("- 'app/**'"))
        assertTrue("Gate must watch iOS changes", text.contains("- 'ios/**'"))
        assertTrue("Gate must watch shared fixtures", text.contains("- 'protocol-fixtures/**'"))
        assertTrue("Gate must watch protocol spec", text.contains("- 'PROTOCOL_SPEC.md'"))
        assertTrue("Gate must execute Android unit tests", text.contains("gradle testDebugUnitTest"))
        assertTrue("Gate must execute iOS tests", text.contains("xcodebuild") && text.contains(" test"))
    }
}
