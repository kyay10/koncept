package box

import io.github.kyay10.koncept.Concept

@Concept
fun provideOk() = "OK2" // Change to OK for test to pass

context(A) fun <A> given() = this@A

fun box(): String {
  return given()
}
