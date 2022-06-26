package box

import io.github.kyay10.koncept.Concept

@Concept
fun provideOk() = "OK"

context(A) fun <A> given() = this@A

fun box(): String {
  return given<CharSequence>().toString()
}
