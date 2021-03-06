package com.adamnfish.thorn.logic

import java.time.ZonedDateTime

import com.adamnfish.thorn.AttemptValues
import com.adamnfish.thorn.logic.Games._
import com.adamnfish.thorn.models.{Player, PlayerAddress, PlayerId, PlayerKey, Rose, Thorn}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks


class GamesTest extends AnyFreeSpec with Matchers with AttemptValues with OptionValues with ScalaCheckDrivenPropertyChecks {
  "newGame" - {
    val gameName = "game-name"
    val creator = Player(
      screenName = "screen-name",
      playerId = PlayerId("id"),
      playerKey = PlayerKey("key"),
      playerAddress = PlayerAddress("address"),
      score = 0,
      roseCount = 3,
      hasThorn = true,
    )

    "sets a random game id" in {
      val firstId = newGame(gameName, creator)
      val secondId = newGame(gameName, creator)
      firstId should not equal secondId
    }

    "sets started to false" in {
      newGame(gameName, creator).started shouldEqual false
    }

    "sets round to None" in {
      newGame(gameName, creator).round shouldEqual None
    }

    "uses provided game name" in {
      newGame(gameName, creator).gameName shouldEqual gameName
    }

    "adds creator to game players" in {
      newGame(gameName, creator).players shouldEqual List(creator)
    }

    "start time is close enough to 'now' for testing purposes" in {
      val now = ZonedDateTime.now()
      val startTime = newGame(gameName, creator).startTime
      val notTooEarly = startTime.isAfter(now.minusSeconds(10))
      val notTooLate = startTime.isBefore(now.plusSeconds(10))
      notTooEarly shouldEqual true
      notTooLate shouldEqual true
    }

    "expiry time is close enough to '21 days hence' for testing purposes" in {
      val expiry = ZonedDateTime.now().plusDays(21)
      val gameExpiry = newGame(gameName, creator).expiry
      val tooEarly = expiry.minusDays(1).toEpochSecond
      val tooLate = expiry.plusDays(1).toEpochSecond

      gameExpiry should (be > tooEarly and be < tooLate)
    }
  }

  "ensureNotStarted" - {
    val game = newGame(
      "test",
      Players.newPlayer("player", PlayerAddress("address"))
    )

    "success if the game has not started" in {
      ensureNotStarted(
        game.copy(started = false)
      ).isSuccessfulAttempt()
    }

    "failure if the game has already started" in {
      ensureNotStarted(
        game.copy(started = true)
      ).isFailedAttempt()
    }
  }

  "requireGame" - {
    "fails if option is empty" in {
      requireGame(None, "test").isFailedAttempt()
    }

    "succeeds if value is present" in {
      requireGame(Some(1), "test").isSuccessfulAttempt()
    }
  }

  "ensureCreator" - {
    val creator = Player(
      screenName = "screen-name",
      playerId = PlayerId("id"),
      playerKey = PlayerKey("key"),
      playerAddress = PlayerAddress("address"),
      score = 0,
      roseCount = 3,
      hasThorn = true,
    )
    val game = newGame("test", creator)

    "succeeds if this is the game's creator" in {
      forAll { (creatorIdStr: String) =>
        val creatorId = PlayerId(creatorIdStr)
        val game = newGame("test", creator.copy(playerId = creatorId))
        ensureCreator(creatorId, game).isSuccessfulAttempt()
      }
    }

    "fails if this is not the game's creator" in {
      forAll { (playerIdStr: String) =>
        whenever(playerIdStr != creator.playerId.pid) {
          val game = newGame("test", creator)
          ensureCreator(PlayerId(playerIdStr), game).isFailedAttempt()
        }
      }
    }
  }

  "ensureNotAlreadyPlaying" - {
    "succeeds if the player address is not already present in this game" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val game = newGame("test", creator)
      ensureNotAlreadyPlaying(game, PlayerAddress("different address")).isSuccessfulAttempt()
    }

    "fails if the player address is already in the game" in {
      val creatorAddress = PlayerAddress("creator-address")
      val creator = Players.newPlayer("creator", creatorAddress)
      val game = newGame("test", creator)
      ensureNotAlreadyPlaying(game, creatorAddress).isFailedAttempt()
    }

    "fails if the player address is already in the game as another player" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val player2Address = PlayerAddress("player-2-address")
      val player2 = Players.newPlayer("player 2", player2Address)
      val game = newGame("test", creator).copy(
        players = List(
          creator, player2,
        )
      )
      ensureNotAlreadyPlaying(game, player2Address).isFailedAttempt()
    }
  }

  "ensureNoDuplicateScreenName" - {
    "succeeds if the screen name is not already present in this game" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val game = newGame("test", creator)
      ensureNoDuplicateScreenName(game, "different screen name").isSuccessfulAttempt()
    }

    "fails if the screen name is already in the game" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val game = newGame("test", creator)
      ensureNoDuplicateScreenName(game, creator.screenName).isFailedAttempt()
    }

    "fails if the screen name is used by another (non-creator) player" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val player2 = Players.newPlayer("player 2", PlayerAddress("player-2-address"))
      val game = newGame("test", creator).copy(
        players = List(
          creator, player2,
        )
      )
      ensureNoDuplicateScreenName(game, player2.screenName).isFailedAttempt()
    }
  }

  "ensurePlayerKey" - {
    val player = Players.newPlayer("player", PlayerAddress("address"))
    val game = newGame("test game", player)

    "succeeds if the player exists and key matches" in {
      ensurePlayerKey(game, player.playerId, player.playerKey)
    }

    "fails if the player is not found" in {
      ensurePlayerKey(game, PlayerId("different id"), player.playerKey)
    }

    "fails if the player key does not match" in {
      ensurePlayerKey(game, player.playerId, PlayerKey("different key"))
    }
  }

  "addPlayer" - {
    "result includes the passed player" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val game = newGame("game-name", creator)
      val player = Players.newPlayer("player", PlayerAddress("player-address"))

      addPlayer(player, game).players should contain(player)
    }
  }

  "updatePlayerAddress" - {
    "updates the game to use the provided address for the player" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val game = newGame("game-name", creator)

      val updatedGame = updatePlayerAddress(creator.playerId, PlayerAddress("new address"), game).value()
      val updatedCreator = updatedGame.players.find(_.playerId == creator.playerId).value
      updatedCreator.playerAddress shouldEqual PlayerAddress("new address")
    }

    "fails if the player does not exist" in {
      val creator = Players.newPlayer("creator", PlayerAddress("creator-address"))
      val game = newGame("game-name", creator)

      updatePlayerAddress(
        PlayerId("does not exist"),
        PlayerAddress("new address"),
        game,
      ).isFailedAttempt()
    }
  }
}
