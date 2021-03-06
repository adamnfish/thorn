package com.adamnfish.thorn.attempt

import scala.concurrent.{ExecutionContext, Future}


/**
 * Represents a value that will need to be calculated using an asynchronous
 * computation that may fail.
 */
case class Attempt[+A] private (underlying: Future[Either[FailedAttempt, A]]) {
  def map[B](f: A => B)(implicit ec: ExecutionContext): Attempt[B] =
    flatMap(a => Attempt.Right(f(a)))

  def flatMap[B](f: A => Attempt[B])(implicit ec: ExecutionContext): Attempt[B] = Attempt {
    asFuture.flatMap {
      case Right(a) => f(a).asFuture
      case Left(e) => Future.successful(Left(e))
    }
  }

  def tapFailure(f: FailedAttempt => Unit)(implicit ec: ExecutionContext): Attempt[A] = {
    fold(
      { failure =>
        f(failure)
      }, identity
    )
    this
  }

  def fold[B](failure: FailedAttempt => B, success: A => B)(implicit ec: ExecutionContext): Future[B] = {
    asFuture.map(_.fold(failure, success))
  }

  def map2[B, C](bAttempt: Attempt[B])(f: (A, B) => C)(implicit ec: ExecutionContext): Attempt[C] = {
    for {
      a <- this
      b <- bAttempt
    } yield f(a, b)
  }

  /**
   * Provides applicative style "validated" behaviour that collects failures.
   *
   * Note: This will discard the successful value, it should only be used for failures.
   */
  def |!|[B](a2: Attempt[B])(implicit ec: ExecutionContext): Attempt[Unit] = {
    val collected = underlying map {
      case Left(fa1) =>
        a2.fold(
          { fa2 =>
            Left(FailedAttempt(fa1.failures ++ fa2.failures))
          },
          { _ =>
            Left(fa1)
          }
        )
      case Right(_) =>
        a2.fold(
          { fa2 =>
            Left(fa2)
          },
          { _ =>
            Right(())
          }
        )
    }
    Attempt(collected.flatten)
  }

  /**
   * If the attempt is a failure, return a copy containing only the first failure.
   */
  def firstFailure()(implicit ec: ExecutionContext): Attempt[A] = Attempt {
    fold(
      {
        case FailedAttempt(first :: _) =>
          scala.Left(FailedAttempt(first))
        case fa =>
          scala.Left(fa)
      },
      a => scala.Right(a)
    )
  }

  /**
   * If there is an error in the Future itself (e.g. a timeout) we convert it to a
   * Left so we have a consistent error representation. Unfortunately, this means
   * the error isn't being handled properly so we're left with just the information
   * provided by the exception.
   *
   * Try to avoid hitting this method's failure case by always handling Future errors
   * and creating a suitable failure instance for the problem.
   */
  def asFuture(implicit ec: ExecutionContext): Future[Either[FailedAttempt, A]] = {
    underlying recover { case err =>
      val apiErrors = FailedAttempt(Failure(err.getMessage, "Unexpected error", 500, exception = Some(err)))
      scala.Left(apiErrors)
    }
  }
}

object Attempt {
  /**
   * As with `Future.sequence`, changes `List[Attempt[A]]` to `Attempt[List[A]]`.
   *
   * This implementation returns the first failure in the list, or the successful result.
   */
  def sequence[A](responses: List[Attempt[A]])(implicit ec: ExecutionContext): Attempt[List[A]] = {
    traverse(responses)(identity)
  }

  /**
   * Changes generated `List[Attempt[A]]` to `Attempt[List[A]]` via provided function (like `Future.traverse`).
   *
   * This implementation returns the first failure in the list, or the successful result.
   */
  def traverse[A, B](as: List[A])(f: A => Attempt[B])(implicit ec: ExecutionContext): Attempt[List[B]] = {
    as.foldRight[Attempt[List[B]]](Right(Nil))(f(_).map2(_)(_ :: _))
  }

  /**
   * Sequence this attempt as a successful attempt that contains a list of potential
   * failures. This is useful if failure is acceptable in part of the application.
   */
  def sequenceFutures[A](response: List[Attempt[A]])(implicit ec: ExecutionContext): Attempt[List[Either[FailedAttempt, A]]] = {
    Async.Right(Future.traverse(response)(_.asFuture))
  }

  def fromEither[A](e: Either[FailedAttempt, A]): Attempt[A] =
    Attempt(Future.successful(e))

  def fromOption[A](optA: Option[A], ifNone: FailedAttempt): Attempt[A] =
    fromEither(optA.toRight(ifNone))

  /**
   * Convert a plain `Future` value to an attempt by providing a recovery handler.
   */
  def fromFuture[A](future: Future[A])(recovery: PartialFunction[Throwable, FailedAttempt])(implicit ec: ExecutionContext): Attempt[A] = {
    Attempt {
      future
        .map(scala.Right(_))
        .recover { case t =>
          scala.Left(recovery(t))
        }
    }
  }

  /**
   * Discard failures from a list of attempts.
   *
   * **Use with caution**.
   */
  def successfulAttempts[A](attempts: List[Attempt[A]])(implicit ec: ExecutionContext): Attempt[List[A]] = {
    Attempt.Async.Right {
      Future.traverse(attempts)(_.asFuture).map(_.collect { case Right(a) => a })
    }
  }

  /**
   * Create an Attempt instance from a "good" value.
   */
  def Right[A](a: A): Attempt[A] =
    Attempt(Future.successful(scala.Right(a)))

  /**
   * Create an Attempt failure from an Failure instance, representing the possibility of multiple failures.
   */
  def Left[A](errs: FailedAttempt): Attempt[A] =
    Attempt(Future.successful(scala.Left(errs)))
  /**
   * Syntax sugar to create an Attempt failure if there's only a single error.
   */
  def Left[A](err: Failure): Attempt[A] =
    Attempt(Future.successful(scala.Left(FailedAttempt(err))))

  def unit: Attempt[Unit] = Attempt.Right(())

  /**
   * Asyncronous versions of the Attempt Right/Left helpers for when you have
   * a Future that returns a good/bad value directly.
   */
  object Async {
    /**
     * Create an Attempt from a Future of a good value.
     */
    def Right[A](fa: Future[A])(implicit ec: ExecutionContext): Attempt[A] =
      Attempt(fa.map(scala.Right(_)))

    /**
     * Create an Attempt from a known failure in the future. For example,
     * if a piece of logic fails but you need to make a Database/API call to
     * get the failure information.
     */
    def Left[A](ff: Future[FailedAttempt])(implicit ec: ExecutionContext): Attempt[A] =
      Attempt(ff.map(scala.Left(_)))
  }
}