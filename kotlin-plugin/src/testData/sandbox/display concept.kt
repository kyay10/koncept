@file:Suppress("SUBTYPING_BETWEEN_CONTEXT_RECEIVERS")

package sandbox

import io.github.kyay10.koncept.Concept


interface Display<in T, in C1, in C2, in C3, in C4, in C5, in C6, in C7> {
  context(C1, C2, C3, C4, C5, C6, C7)
  fun display(t: T): String
}


interface SingleDisplay<in T> : Display<T, Unit, Unit, Unit, Unit, Unit, Unit, Unit> {
  context(Unit, Unit, Unit, Unit, Unit, Unit, Unit)
    override fun display(t: T): String = display(t, unit = Unit)

  @Suppress("VIRTUAL_MEMBER_HIDDEN")
  fun display(t: T, unit: Unit): String
}

object IntDisplay : SingleDisplay<Int> {
  override fun display(t: Int, unit: Unit): String {
    return "Display Int $t"
  }
}

object StringDisplay : SingleDisplay<String> {
  override fun display(t: String, unit: Unit): String {
    return "Display String $t"
  }
}

context(Display<X, C1, C2, C3, C4, C5, C6, C7>, C1, C2, C3, C4, C5, C6, C7)
fun <X, C1, C2, C3, C4, C5, C6, C7> X.display() =
  display(this)

context(C1, C2, C3, C4, C5, C6, C7)
fun <X, C1, C2, C3, C4, C5, C6, C7> X.displayWithParam(display: Display<X, C1, C2, C3, C4, C5, C6, C7>) =
  display.display(this)

@Concept
fun intDisplay(): SingleDisplay<Int> = IntDisplay

@Concept
fun stringDisplay(): SingleDisplay<String> = StringDisplay

@Concept
fun unit() {
}

sealed interface ListDisplay<T> : Display<List<T>, SingleDisplay<T>, Unit, Unit, Unit, Unit, Unit, Unit> {
  context(SingleDisplay<T>, Unit, Unit, Unit, Unit, Unit, Unit)
    override fun display(t: List<T>): String = with(Unit) {
    return t.joinToString(prefix = "Display List [", separator = ", ", postfix = "]") { display(it, unit = Unit) }
  }

  companion object : ListDisplay<Any?>
}

@Concept
fun <E> listDisplay() = ListDisplay as ListDisplay<E>

fun box(): String {
  listOf("hello", "world").displayWithParam(listDisplay())
  listOf("hello", "world").display()
  return "OK".display<String, _, _, _, _, _, _, _>().drop(15.display().drop(12).toInt())
}
