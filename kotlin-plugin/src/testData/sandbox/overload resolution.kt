package box

import io.github.kyay10.koncept.Concept

@Concept
fun unit(): Unit {
}

object LibraryMarker

context(LibraryMarker, Int)
fun overload() {

}

context(Unit)
fun overload() {
}

context(LibraryMarker, Int, Unit)
fun overload2() {

}

context(Unit, String)
fun overload2() = this@String

context(OkCreator) class OkFactory {
  context(OkValue)
  fun create() = createOk()
}

object OkToken
class OkCreator {
  context(OkValue, OkToken)
  fun createOk(): String = value
}

@JvmInline
value class OkValue(val value: String)

@Concept
fun ok() = "O"

@Concept
fun okCreator() = OkCreator()

@Concept
fun okToken() = OkToken

@Concept
fun okValue() = OkValue("K")

fun box(): String {
  val firstHalf: String
  with(LibraryMarker) {
    overload()
    firstHalf = overload2()
  }
  return firstHalf + OkFactory().create()
}
