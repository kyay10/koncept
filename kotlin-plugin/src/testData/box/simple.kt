package foo.bar

import io.github.kyay10.koncept.Concept

class A
class B(val message: String)

context(A, B) fun myFun(): String {
  return message
}

@Concept
fun gimmeB() = B("OK")
fun box(): String {
  with(A()) {
    return myFun()
  }
}
