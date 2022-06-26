package io.github.kyay10.koncept.internal

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.visitors.FirDefaultTransformer
import org.jetbrains.kotlin.fir.visitors.FirTransformer


internal object StoreNameReference : FirDefaultTransformer<FirNamedReference>() {
  override fun <E : FirElement> transformElement(element: E, data: FirNamedReference): E {
    return element
  }

  override fun transformNamedReference(
    namedReference: FirNamedReference,
    data: FirNamedReference
  ): FirNamedReference {
    return data
  }

  override fun transformThisReference(thisReference: FirThisReference, data: FirNamedReference): FirReference {
    return data
  }

  override fun transformSuperReference(
    superReference: FirSuperReference,
    data: FirNamedReference
  ): FirReference {
    return data
  }
}

internal object StoreReceiver : FirTransformer<FirExpression>() {
  override fun <E : FirElement> transformElement(element: E, data: FirExpression): E {
    @Suppress("UNCHECKED_CAST")
    return (data as E)
  }
}
