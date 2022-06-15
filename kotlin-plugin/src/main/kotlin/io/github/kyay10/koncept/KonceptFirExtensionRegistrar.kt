package io.github.kyay10.koncept

import io.github.kyay10.koncept.utils.lastIsInstance
import io.github.kyay10.koncept.utils.map
import io.github.kyay10.koncept.utils.withContexts
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.PrivateForInline
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.analysis.checkers.typeParameterSymbols
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.predicate.has
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.references.builder.buildSimpleNamedReference
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeAmbiguityError
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeDiagnosticWithCandidates
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeInapplicableCandidateError
import org.jetbrains.kotlin.fir.resolve.inference.InferenceComponents
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.*
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.toConeTypeProjection
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.calls.results.TypeSpecificityComparator
import org.jetbrains.kotlin.utils.addToStdlib.cast


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
    return underlying.create(typeSpecificityComparator, components, transformerComponents)
  }
}

class KonceptExpressionResolutionExtension(
  private val firSession: FirSession,
  private val messageCollector: MessageCollector,
  private val transformerComponentsList: MutableList<BodyResolveComponents>
) : FirExpressionResolutionExtension(firSession) {
  companion object {
    val HAS_CONCEPT_ANNOTATION = has(FqName(BuildConfig.CONCEPT_FQNAME))
  }

  val conceptFunctions by lazy {
    session.predicateBasedProvider.getSymbolsByPredicate(HAS_CONCEPT_ANNOTATION)
      .filterIsInstance<FirNamedFunctionSymbol>()
  }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(HAS_CONCEPT_ANNOTATION)
  }

  @OptIn(PrivateForInline::class)
  override fun addNewImplicitReceivers(functionCall: FirFunctionCall): List<ConeKotlinType> {
    val resolveComponents = try {
      resolveComponents.also { it.context.file }
    } catch (e: UninitializedPropertyAccessException) {
      transformerComponentsList.lastIsInstance {
        it.transformer.transformerPhase == FirResolvePhase.BODY_RESOLVE && it != resolveComponents
      }
    }
    withContexts(resolveComponents) {
      println(
        "function " + functionCall.render()
      )
      functionCall.contextReceiverArguments.forEach {
        println(
          "context " + it.render()
        )
      }
      val calleeReference = functionCall.calleeReference
      if (calleeReference is FirErrorNamedReference) {
        val missingContextReceivers = buildList {
          when (val diagnostic = calleeReference.diagnostic) {
            is ConeAmbiguityError, is ConeInapplicableCandidateError -> if (diagnostic is ConeDiagnosticWithCandidates) {
              addAll(diagnostic.candidates.flatMap { candidate ->
                candidate.diagnostics.filterIsInstance<NoApplicableValueForContextReceiver>()
                  .map { noApplicableValueForContextReceiver ->
                    val expectedContextReceiverType = noApplicableValueForContextReceiver.expectedContextReceiverType
                    if (expectedContextReceiverType is ConeTypeVariableType && candidate is Candidate) {
                      expectedContextReceiverType.lookupTag.originalTypeParameter.cast<ConeTypeParameterLookupTag>().typeParameterSymbol.takeIf { it in candidate.symbol.typeParameterSymbols!! }
                        ?.let {
                          candidate.callInfo.typeArguments[candidate.symbol.typeParameterSymbols!!.indexOf(it)].toConeTypeProjection()
                        }
                        ?: expectedContextReceiverType
                    } else expectedContextReceiverType
                  }
              })
            }
          }
        }
        val returnTypeCalculator = createReturnTypeCalculatorForIDE(
          scopeSession,
          ImplicitBodyResolveComputationSession(),
          ::FirDesignatedBodyResolveTransformerForReturnTypeCalculator
        )
        val previousTowerContext = resolveContext.towerDataContext
        var didAddReceiver = false
        for (missingContextReceiver in missingContextReceivers) {
          conceptFunctions.singleOrNull { returnTypeCalculator.tryCalculateReturnType(it).type == missingContextReceiver.type!! }
            ?.let {
              resolveContext.addReceiver(
                null, ContextReceiverValueForCallable(
                  ConceptNamedFunctionSymbol(it, missingContextReceiver.type!!),
                  missingContextReceiver.type!!,
                  null,
                  session,
                  resolveComponents.scopeSession,
                  contextReceiverNumber = -1
                )
              )
              didAddReceiver = true
            }
        }
        if (didAddReceiver) {
          functionCall.replaceCalleeReference(buildSimpleNamedReference {
            source = functionCall.source
            name = when (val diagnostic = calleeReference.diagnostic) {
              is ConeAmbiguityError -> {
                diagnostic.name
              }
              is ConeInapplicableCandidateError -> {
                diagnostic.candidate.callInfo.name
              }
              else -> calleeReference.name
            }
          })
          expressionsTransformer.transformFunctionCall(functionCall, ResolutionMode.ContextDependent)
          resolveContext.replaceTowerDataContext(previousTowerContext)
          functionCall.replaceContextReceiverArguments(functionCall.contextReceiverArguments.map {
            if (it is FirThisReceiverExpression) {
              val conceptSymbol = it.calleeReference.boundSymbol
              if (conceptSymbol is ConceptNamedFunctionSymbol) {
                buildFunctionCall {
                  typeRef = buildResolvedTypeRef { type = conceptSymbol.expectedType }
                  this.calleeReference = buildResolvedNamedReference {
                    name = conceptSymbol.name
                    resolvedSymbol = conceptSymbol
                  }
                }
              } else it
            } else it
          })
        }
      }
      return emptyList()
    }
  }

  val resolveComponents: FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents get() = transformerComponentsList.lastIsInstance { it.transformer.transformerPhase == FirResolvePhase.BODY_RESOLVE }
  context(FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents)
  val resolveContext: BodyResolveContext
    get() = context

  context(FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents) @Suppress(
    "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER"
  )
  val expressionsTransformer
    get() = transformer.expressionsTransformer
}

@OptIn(SymbolInternals::class)
class ConceptNamedFunctionSymbol(
  val underlying: FirNamedFunctionSymbol, val expectedType: ConeKotlinType
) : FirNamedFunctionSymbol(underlying.callableId) {
  init {
    bind(underlying.fir)
  }
}
