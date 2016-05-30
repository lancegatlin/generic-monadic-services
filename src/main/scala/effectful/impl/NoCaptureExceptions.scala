package effectful.impl

import effectful.Exec
import effectful.aspects.Exceptions

/**
  * An instance of Exceptions for monads that don't capture exceptions
  * in the monad
  */
trait NoCaptureExceptions[E[_]] extends Exceptions[E] {
  implicit val E:Exec[E]

  override def attempt[A](
   _try: =>E[A]
  )(
   _catch: PartialFunction[Throwable, E[A]]
  ): E[A] =
    try { _try } catch _catch

  override def attemptFinally[A,U](
    _try: => E[A]
  )(
    _catch: PartialFunction[Throwable, E[A]]
  )(
    _finally: => E[U]
  ): E[A] =
    try { _try } catch _catch finally _finally

  override def success[A](a: A): E[A] =
    E(a)

  override def failure(t: Throwable): E[Nothing] =
    throw t
}
