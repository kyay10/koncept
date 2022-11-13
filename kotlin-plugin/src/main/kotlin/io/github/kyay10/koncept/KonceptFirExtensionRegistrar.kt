package io.github.kyay10.koncept

import io.github.kyay10.koncept.internal.KonceptCheckContextReceivers
import io.github.kyay10.koncept.utils.safeAs
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.PrivateForInline
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.calls.tower.FirTowerResolver
import org.jetbrains.kotlin.fir.resolve.calls.tower.TowerGroup
import org.jetbrains.kotlin.fir.resolve.inference.InferenceComponents
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.*
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator
import org.jetbrains.kotlin.resolve.calls.tower.CandidateApplicability
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.resume

private val NOTHING_FUN_FQNAME = FqName(BuildConfig.NOTHING_FUN_FQNAME)

class KonceptFirExtensionRegistrar(
  val messageCollector: MessageCollector, val configuration: CompilerConfiguration
) : FirExtensionRegistrar() {
  @OptIn(SessionConfiguration::class)
  override fun ExtensionRegistrarContext.configurePlugin() {
    +{ firSession: FirSession ->
      val conflictResolverFactory = KonceptConeCallConflictResolverFactory(firSession.callConflictResolverFactory)
      val transformerComponentsList = conflictResolverFactory.transformerComponentsList
      firSession.register(
        ConeCallConflictResolverFactory::class, conflictResolverFactory
      )
      KonceptExpressionResolutionExtension(
        firSession, messageCollector, transformerComponentsList
      ).also { conflictResolverFactory.conceptFunctionsLazy = it.conceptFunctionsLazy }
    }
  }
}

class KonceptConeCallConflictResolverFactory(val underlying: ConeCallConflictResolverFactory) :
  ConeCallConflictResolverFactory() {
  val transformerComponentsList: MutableList<BodyResolveComponents> = mutableListOf()
  lateinit var conceptFunctionsLazy: Lazy<List<FirNamedFunctionSymbol>>

  @OptIn(PrivateForInline::class)
  override fun create(
    typeSpecificityComparator: TypeSpecificityComparator,
    components: InferenceComponents,
    transformerComponents: BodyResolveComponents
  ): ConeCallConflictResolver {
    //transformerComponents.towerDataContext.addNonLocalTowerDataElements(listOf(KonceptTopLevelFakeFirScope().asTowerDataElement(false)))
    transformerComponents.safeAs<FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents>()?.run {
      context.addReceiver(
        null, ContextReceiverValueForCallable(
          components.session.symbolProvider.getTopLevelFunctionSymbols(
            NOTHING_FUN_FQNAME.parent(), NOTHING_FUN_FQNAME.shortName()
          ).single(),
          components.session.builtinTypes.nothingType.type,
          null,
          session,
          scopeSession,
          contextReceiverNumber = -1
        )
      )
    }
    transformerComponentsList.add(transformerComponents)
    return if (transformerComponents is FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents) KonceptCallConflictResolver(
      underlying.create(
        typeSpecificityComparator, components, transformerComponents
      ), transformerComponents, conceptFunctionsLazy
    ) else underlying.create(typeSpecificityComparator, components, transformerComponents)
  }
}

class KonceptTopLevelFakeFirScope : FirScope() {
  override fun processDeclaredConstructors(processor: (FirConstructorSymbol) -> Unit) {
  }

  override fun processFunctionsByName(name: Name, processor: (FirNamedFunctionSymbol) -> Unit) {
    super.processFunctionsByName(name, processor)
  }

  override fun processPropertiesByName(name: Name, processor: (FirVariableSymbol<*>) -> Unit) {
    super.processPropertiesByName(name, processor)
  }
}

class KonceptCallConflictResolver(
  val delegate: ConeCallConflictResolver,
  val components: FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents,
  val conceptFunctionsLazy: Lazy<List<FirNamedFunctionSymbol>>
) : ConeCallConflictResolver() {
  private val towerResolver: FirTowerResolver = FirTowerResolver(
    components, components.resolutionStageRunner, KonceptCandidatesCollector(
      components, components.resolutionStageRunner, conceptFunctionsLazy
    )
  )

  override fun chooseMaximallySpecificCandidates(
    candidates: Set<Candidate>, discriminateGenerics: Boolean, discriminateAbstracts: Boolean
  ): Set<Candidate> {
    if (candidates.any {
        it.contextReceiverArguments().any(FirExpression::isNothingFunContextArgument)
      }) {
      // We potentially have a function call that needs concepts to be evaluated
      // However, it might be that the function has a non-context version and a context version that are identical otherwise in signature,
      // so we need to be careful!
      towerResolver.reset()
      val result = towerResolver.runResolver(candidates.first().callInfo, components.transformer.resolutionContext)
      val bestCandidates = result.bestCandidates()
      return delegate.chooseMaximallySpecificCandidates(bestCandidates, discriminateGenerics, discriminateAbstracts) // TODO: overload resolution by lambda return type
    } else return delegate.chooseMaximallySpecificCandidates(candidates, discriminateGenerics, discriminateAbstracts)
  }
}

class KonceptCandidatesCollector(
  components: BodyResolveComponents,
  resolutionStageRunner: ResolutionStageRunner,
  val conceptFunctionsLazy: Lazy<List<FirNamedFunctionSymbol>>
) : CandidateCollector(components, resolutionStageRunner) {
  val conceptFunctions by conceptFunctionsLazy
  override fun consumeCandidate(
    group: TowerGroup, candidate: Candidate, context: ResolutionContext
  ): CandidateApplicability {
    processCandidate(candidate, context, candidate.callInfo.callKind.resolutionSequence.map {
      if (it is CheckContextReceivers) KonceptCheckContextReceivers(conceptFunctions) else it
    })
    return super.consumeCandidate(group, candidate, context)
  }
}


fun processCandidate(
  candidate: Candidate,
  context: ResolutionContext,
  resolutionSequence: List<ResolutionStage> = candidate.callInfo.callKind.resolutionSequence,
  indexRange: IntRange = resolutionSequence.indices,
  stopOnFirstError: Boolean = true
): CandidateApplicability {
  val sink = CheckerSinkImpl(candidate, stopOnFirstError = stopOnFirstError)
  var finished = false
  sink.continuation = suspend {
    resolutionSequence.forEachIndexed { index, stage ->
      if (index !in indexRange) if (index < candidate.passedStages) return@forEachIndexed
      candidate.passedStages++
      stage.check(candidate, candidate.callInfo, sink, context)
    }
  }.createCoroutineUnintercepted(completion = object : Continuation<Unit> {
    override val context: CoroutineContext
      get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
      result.exceptionOrNull()?.let { throw it }
      finished = true
    }
  })

  while (!finished) {
    sink.continuation!!.resume(Unit)
    if (!candidate.isSuccessful) {
      break
    }
  }
  return candidate.currentApplicability
}

val FirExpression.isNothingFunContextArgument get() = safeAs<FirThisReceiverExpression>()?.calleeReference?.boundSymbol?.safeAs<FirNamedFunctionSymbol>()?.callableId?.asSingleFqName() == NOTHING_FUN_FQNAME
