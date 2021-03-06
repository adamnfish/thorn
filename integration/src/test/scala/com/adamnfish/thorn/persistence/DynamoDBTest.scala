package com.adamnfish.thorn.persistence

import java.time.{ZoneId, ZonedDateTime}

import com.adamnfish.thorn.AttemptValues
import com.adamnfish.thorn.logic.Games
import com.adamnfish.thorn.models.{GameDB, GameId, PlayerDB}
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scanamo.LocalDynamoDB


class DynamoDBTest extends AnyFreeSpec with AttemptValues with OptionValues {

  val client = LocalDynamoDB.client()
  LocalDynamoDB.createTable(client)("games")("gameCode" -> S, "gameId" -> S)
  LocalDynamoDB.createTable(client)("players")("gameId" -> S, "playerId" -> S)
  val db = new DynamoDB(client, "games", "players")

  val startTime = ZonedDateTime.of(2020, 4, 24, 19, 52, 0, 0, ZoneId.of("UTC"))
  val expiry = startTime.plusDays(1).toEpochSecond

  "games table" - {
    val gameId = "game-id"
    val gameCode = Games.gameCode(GameId(gameId))
    val gameDb = GameDB(
      gameCode, gameId, "game-name", "player-1", List("player-1", "player-2"), true,
      startTime, "flip", Some("player-1"), Map("player-1" -> 1, "player-2" -> 2), expiry
    )

    "round trips correctly by game ID" in {
      db.writeGame(gameDb).isSuccessfulAttempt()
      val result = db.getGame(GameId(gameDb.gameId)).value()
      result.value shouldEqual gameDb
    }

    "round trips correctly by game code" in {
      db.writeGame(gameDb).isSuccessfulAttempt()
      val result = db.lookupGame(gameCode).value()
      result.value shouldEqual gameDb
    }
  }

  "players table" - {
    "round trips correctly" in {
      val playerDb1 = PlayerDB(
        "game-id", "player-1", "key-1", "player-1-address", "Player 1",
        2, List("thorn", "rose"), 3, hasThorn = true, 1, true, expiry
      )
      val playerDb2 = PlayerDB(
        "game-id", "player-2", "key-2", "player-2-address", "Player 2",
        0, List("rose"), 3, hasThorn = true, 0, false, expiry
      )

      db.writePlayer(playerDb1).isSuccessfulAttempt()
      db.writePlayer(playerDb2).isSuccessfulAttempt()
      val result = db.getPlayers(GameId("game-id")).value()
      result shouldEqual List(playerDb1, playerDb2)
    }
  }
}
