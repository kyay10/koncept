package io.github.kyay10.koncept

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.inference.InferenceComponents
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator


class KonceptFirExtensionRegistrar(
  val messageCollector: MessageCollector, val configuration: CompilerConfiguration
) : FirExtensionRegistrar() {
  @OptIn(SessionConfiguration::class)
  override fun ExtensionRegistrarContext.configurePlugin() {
    +{ firSession: FirSession ->
      val conflictResolverFactory = DelegatingConeCallConflictResolverFactory(firSession.callConflictResolverFactory)
      val transformerComponentsList = conflictResolverFactory.transformerComponentsList
      firSession.register(
        ConeCallConflictResolverFactory::class, conflictResolverFactory
      )
      KonceptExpressionResolutionExtension(firSession, messageCollector, transformerComponentsList)
    }
  }
}

class DelegatingConeCallConflictResolverFactory(val underlying: ConeCallConflictResolverFactory) :
  ConeCallConflictResolverFactory() {
  val transformerComponentsList: MutableList<BodyResolveComponents> = mutableListOf()
  override fun create(
    typeSpecificityComparator: TypeSpecificityComparator,
    components: InferenceComponents,
    transformerComponents: BodyResolveComponents
  ): ConeCallConflictResolver {
    transformerComponentsList.add(transformerComponents)
    return ConeCompositeConflictResolver(
      underlying.create(
        typeSpecificityComparator,
        components,
        transformerComponents
      ),
      KonceptCallConflictResolver()
    )
  }
}

class KonceptCallConflictResolver : ConeCallConflictResolver() {
  override fun chooseMaximallySpecificCandidates(
    candidates: Set<Candidate>,
    discriminateGenerics: Boolean,
    discriminateAbstracts: Boolean
  ): Set<Candidate> {
    return candidates.filterTo(mutableSetOf()) { it.symbol.hasAnnotation(KonceptExpressionResolutionExtension.CONCEPT_ANNOTATION_CLASS_ID) }
      .ifEmpty { candidates }
  }
}
