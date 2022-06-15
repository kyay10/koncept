package io.github.kyay10.koncept

import org.jetbrains.kotlin.generators.generateTestGroupSuiteWithJUnit5
import io.github.kyay10.koncept.runners.AbstractBoxTest
import io.github.kyay10.koncept.runners.AbstractDiagnosticTest
import io.github.kyay10.koncept.runners.AbstractSandboxTest

fun main() {
  generateTestGroupSuiteWithJUnit5 {
    testGroup(testDataRoot = "src/testData", testsRoot = "src/test-gen") {
      testClass<AbstractDiagnosticTest> {
        model("diagnostics")
      }
      testClass<AbstractBoxTest> {
        model("box")
      }
      testClass<AbstractSandboxTest>{
        model("sandbox")
      }
    }
  }
}
