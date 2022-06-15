package io.github.kyay10.koncept.services

import com.intellij.openapi.project.Project
import io.github.kyay10.koncept.KonceptFirExtensionRegistrar
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices

class ExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
  override fun registerCompilerExtensions(project: Project, module: TestModule, configuration: CompilerConfiguration) {
    val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    FirExtensionRegistrar.registerExtension(
      project,
      KonceptFirExtensionRegistrar(messageCollector, configuration)
    )
  }
}
