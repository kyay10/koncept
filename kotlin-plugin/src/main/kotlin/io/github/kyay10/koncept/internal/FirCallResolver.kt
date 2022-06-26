/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
@file:OptIn(SymbolInternals::class)

package io.github.kyay10.koncept.internal


import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.hasExplicitBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isReferredViaField
import org.jetbrains.kotlin.fir.diagnostics.ConeDiagnostic
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.*
import org.jetbrains.kotlin.fir.references.builder.buildBackingFieldReference
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.calls.tower.FirTowerResolver
import org.jetbrains.kotlin.fir.resolve.diagnostics.*
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirAbstractBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirExpressionsResolveTransformer
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.tower.CandidateApplicability
import org.jetbrains.kotlin.resolve.calls.tower.isSuccess
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class FirCallResolver(
  private val components: FirAbstractBodyResolveTransformer.BodyResolveTransformerComponents,
) {
  private val session = components.session
  private val overloadByLambdaReturnTypeResolver = FirOverloadByLambdaReturnTypeResolver(components)

  private lateinit var transformer: FirExpressionsResolveTransformer

  fun initTransformer(transformer: FirExpressionsResolveTransformer) {
    this.transformer = transformer
  }

  private val towerResolver = FirTowerResolver(
    components, components.resolutionStageRunner,
  )

  val conflictResolver: ConeCallConflictResolver = components.callResolver.conflictResolver

  @PrivateForInline
  var needTransformArguments: Boolean = true

  @OptIn(PrivateForInline::class)
  fun resolveCallAndSelectCandidate(functionCall: FirFunctionCall): FirFunctionCall {
    @Suppress("NAME_SHADOWING") val functionCall = if (needTransformArguments) {
      functionCall.transformExplicitReceiver().also {
        components.dataFlowAnalyzer.enterQualifiedAccessExpression()
        functionCall.argumentList.transformArguments(transformer, ResolutionMode.ContextDependent)
      }
    } else {
      functionCall
    }

    val name = functionCall.calleeReference.name
    val result = collectCandidates(functionCall, name, origin = functionCall.origin)

    var forceCandidates: Collection<Candidate>? = null
    if (result.candidates.isEmpty()) {
      val newResult = collectCandidates(functionCall, name, CallKind.VariableAccess, origin = functionCall.origin)
      if (newResult.candidates.isNotEmpty()) {
        forceCandidates = newResult.candidates
      }
    }

    val nameReference = createResolvedNamedReference(
      functionCall.calleeReference,
      name,
      result.info,
      result.candidates,
      result.applicability,
      functionCall.explicitReceiver,
      expectedCallKind = if (forceCandidates != null) CallKind.VariableAccess else null,
      expectedCandidates = forceCandidates
    )

    val resultExpression = functionCall.transformCalleeReference(StoreNameReference, nameReference)
    val candidate = (nameReference as? FirNamedReferenceWithCandidate)?.candidate
    val resolvedReceiver = functionCall.explicitReceiver
    if (candidate != null && resolvedReceiver is FirResolvedQualifier) {
      resolvedReceiver.replaceResolvedToCompanionObject(candidate.isFromCompanionObjectTypeScope)
    }

    // We need desugaring
    val resultFunctionCall = if (candidate != null && candidate.callInfo != result.info) {
      functionCall.copyAsImplicitInvokeCall {
        explicitReceiver = candidate.callInfo.explicitReceiver
        dispatchReceiver = candidate.dispatchReceiverExpression()
        extensionReceiver = candidate.chosenExtensionReceiverExpression()
        argumentList = candidate.callInfo.argumentList
        contextReceiverArguments.addAll(candidate.contextReceiverArguments())
      }
    } else {
      resultExpression
    }
    val typeRef = components.typeFromCallee(resultFunctionCall)
    if (typeRef.type is ConeErrorType) {
      resultFunctionCall.replaceTypeRef(typeRef)
    }

    return resultFunctionCall
  }

  private inline fun <reified Q : FirQualifiedAccess> Q.transformExplicitReceiver(): Q {
    val explicitReceiver = explicitReceiver as? FirQualifiedAccessExpression ?: return transformExplicitReceiver(
      transformer,
      ResolutionMode.ReceiverResolution
    ) as Q

    (explicitReceiver.calleeReference as? FirSuperReference)?.let {
      transformer.transformSuperReceiver(it, explicitReceiver, this)
      return this
    }

    if (explicitReceiver is FirPropertyAccessExpression) {
      this.replaceExplicitReceiver(
        transformer.transformQualifiedAccessExpression(
          explicitReceiver, ResolutionMode.ReceiverResolution, isUsedAsReceiver = true
        ) as FirExpression
      )
      return this
    }

    return transformExplicitReceiver(transformer, ResolutionMode.ReceiverResolution) as Q
  }

  private data class ResolutionResult(
    val info: CallInfo, val applicability: CandidateApplicability, val candidates: Collection<Candidate>,
  )

  private fun <T : FirQualifiedAccess> collectCandidates(
    qualifiedAccess: T,
    name: Name,
    forceCallKind: CallKind? = null,
    origin: FirFunctionCallOrigin = FirFunctionCallOrigin.Regular,
    containingDeclarations: List<FirDeclaration> = transformer.components.containingDeclarations,
    resolutionContext: ResolutionContext = transformer.resolutionContext,
    collector: CandidateCollector? = null
  ): ResolutionResult {
    val explicitReceiver = qualifiedAccess.explicitReceiver
    val argumentList = (qualifiedAccess as? FirFunctionCall)?.argumentList ?: FirEmptyArgumentList
    val typeArguments = (qualifiedAccess as? FirFunctionCall)?.typeArguments.orEmpty()

    val info = CallInfo(
      qualifiedAccess,
      forceCallKind ?: if (qualifiedAccess is FirFunctionCall) CallKind.Function else CallKind.VariableAccess,
      name,
      explicitReceiver,
      argumentList,
      isImplicitInvoke = qualifiedAccess is FirImplicitInvokeCall,
      typeArguments,
      session,
      components.file,
      containingDeclarations,
      origin = origin
    )
    towerResolver.reset()
    val result = towerResolver.runResolver(info, resolutionContext, collector)
    val bestCandidates = result.bestCandidates()

    fun chooseMostSpecific(): Set<Candidate> {
      val onSuperReference = (explicitReceiver as? FirQualifiedAccessExpression)?.calleeReference is FirSuperReference
      return conflictResolver.chooseMaximallySpecificCandidates(
        bestCandidates, discriminateGenerics = true, discriminateAbstracts = onSuperReference
      )
    }

    var reducedCandidates = if (!result.currentApplicability.isSuccess) {
      val distinctApplicabilities = bestCandidates.mapTo(mutableSetOf()) { it.currentApplicability }
      //if all candidates have the same kind on inApplicability - try to choose the most specific one
      if (distinctApplicabilities.size == 1 && distinctApplicabilities.single() > CandidateApplicability.INAPPLICABLE) {
        chooseMostSpecific()
      } else {
        bestCandidates.toSet()
      }
    } else {
      chooseMostSpecific()
    }

    reducedCandidates =
      overloadByLambdaReturnTypeResolver.reduceCandidates(qualifiedAccess, bestCandidates, reducedCandidates)

    return ResolutionResult(info, result.currentApplicability, reducedCandidates)
  }


  private fun createResolvedNamedReference(
    reference: FirReference,
    name: Name,
    callInfo: CallInfo,
    candidates: Collection<Candidate>,
    applicability: CandidateApplicability,
    explicitReceiver: FirExpression? = null,
    createResolvedReferenceWithoutCandidateForLocalVariables: Boolean = true,
    expectedCallKind: CallKind? = null,
    expectedCandidates: Collection<Candidate>? = null
  ): FirNamedReference {
    val source = reference.source
    return when {
      expectedCallKind != null -> {
        fun isValueParametersNotEmpty(candidate: Candidate): Boolean {
          return (candidate.symbol.fir as? FirFunction)?.valueParameters?.size?.let { it > 0 } ?: false
        }

        val candidate = candidates.singleOrNull()

        val diagnostic = if (expectedCallKind == CallKind.Function) {
          ConeFunctionCallExpectedError(name, candidates.any { isValueParametersNotEmpty(it) }, candidates)
        } else {
          val singleExpectedCandidate = expectedCandidates?.singleOrNull()

          var fir = singleExpectedCandidate?.symbol?.fir
          if (fir is FirTypeAlias) {
            fir =
              (fir.expandedTypeRef.coneType.fullyExpandedType(session).toSymbol(session) as? FirRegularClassSymbol)?.fir
          }

          if (fir is FirRegularClass) {
            ConeResolutionToClassifierError(singleExpectedCandidate!!, fir.symbol)
          } else {
            val coneType = explicitReceiver?.typeRef?.coneType
            when {
              coneType != null && !coneType.isUnit -> {
                ConeFunctionExpectedError(
                  name.asString(), (fir as? FirCallableDeclaration)?.returnTypeRef?.coneType ?: coneType
                )
              }
              singleExpectedCandidate != null && !singleExpectedCandidate.currentApplicability.isSuccess -> {
                createConeDiagnosticForCandidateWithError(
                  singleExpectedCandidate.currentApplicability, singleExpectedCandidate
                )
              }
              else -> ConeUnresolvedNameError(name)
            }
          }
        }

        if (candidate != null) {
          createErrorReferenceWithExistingCandidate(
            candidate, diagnostic, source, transformer.resolutionContext, components.resolutionStageRunner
          )
        } else {
          buildErrorReference(callInfo, diagnostic, source)
        }
      }

      candidates.isEmpty() -> {
        val diagnostic = if (name.asString() == "invoke" && explicitReceiver is FirConstExpression<*>) {
          ConeFunctionExpectedError(explicitReceiver.value?.toString() ?: "", explicitReceiver.typeRef.coneType)
        } else {
          ConeUnresolvedNameError(name)
        }

        buildErrorReference(
          callInfo, diagnostic, source
        )
      }

      candidates.size > 1 -> buildErrorReference(
        callInfo, ConeAmbiguityError(name, applicability, candidates), source
      )

      !applicability.isSuccess -> {
        val candidate = candidates.single()
        val diagnostic = createConeDiagnosticForCandidateWithError(applicability, candidate)
        createErrorReferenceWithExistingCandidate(
          candidate, diagnostic, source, transformer.resolutionContext, components.resolutionStageRunner
        )
      }

      else -> {
        val candidate = candidates.single()
        val coneSymbol = candidate.symbol
        if (coneSymbol is FirBackingFieldSymbol) {
          coneSymbol.fir.propertySymbol.fir.isReferredViaField = true
          return buildBackingFieldReference {
            this.source = source
            resolvedSymbol = coneSymbol
          }
        }
        if (coneSymbol.safeAs<FirPropertySymbol>()?.hasExplicitBackingField == true) {
          return FirPropertyWithExplicitBackingFieldResolvedNamedReference(
            source, name, candidate.symbol, candidate.hasVisibleBackingField
          )
        }
        /*
         * This `if` is an optimization for local variables and properties without type parameters
         * Since they have no type variables, so we can don't run completion on them at all and create
         *   resolved reference immediately
         *
         * But for callable reference resolution we should keep candidate, because it was resolved
         *   with special resolution stages, which saved in candidate additional reference info,
         *   like `resultingTypeForCallableReference`
         */
        if (createResolvedReferenceWithoutCandidateForLocalVariables && explicitReceiver?.typeRef?.coneTypeSafe<ConeIntegerLiteralType>() == null && coneSymbol is FirVariableSymbol && (coneSymbol !is FirPropertySymbol || (coneSymbol.fir as FirMemberDeclaration).typeParameters.isEmpty())) {
          return buildResolvedNamedReference {
            this.source = source
            this.name = name
            resolvedSymbol = coneSymbol
          }
        }
        FirNamedReferenceWithCandidate(source, name, candidate)
      }
    }
  }

  private fun buildErrorReference(
    callInfo: CallInfo, diagnostic: ConeDiagnostic, source: KtSourceElement?
  ): FirErrorReferenceWithCandidate {
    return createErrorReferenceWithErrorCandidate(
      callInfo, diagnostic, source, transformer.resolutionContext, components.resolutionStageRunner
    )
  }
}
