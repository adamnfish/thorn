package com.adamnfish.thorn.logic

import com.adamnfish.thorn.attempt.Attempt
import com.adamnfish.thorn.models._

import scala.concurrent.ExecutionContext


object Responses {
  def tbd[A <: Message](): Response[A] = {
    ???
  }

  def empty[A <: Message]: Response[A] = {
    Response(None, Map.empty)
  }

  def ok(): Response[Status] = {
    Response(
      Some(Status("ok")),
      Map.empty
    )
  }

  def justRespond[A <: Message](msg: A): Response[A] = {
    Response(
      Some(msg),
      Map.empty
    )
  }

  def gameStatuses(game: Game)(implicit ec:ExecutionContext): Attempt[Response[GameStatus]] = {
    for {
      statuses <- Attempt.traverse(game.players) { player =>
        Representations.gameStatus(game, player.playerId).map(player.playerAddress -> _)
      }
    } yield {
      Response(
        None,
        statuses.toMap
      )
    }
  }

  def messageAndStatuses[A <: Message](message: A, game: Game)(implicit ec: ExecutionContext): Attempt[Response[A]] = {
    for {
      response <- gameStatuses(game)
    } yield response.copy(response = Some(message))
  }
}
