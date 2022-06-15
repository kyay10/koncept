package io.github.kyay10.koncept.services

import io.github.kyay10.koncept.BuildConfig
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions
import java.io.File
import java.io.FilenameFilter

class PreludeProvider(testServices: TestServices) : EnvironmentConfigurator(testServices) {
  companion object {
    private val PRELUDE_JAR_FILTER =
      FilenameFilter { _, name -> name.startsWith("prelude-jvm") && name.endsWith(".jar") }
  }

  override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
    val libDir = File(BuildConfig.PRELUDE_JVM_JAR_PATH)
    testServices.assertions.assertTrue(libDir.exists() && libDir.isDirectory, failMessage)
    val jar = libDir.listFiles(PRELUDE_JAR_FILTER)?.firstOrNull() ?: testServices.assertions.fail(failMessage)
    configuration.addJvmClasspathRoot(jar)
  }

  private val failMessage = { "Prelude Jvm jar does not exist. Please run :prelude:jvmJar" }
}
