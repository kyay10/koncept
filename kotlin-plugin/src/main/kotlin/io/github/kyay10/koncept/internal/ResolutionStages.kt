package io.github.kyay10.koncept.internal

/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import io.github.kyay10.koncept.isNothingFunContextArgument
import io.github.kyay10.koncept.utils.map
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.builder.buildFunctionCall
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.calls.inference.isSubtypeConstraintCompatible
import org.jetbrains.kotlin.resolve.calls.inference.model.SimpleConstraintSystemConstraintPosition
import org.jetbrains.kotlin.types.model.TypeSystemCommonSuperTypesContext
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty

private fun Candidate.prepareReceivers(
  argumentExtensionReceiverValue: ReceiverValue,
  expectedType: ConeKotlinType,
  context: ResolutionContext,
): ReceiverDescription {
  val argumentType = captureFromTypeParameterUpperBoundIfNeeded(
    argumentType = argumentExtensionReceiverValue.type, expectedType = expectedType, session = context.session
  ).let { prepareCapturedType(it, context) }

  return ReceiverDescription(argumentExtensionReceiverValue.receiverExpression, argumentType)
}

/**
 * interface Inv<T>
 * fun <Y> bar(l: Inv<Y>): Y = ...
 *
 * fun <X : Inv<out Int>> foo(x: X) {
 *      val xr = bar(x)
 * }
 * Here we try to capture from upper bound from type parameter.
 * We replace type of `x` to `Inv<out Int>`(we chose supertype which contains supertype with expectedTypeConstructor) and capture from this type.
 * It is correct, because it is like this code:
 * fun <X : Inv<out Int>> foo(x: X) {
 *      val inv: Inv<out Int> = x
 *      val xr = bar(inv)
 * }
 *
 */
internal fun captureFromTypeParameterUpperBoundIfNeeded(
  argumentType: ConeKotlinType, expectedType: ConeKotlinType, session: FirSession
): ConeKotlinType {
  val expectedTypeClassId = expectedType.upperBoundIfFlexible().classId ?: return argumentType
  val simplifiedArgumentType = argumentType.lowerBoundIfFlexible() as? ConeTypeParameterType ?: return argumentType
  val typeParameterSymbol = simplifiedArgumentType.lookupTag.typeParameterSymbol

  val context = session.typeContext

  val chosenSupertype = typeParameterSymbol.resolvedBounds.map { it.coneType }
    .singleOrNull { it.hasSupertypeWithGivenClassId(expectedTypeClassId, context) } ?: return argumentType

  val capturedType = context.captureFromExpression(chosenSupertype) as ConeKotlinType? ?: return argumentType
  return if (argumentType is ConeDefinitelyNotNullType) {
    ConeDefinitelyNotNullType.create(capturedType, session.typeContext) ?: capturedType
  } else {
    capturedType
  }
}

private fun ConeKotlinType.hasSupertypeWithGivenClassId(
  classId: ClassId, context: TypeSystemCommonSuperTypesContext
): Boolean {
  return with(context) {
    anySuperTypeConstructor {
      val typeConstructor = it.typeConstructor()
      typeConstructor is ConeClassLikeLookupTag && typeConstructor.classId == classId
    }
  }
}


private class ReceiverDescription(
  val expression: FirExpression,
  val type: ConeKotlinType,
)

