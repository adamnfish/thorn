package com.adamnfish.thorn.logic

import java.util.UUID.randomUUID

import com.adamnfish.thorn.attempt.{Attempt, Failure}
import com.adamnfish.thorn.models._


object Players {
  def newPlayer(screenName: String, address: PlayerAddress): Player = {
    val id = randomUUID().toString
    val key = randomUUID().toString
    Player(
      screenName = screenName,
      playerId = PlayerId(id),
      playerKey = PlayerKey(key),
      playerAddress = address,
      score = 0,
      roseCount = 3,
      hasThorn = true,
    )
  }

  def ensureAllPlayersPresent(gameDB: GameDB, playerDBs: List[PlayerDB]): Attempt[Unit] = {
    gameDB.playerIds.filterNot(pid => playerDBs.exists(_.playerId == pid)) match {
      case Nil =>
        Attempt.unit
      case missingIds =>
        Attempt.Left {
          Failure(
            s"Game players result is missing players `${missingIds.mkString(",")}`",
            "Couldn't fetch all the players for this game",
            500
          )
        }
    }
  }
}