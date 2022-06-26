package io.github.kyay10.koncept

import io.github.kyay10.koncept.utils.map
import io.github.kyay10.koncept.utils.safeAs
import io.github.kyay10.koncept.utils.withContexts
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.PrivateForInline
import org.jetbrains.kotlin.fir.analysis.checkers.typeParameterSymbols
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccess
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.extensions.predicate.annotated
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.references.builder.buildSimpleNamedReference
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.calls.Candidate
import org.jetbrains.kotlin.fir.resolve.calls.ContextReceiverValueForCallable
import org.jetbrains.kotlin.fir.resolve.calls.NoApplicableValueForContextReceiver
import org.jetbrains.kotlin.fir.resolve.calls.removeTypeVariableTypes
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeAmbiguityError
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeDiagnosticWithCandidates
import org.jetbrains.kotlin.fir.resolve.diagnostics.ConeInapplicableCandidateError
import org.jetbrains.kotlin.fir.resolve.substitution.AbstractConeSubstitutor
import org.jetbrains.kotlin.fir.resolve.substitution.chain
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirAbstractBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirImplicitAwareBodyResolveTransformer
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildPlaceholderProjection
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.TypeApproximatorConfiguration
import org.jetbrains.kotlin.utils.addToStdlib.cast

class KonceptExpressionResolutionExtension(
  firSession: FirSession,
  private val messageCollector: MessageCollector,
  private val transformerComponentsList: MutableList<BodyResolveComponents>
) : FirExpressionResolutionExtension(firSession) {
  companion object {
    val CONCEPT_ANNOTATION_FQNAME = FqName(BuildConfig.CONCEPT_FQNAME)
    val CONCEPT_ANNOTATION_CLASS_ID = ClassId.topLevel(CONCEPT_ANNOTATION_FQNAME)
    val HAS_CONCEPT_ANNOTATION = annotated(CONCEPT_ANNOTATION_FQNAME)
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
      regularResolveComponents.also { it.context.file }
    } catch (e: UninitializedPropertyAccessException) {
      implicitResolveComponents
    }
    withContexts(resolveComponents) {
      val calleeReference = functionCall.calleeReference
      if (calleeReference is FirErrorNamedReference) {
        val missingContextReceivers = buildList {
          when (val diagnostic = calleeReference.diagnostic) {
            is ConeAmbiguityError, is ConeInapplicableCandidateError -> if (diagnostic is ConeDiagnosticWithCandidates) {
              diagnostic.candidates.flatMapTo(this) { candidate ->
                if (candidate is Candidate) {
                  val typeParams = candidate.symbol.typeParameterSymbols!!
                  val typeParamsToTypeArguments =
                    (0..typeParams.lastIndex).associateBy(typeParams::get) { candidate.callInfo.typeArguments[it].toConeTypeProjection().type!! }
                  val substitutor = substitutorByMap(
                    typeParamsToTypeArguments, session
                  ).chain(ErrorToStarProjectionSubstitutor(session.typeContext))
                  candidate.diagnostics.filterIsInstance<NoApplicableValueForContextReceiver>()
                    .map { noApplicableValueForContextReceiver ->
                      val expectedContextReceiverType = noApplicableValueForContextReceiver.expectedContextReceiverType
                      substitutor.substituteOrSelf(expectedContextReceiverType.removeTypeVariableTypes(session.typeContext))
                    }
                } else candidate.diagnostics.filterIsInstance<NoApplicableValueForContextReceiver>()
                  .map { it.expectedContextReceiverType }
              }
            }
          }
        }

        context.withTowerDataCleanup {
          var didAddReceiver = false
          for (missingContextReceiver in missingContextReceivers) {
            conceptFunctions.singleOrNull {
              returnTypeCalculator.tryCalculateReturnType(it).type.isSubtypeOf(
                missingContextReceiver, session
              )
            }?.let {
              context.addReceiver(
                null, ContextReceiverValueForCallable(
                  ConceptNamedFunctionSymbol(it, missingContextReceiver),
                  returnTypeCalculator.tryCalculateReturnType(it).type,
                  null,
                  session,
                  scopeSession,
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
            // Re-infer any type arguments that gave errors before because they might be resolvable now
            functionCall.replaceTypeArguments(functionCall.typeArguments.map {
              it.toConeTypeProjection().type?.safeAs<ConeErrorType>()?.let {
                buildPlaceholderProjection { }
              } ?: it
            })
            transformer.transformFunctionCall(functionCall, ResolutionMode.ContextDependent)
            functionCall.replaceContextReceiverArguments(functionCall.contextReceiverArguments.map { argument ->
              argument.safeAs<FirThisReceiverExpression>()?.let {
                it.calleeReference.boundSymbol.safeAs<ConceptNamedFunctionSymbol>()?.let { conceptSymbol ->
                  buildFunctionCall {
                    typeRef = buildResolvedTypeRef { type = conceptSymbol.expectedType }
                    this.calleeReference = buildResolvedNamedReference {
                      name = conceptSymbol.name
                      resolvedSymbol = conceptSymbol
                    }
                  }
                }
              } ?: argument
            })
          }
        }
      }
      return emptyList()
    }
  }

  val regularResolveComponents: FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents
    get() = transformerComponentsList.single {
      it is FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents && it.transformer::class == FirBodyResolveTransformer::class
    }.cast()
  val implicitResolveComponents: FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents
    get() = transformerComponentsList.single {
      it is FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents && it.transformer::class == FirImplicitAwareBodyResolveTransformer::class
    }.cast()

  /*context(FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents) @Suppress(
    "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER"
  )
  val expressionsTransformer
    get() = transformer.expressionsTransformer*/
  internal fun <T> FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents.storeTypeFromCallee(
    access: T,
    typeFromCallee: FirResolvedTypeRef
  ) where T : FirQualifiedAccess, T : FirExpression {
    access.replaceTypeRef(
      typeFromCallee.withReplacedConeType(
        session.typeApproximator.approximateToSuperType(
          typeFromCallee.type, TypeApproximatorConfiguration.FinalApproximationAfterResolutionAndInference
        )
      )
    )
  }
}

class ErrorToStarProjectionSubstitutor(typeContext: ConeTypeContext) : AbstractConeSubstitutor(typeContext) {
  override fun substituteType(type: ConeKotlinType): ConeKotlinType? {
    return null
  }

  override fun substituteArgument(
    projection: ConeTypeProjection, lookupTag: ConeClassLikeLookupTag, index: Int
  ): ConeTypeProjection? {
    return projection.type?.safeAs<ConeErrorType>()?.let { ConeStarProjection }
  }
}


@OptIn(SymbolInternals::class)
class ConceptNamedFunctionSymbol(
  val underlying: FirNamedFunctionSymbol, val expectedType: ConeKotlinType
) : FirNamedFunctionSymbol(underlying.callableId) {
  init {
    bind(underlying.fir)
  }
}