class KonceptCheckContextReceivers(val conceptFunctions: List<FirNamedFunctionSymbol>) : ResolutionStage() {
  override suspend fun check(candidate: Candidate, callInfo: CallInfo, sink: CheckerSink, context: ResolutionContext) {
    val contextReceiverExpectedTypes = (candidate.symbol as? FirCallableSymbol<*>)?.resolvedContextReceivers?.map {
      candidate.substitutor.substituteOrSelf(it.typeRef.coneType)
    }?.takeUnless { it.isEmpty() } ?: return

    val receiverGroups: List<List<ImplicitReceiverValue<*>>> =
      context.bodyResolveComponents.towerDataElements.asReversed().mapNotNull { towerDataElement ->
        towerDataElement.implicitReceiver?.takeUnless { it.receiverExpression.isNothingFunContextArgument }
          ?.let(::listOf) ?: towerDataElement.contextReceiverGroup
      }

    val resultingContextReceiverArguments = mutableListOf<FirExpression>()
    for (expectedType in contextReceiverExpectedTypes) {
      val matchingReceivers =
        candidate.findClosestMatchingReceivers(expectedType, receiverGroups, context, conceptFunctions)
      when (matchingReceivers.size) {
        0 -> {
          sink.reportDiagnostic(NoApplicableValueForContextReceiver(expectedType))
          return
        }

        1 -> {
          val matchingReceiver = matchingReceivers.single()
          resultingContextReceiverArguments.add(matchingReceiver.expression)
          candidate.system.addSubtypeConstraint(
            matchingReceiver.type, expectedType, SimpleConstraintSystemConstraintPosition
          )
        }

        else -> {
          sink.reportDiagnostic(AmbiguousValuesForContextReceiverParameter(expectedType))
          return
        }
      }
    }

    candidate.contextReceiverArguments = resultingContextReceiverArguments
  }
}

@OptIn(SymbolInternals::class)
private fun Candidate.findClosestMatchingReceivers(
  expectedType: ConeKotlinType,
  receiverGroups: List<List<ImplicitReceiverValue<*>>>,
  context: ResolutionContext,
  conceptFunctions: List<FirNamedFunctionSymbol>,
): List<ReceiverDescription> = with(context) {
  for (receiverGroup in receiverGroups) {
    val currentResult = receiverGroup.map { prepareReceivers(it, expectedType, context) }.filter {
      system.isSubtypeConstraintCompatible(
        it.type, expectedType, SimpleConstraintSystemConstraintPosition
      )
    }

    if (currentResult.isNotEmpty()) return currentResult
  }
  /*val typeParams = symbol.typeParameterSymbols!!
  *//*val typeParamsToTypeArguments =
    (0..typeParams.lastIndex).associateBy(typeParams::get) { callInfo.typeArguments[it].toConeTypeProjection().type!! }
 *//* val substitutor = substitutor.chain(ErrorToStarProjectionSubstitutor(session.typeContext))
  val expectedType = substitutor.substituteOrSelf(expectedType.removeTypeVariableTypes(session.typeContext))*/


  conceptFunctions.map {
    prepareReceivers(it.asReceiverValue(), expectedType, context)
  }.filter {
    val conceptFunType = it.expression.cast<FirResolvable>().candidate()?.let {candidate ->
      /*
       * It's important to extract type from argument neither from symbol, because of symbol contains
       *   placeholder type with value 0, but argument contains type with proper literal value
       */
      val type: ConeKotlinType =
        context.returnTypeCalculator.tryCalculateReturnType(candidate.symbol.fir as FirCallableDeclaration).type
      candidate.substitutor.substituteOrSelf(type)
    } ?: it.type
    system.isSubtypeConstraintCompatible(
      conceptFunType, expectedType, SimpleConstraintSystemConstraintPosition
    )
  }.ifNotEmpty { return this }
  return emptyList()
}

internal fun FirResolvable.candidate(): Candidate? {
  return when (val callee = this.calleeReference) {
    is FirNamedReferenceWithCandidate -> return callee.candidate
    else -> null
  }
}


class ConceptFunctionAsReceiverValue(
  override val receiverExpression: FirExpression, override val type: ConeKotlinType
) : ReceiverValue

context(ResolutionContext)
fun FirNamedFunctionSymbol.asReceiverValue(): ConceptFunctionAsReceiverValue {
  val type = returnTypeCalculator.tryCalculateReturnType(this).type
  return ConceptFunctionAsReceiverValue(buildFunctionCall {
    typeRef = buildResolvedTypeRef { this.type = type }
    this.calleeReference = buildResolvedNamedReference {
      name = this@asReceiverValue.name
      resolvedSymbol = this@asReceiverValue
    }
  }, type)
}
