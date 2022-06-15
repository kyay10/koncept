/*
 * Copyright (C) 2020 Brian Norman
 * Copyright (C) 2021 Youssef Shoaib
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("MemberVisibilityCanBePrivate", "ReplaceNotNullAssertionWithElvisReturn")

package io.github.kyay10.koncept

import io.github.kyay10.koncept.utils.*
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrReturnableBlockSymbol
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.utils.addToStdlib.cast

class KonceptIrGenerationExtension(
  private val project: Project,
  private val messageCollector: MessageCollector,
  private val compilerConfig: CompilerConfiguration,
  private val renamesMap: MutableMap<String, String>
) : IrGenerationExtension {

  var shadowsContext: IrPluginContext? = null

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {}
}

tailrec fun IrExpression.extractFromReturnIfNeeded(): IrExpression =
  if (this is IrReturn && this.returnTargetSymbol is IrReturnableBlockSymbol) value.lastElement().cast<IrExpression>()
    .extractFromReturnIfNeeded() else this

fun IrExpression.calculatePredeterminedEqualityIfPossible(context: IrPluginContext): Boolean? {
  val trueElement = lastElement().cast<IrExpression>().extractFromReturnIfNeeded()
  if (trueElement is IrConst<*> && trueElement.kind == IrConstKind.Boolean) return trueElement.cast<IrConst<Boolean>>().value
  if (trueElement is IrCall && (trueElement.symbol == context.irBuiltIns.eqeqSymbol || trueElement.symbol == context.irBuiltIns.eqeqeqSymbol)) {
    val lhs = trueElement.getValueArgument(0)?.extractCompileTimeConstantFromIrGetIfPossible()
    val rhs = trueElement.getValueArgument(1)?.extractCompileTimeConstantFromIrGetIfPossible()
    if (lhs is IrGetEnumValue && rhs is IrGetEnumValue) return lhs.symbol == rhs.symbol
    if (lhs is IrConst<*> && rhs is IrConst<*>) return lhs.value == rhs.value
  }
  return null
}

tailrec fun IrExpression.extractCompileTimeConstantFromIrGetIfPossible(): IrExpression {
  val compileTimeConstant = safeAs<IrGetValue>()?.symbol?.owner?.safeAs<IrVariable>()
    ?.takeIf { !it.isVar && it.origin == IrDeclarationOrigin.IR_TEMPORARY_VARIABLE }?.initializer?.lastElement()
    ?.safeAs<IrExpression>()?.extractFromReturnIfNeeded() ?: return this
  return compileTimeConstant.extractCompileTimeConstantFromIrGetIfPossible()
}

class CollapseContainerExpressionsReturningFunctionExpressionsTransformer(pluginContext: IrPluginContext) :
  IrFileTransformerVoidWithContext(pluginContext) {
  val functionAccesses = mutableListOf<IrExpression>()

  override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
    val result = expression.run {
      if (expression in functionAccesses) return@run expression
      val params = buildList(valueArgumentsCount + 2) {
        add(dispatchReceiver)
        add(extensionReceiver)
        for (i in 0 until valueArgumentsCount) add(getValueArgument(i))
      }
      return@run declarationIrBuilder.irBlock {
        var lambdaBlockArgumentFound = false
        params.forEachIndexed { index, value ->
          if (value != null && value.type.isFunctionTypeOrSubtype && value !is IrFunctionExpression) {
            lambdaBlockArgumentFound = true
            val accumulatedStatements = accumulateStatements(value)
            val newArgumentValue = accumulatedStatements.removeLast() as IrExpression
            accumulatedStatements.forEach { +it }
            expression.changeArgument(index, newArgumentValue)
          }
        }
        if (!lambdaBlockArgumentFound) return@run expression
        +expression
        functionAccesses.add(expression)
      }
      return@run run {
        val lambdaParamsWithBlockArgument = params.withIndex().filterNotNullIndices()
          .filter { it.value.type.isFunctionTypeOrSubtype() && it.value !is IrFunctionExpression }
        if (lambdaParamsWithBlockArgument.isNotEmpty()) {
          declarationIrBuilder.irBlock {
            lambdaParamsWithBlockArgument.forEach { (index, value) ->
              val accumulatedStatements = accumulateStatements(value)
              val newArgumentValue = accumulatedStatements.removeLast() as IrExpression
              accumulatedStatements.forEach { +it }
              expression.changeArgument(index, newArgumentValue)
            }
            +expression
            functionAccesses.add(expression)
          }
        } else expression
      }
      val lambdaParamsWithBlockArgument = params.withIndex().filterNotNullIndices()
        .filter { it.value.type.isFunctionTypeOrSubtype() && it.value !is IrFunctionExpression }
      if (lambdaParamsWithBlockArgument.isNotEmpty()) {
        declarationIrBuilder.irBlock {
          for ((index, value) in lambdaParamsWithBlockArgument) {
            val accumulatedStatements = accumulateStatements(value)
            val newArgumentValue = accumulatedStatements.removeLast() as IrExpression
            accumulatedStatements.forEach { +it }
            expression.changeArgument(index, newArgumentValue)
          }
          +expression
          functionAccesses.add(expression)
        }
      } else expression


    }
    return super.visitExpression(result)
  }
}
