package io.github.kyay10.koncept.runners

import io.github.kyay10.koncept.services.ExtensionRegistrarConfigurator
import io.github.kyay10.koncept.services.PreludeProvider
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.backend.handlers.JvmBoxRunner
import org.jetbrains.kotlin.test.backend.ir.JvmIrBackendFacade
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.fir2IrStep
import org.jetbrains.kotlin.test.builders.irHandlersStep
import org.jetbrains.kotlin.test.builders.jvmArtifactsHandlersStep
import org.jetbrains.kotlin.test.directives.FirDiagnosticsDirectives
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.runners.RunnerWithTargetBackendForTestGeneratorMarker
import org.jetbrains.kotlin.test.runners.baseFirDiagnosticTestConfiguration

open class AbstractSandboxTest : BaseTestRunner(), RunnerWithTargetBackendForTestGeneratorMarker {
  override val targetBackend: TargetBackend
    get() = TargetBackend.JVM_IR

  override fun TestConfigurationBuilder.configuration() {
    globalDefaults {
      targetBackend = TargetBackend.JVM_IR
      targetPlatform = JvmPlatforms.defaultJvmPlatform
      dependencyKind = DependencyKind.Binary
    }

    baseFirDiagnosticTestConfiguration()

    defaultDirectives {
      +FirDiagnosticsDirectives.ENABLE_PLUGIN_PHASES
    }

    useConfigurators(
      ::ExtensionRegistrarConfigurator,
      ::PreludeProvider
    )
    fir2IrStep()
    irHandlersStep {}
    facadeStep(::JvmIrBackendFacade)
    jvmArtifactsHandlersStep {
      useHandlers(::JvmBoxRunner)
    }

    useAfterAnalysisCheckers(::BlackBoxCodegenSuppressor)
  }
}
