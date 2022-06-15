package io.github.kyay10.koncept.utils
/*

typealias LazySequence<T> = (((T) -> Boolean)?) -> LazyIterator<T>?
typealias LazyIterator<T> = (Boolean) -> Output2<Boolean, T>
typealias LazyIndexedValue<T> = (Boolean) -> Output2<Int, T>

inline fun <T> LazySequence(
  crossinline forEach: ((T) -> Boolean) -> Unit,
  crossinline iterator: () -> LazyIterator<T>
): LazySequence<T> {
  return {
    if (it == null) {
      iterator()
    } else {
      forEach(it)
      null
    }
  }
}

inline fun <T> LazyIterator(
  crossinline hasNext: () -> Boolean,
  crossinline next: () -> T
): LazyIterator<T> {
  return {
    Output2(
      if (it) {
        next()
      } else {
        hasNext()
      }
    )
  }
}

inline fun <T> LazyIndexedValue(
  index: Int,
  value: T
): LazyIndexedValue<T> {
  return {
    Output2(
      if (it) {
        value
      } else {
        index
      }
    )
  }
}

inline fun <A, B> ((Boolean) -> Output2<A, B>).first(): A = this(false).value as A
inline fun <A, B> ((Boolean) -> Output2<A, B>).second(): B = this(true).value as B

@JvmInline
value class Output2<out A, out B> @PublishedApi internal constructor(val value: Any?)

inline operator fun <T> LazyIterator<T>.hasNext(): Boolean = first()
inline operator fun <T> LazyIterator<T>.next(): T = second()
inline operator fun <T> LazySequence<T>.iterator() = this(null)!!

inline val <T> LazyIndexedValue<T>.index get() = first()
inline val <T> LazyIndexedValue<T>.value get() = second()

inline operator fun <T> LazyIndexedValue<T>.component1(): Int = first()
inline operator fun <T> LazyIndexedValue<T>.component2(): T = second()

inline fun <T> LazySequence<T>.forEach(noinline action: (T) -> Unit) {
  forEachBreakable {
    action(it)
    false
  }
}

@JvmName("forEachBreakable")
inline fun <T> LazySequence<T>.forEachBreakable(noinline action: (T) -> Boolean) {
  this(action)
}

inline fun <T> LazySequence<T>.forEachIndexed(noinline action: (Int, T) -> Unit) {
  var i = 0
  forEach {
    action(i, it)
    i++
  }
}

inline fun <T> LazySequence<T>.forEachIndexedBreakable(noinline action: (Int, T) -> Boolean) {
  var i = 0
  forEachBreakable {
    action(i, it).also { i++ }
  }
}

inline fun <T> LazySequence<T>.filter(crossinline predicate: (T) -> Boolean): LazySequence<T> {
  return LazySequence({ consumer ->
    forEachBreakable {
      if (predicate(it))
        consumer(it)
      else false
    }
  }, {
    val iterator = iterator()
    var nextState: Int = -1 // -1 for unknown, 0 for done, 1 for continue
    var nextItem: T? = null
    val calcNext = calcNext@{
      while (iterator.hasNext()) {
        val item = iterator.next()
        if (predicate(item)) {
          nextItem = item
          nextState = 1
          return@calcNext
        }
      }
      nextState = 0
    }
    LazyIterator({
      if (nextState == -1)
        calcNext()
      nextState == 1
    }, {
      if (nextState == -1)
        calcNext()
      if (nextState == 0)
        throw NoSuchElementException()
      val result = nextItem
      nextItem = null
      nextState = -1
      result as T
    })
  })
}


inline fun <T> LazySequence<T>.withIndex(): LazySequence<LazyIndexedValue<T>> =
  LazySequence({ consumer ->
    forEachIndexedBreakable { index, element ->
      consumer(LazyIndexedValue(index, element))
    }
  }, {
    val iterator = iterator()
    var i = 0
    LazyIterator({ iterator.hasNext() }, {
      LazyIndexedValue(i, iterator.next()).also {
        i++
      }
    })
  })


inline fun <T : Any> LazySequence<LazyIndexedValue<T?>>.filterNotNullIndices(): LazySequence<LazyIndexedValue<T>> {
  return filter { it.value != null } as LazySequence<LazyIndexedValue<T>>
}

inline fun <T> LazySequence<T>.isNotEmpty(): Boolean = !isEmpty()
inline fun <T> LazySequence<T>.isEmpty(): Boolean {
  var result = false
  forEachBreakable {
    result = true
    true
  }
  return result
}

inline fun <T> Iterable<T>.asLazySequence(): LazySequence<T> = LazySequence(forEachBreakable@{ consumer ->
  forEach { element ->
    if (consumer(element)) return@forEachBreakable
  }
}, {
  iterator().asLazyIterator()
})

inline fun <T> List<T>.asLazySequence(): LazySequence<T> = LazySequence(forEachBreakable@{ consumer ->
  forEach { element ->
    if (consumer(element)) return@forEachBreakable
  }
}, {
  iterator().asLazyIterator()
})

inline fun <T> Iterator<T>.asLazyIterator(): LazyIterator<T> = LazyIterator({ hasNext() }, { next() })
*/
