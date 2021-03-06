package com.adamnfish.thorn.logic

import com.adamnfish.thorn.attempt.{Attempt, Failure}
import com.adamnfish.thorn.models._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext


object Play {
  def advanceActivePlayer(players: List[Player], currentActivePlayerId: PlayerId): PlayerId = {
    @tailrec
    def loop(remainder: List[Player]): PlayerId = {
      remainder match {
        case next :: tail =>
          if (Players.outOfDiscs(next))
            loop(tail)
          else
            next.playerId
        case Nil =>
          // fall back to the next player in table order, but this should be unreachable
          players.head.playerId
      }
    }
    val nextPlayers = (players ++ players).dropWhile(_.playerId != currentActivePlayerId).drop(1)
    loop(nextPlayers)
  }

  def nextStartPlayer(finished: Finished): PlayerId = {
    ???
  }

  def placeDisc(disc: Disc, playerId: PlayerId, game: Game)(implicit ec: ExecutionContext): Attempt[Game] = {
    for {
      player <- Games.getPlayer(playerId, game)
      newRound <- {
        val failure = (roundStr: String) => Failure(
          s"cannot place discs in $roundStr round",
          "You can't place discs now",
          400
        ).asAttempt
        game.round match {
          case None =>
            Attempt.Left {
              failure("none")
            }
          case Some(round @ InitialDiscs(_, initialDiscs)) =>
            for {
              _ <-
                if (initialDiscs.getOrElse(playerId, Nil).isEmpty) Attempt.unit
                else Attempt.Left {
                  Failure(
                    "Cannot place second disc in initial discs round",
                    "You have already placed your disc",
                    400
                  )
                }
              _ <-
                disc match {
                  case Rose =>
                    if (player.roseCount > 0)
                      Attempt.unit
                    else
                      Attempt.Left(
                        Failure(
                          "Cannot place a Rose when none remain",
                          "You have run out of Roses",
                          400
                        )
                      )
                  case Thorn =>
                    if (player.hasThorn)
                      Attempt.unit
                    else
                      Attempt.Left(
                        Failure(
                          "Cannot place Thorn after it is lost",
                          "You have lost your Thorn",
                          400
                        )
                      )
                }
              newDiscs = initialDiscs.updatedWith(playerId) {
                case Some(Nil) =>
                  Some(List(disc))
                case None =>
                  Some(List(disc))
                case Some(current) =>
                  Some(current)
              }
            } yield {
              if (allPlayersPlaced(newDiscs, game.players))
                Placing(
                  activePlayer = round.firstPlayer,
                  discs = newDiscs,
                )
              else
                round.copy(initialDiscs = newDiscs)
            }

          case Some(round @ Placing(activePlayerId, discs)) =>
            if (activePlayerId == playerId) {
              val playerPlacedDiscs = discs.getOrElse(playerId, Nil)
              for {
                _ <-
                  disc match {
                    case Rose =>
                      if (playerPlacedDiscs.count(_ == Rose) < player.roseCount)
                        Attempt.unit
                      else
                        Attempt.Left(
                          Failure(
                            "Cannot place a Rose when none remain",
                            "You have run out of Roses",
                            400
                          )
                        )
                    case Thorn =>
                      if (player.hasThorn) {
                        if (playerPlacedDiscs.contains(Thorn)) {
                          Attempt.Left(
                            Failure(
                              "Cannot place a second Thorn",
                              "You have already placed your Thorn",
                              400
                            )
                          )
                        } else {
                          Attempt.unit
                        }
                      } else Attempt.Left {
                        Failure(
                          "Cannot place Thorn after it is lost",
                          "You have lost your Thorn",
                          400
                        )
                      }
                  }
              } yield round.copy(
                activePlayer = advanceActivePlayer(game.players, activePlayerId),
                discs = discs.updatedWith(playerId) {
                  case Some(current) =>
                    Some(disc :: current)
                  case None =>
                    Some(List(disc))
                }
              )
            } else Attempt.Left {
              Failure(
                "Cannot place disc on another player's turn",
                "You can't place a disc when it isn't your turn",
                400
              )
            }
          case Some(_: Bidding) =>
            Attempt.Left {
              failure("bidding")
            }
          case Some(_: Flipping) =>
            Attempt.Left {
              failure("flipping")
            }
          case Some(_: Finished) =>
            Attempt.Left {
              failure("finished")
            }
        }
      }
    } yield game.copy(round = Some(newRound))
  }

  private[logic] def allPlayersPlaced[A](discs: Map[PlayerId, List[Disc]], players: List[Player]): Boolean = {
    players.filterNot(Players.outOfDiscs).forall { player =>
      discs.getOrElse(player.playerId, Nil).nonEmpty
    }
  }

  def bidOnRound(count: Int, playerId: PlayerId, game: Game): Attempt[Game] = {
    val failure = (roundStr: String) => Attempt.Left(Failure(
      s"cannot bid in $roundStr round",
      "You can't place discs now",
      400
    ).asAttempt)
    game.round match {
      case None =>
        failure("none")
      case Some(_: InitialDiscs) =>
        failure("initial discs")
      case Some(placing: Placing) =>
        val numberOfDiscs =
          placing.discs.values.flatten.size
        if (placing.activePlayer != playerId) {
          Attempt.Left(
            Failure(
              "Cannot bid on another player's turn",
              "It's not your turn to bid",
              400
            )
          )
        } else if (count > numberOfDiscs) {
          Attempt.Left {
            Failure(
              "Bid exceeds disc count",
              "You can't bid more than the number of discs",
              400
            )
          }
        } else if (count == numberOfDiscs) {
          Attempt.Right(
            game.copy(
              round = Some(Flipping(
                activePlayer = placing.activePlayer,
                target = count,
                bids = Map(playerId -> count),
                discs = placing.discs,
                revealed = Map.empty
              ))
            )
          )
        } else {
          Attempt.Right {
            game.copy(
              round = Some(Bidding(
                activePlayer = advanceActivePlayer(game.players, placing.activePlayer),
                discs = placing.discs,
                bids = Map(
                  playerId -> count
                ),
                passed = Nil
              ))
            )
          }
        }
      case Some(bidding: Bidding) =>
        if (bidding.activePlayer != playerId) {
          Attempt.Left(
            Failure(
              "Cannot bid on another player's turn",
              "It's not your turn to bid",
              400
            )
          )
        } else {
          val currentBid =
            if (bidding.bids.isEmpty) 0
            else bidding.bids.values.max
          val numberOfDiscs =
            bidding.discs.values.flatten.size
          if (count <= currentBid) {
            Attempt.Left {
              Failure(
                "Bid not larger than previous bids",
                "Your bid must be larger than previous bids",
                400
              )
            }
          } else if (count > numberOfDiscs) {
            Attempt.Left {
              Failure(
                "Bid exceeds disc count",
                "You can't bid more than the number of discs",
                400
              )
            }
          } else if (bidding.passed.contains(playerId)) {
            Attempt.Left {
              Failure(
                "Cannot bid after passing",
                "You can't bid after you have passed",
                400
              )
            }
          } else if (count == numberOfDiscs) {
            Attempt.Right(
              game.copy(
                round = Some(Flipping(
                  activePlayer = bidding.activePlayer,
                  target = count,
                  bids = bidding.bids.updated(playerId, count),
                  discs = bidding.discs,
                  revealed = Map.empty
                ))
              )
            )
          } else {
            Attempt.Right {
              game.copy(
                round = Some(
                  bidding.copy(
                    activePlayer = advanceActivePlayer(game.players, bidding.activePlayer),
                    bids = bidding.bids.updated(playerId, count)
                  )
                )
              )
            }
          }
        }
      case Some(_: Flipping) =>
        failure("flipping")
      case Some(_: Finished) =>
        failure("finished")
    }
  }

  def passRound(playerId: PlayerId, game: Game): Attempt[Game] = {
    val failure = (roundStr: String) => Attempt.Left(Failure(
      s"cannot pass in $roundStr round",
      "You can't pass now",
      400
    ).asAttempt)
    game.round match {
      case None =>
        failure("none")
      case Some(_: InitialDiscs) =>
        failure("initial discs")
      case Some(_: Placing) =>
        failure("placing")
      case Some(bidding: Bidding) =>
        if (bidding.activePlayer != playerId) {
          Attempt.Left {
            Failure(
              "Cannot pass on another player's turn",
              "It's not your turn to pass",
              400
            )
          }
        } else if (bidding.passed.contains(playerId)) {
          Attempt.Left {
            Failure(
              "Cannot pass a second time",
              "You have already passed this round",
              400
            )
          }
        } else {
          biddingRoundWinner(playerId :: bidding.passed, bidding.bids, game.players) match {
            case Some((flipPlayerId, target)) =>
              Attempt.Right {
                game.copy(
                  round = Some(Flipping(
                    activePlayer = flipPlayerId,
                    target = target,
                    bids = bidding.bids,
                    bidding.discs,
                    revealed = Map.empty,
                  ))
                )
              }
            case None =>
              Attempt.Right {
                game.copy(
                  round = Some(
                    bidding.copy(
                      activePlayer = advanceActivePlayer(game.players, playerId),
                      passed = playerId :: bidding.passed
                    )
                  )
                )
              }
          }
        }
      case Some(_: Flipping) =>
        failure("flipping")
      case Some(_: Finished) =>
        failure("finished")
    }
  }

  private[logic] def biddingRoundWinner(passed: List[PlayerId], bids: Map[PlayerId, Int], players: List[Player]): Option[(PlayerId, Int)] = {
    players.filterNot(Players.outOfDiscs).map(_.playerId).toSet.removedAll(passed).toList match {
      case remainingPlayerId :: Nil =>
        val bid = bids.getOrElse(remainingPlayerId, 0)
        if (bid > 0)
          Some((remainingPlayerId, bid))
        else
          None
      case _ =>
        None
    }
  }

  def flipDisc(playerId: PlayerId, stack: PlayerId, game: Game)(implicit ec: ExecutionContext): Attempt[Game] = {
    val failure = (roundStr: String) => Attempt.Left(Failure(
      s"cannot flip discs in $roundStr round",
      "You can't flip discs now",
      400
    ).asAttempt)
    game.round match {
      case None =>
        failure("none")
      case Some(_: InitialDiscs) =>
        failure("initial discs")
      case Some(_: Placing) =>
        failure("placing")
      case Some(_: Bidding) =>
        failure("bidding")
      case Some(flipping: Flipping) =>
        val hasRevealedOwnDiscs =
          flipping.revealed.getOrElse(playerId, Nil).length == flipping.discs.getOrElse(playerId, Nil).length
        if (flipping.activePlayer != playerId) {
          Attempt.Left(
            Failure(
              "Cannot flip on another player's turn",
              "It's another player's chance to flip discs",
              400
            )
          )
        } else if (!hasRevealedOwnDiscs && stack != playerId) {
          Attempt.Left {
            Failure(
              "Cannot flip other players' discs before your own",
              "You must start by flipping all of your own discs",
              400
            )
          }
        } else {
          val noMoreDiscs =
            flipping.revealed.getOrElse(stack, Nil).length >= flipping.discs.getOrElse(stack, Nil).length
          if (noMoreDiscs) {
            Attempt.Left {
              Failure(
                "All this player's discs have already been flipped",
                "All of this player's discs have already been revealed",
                400
              )
            }
          } else {
            val newRevealed: Map[PlayerId, List[Disc]] = flipping.revealed.updatedWith(stack) { maybeDiscs =>
              if (playerId == stack) {
                // when flipping own discs, flip all at once
                Some(flipping.discs.getOrElse(stack, Nil))
              } else {
                val currentlyRevealed = maybeDiscs.getOrElse(Nil)
                val nextDisc = flipping.discs.getOrElse(stack, Nil).drop(currentlyRevealed.size).head
                Some(currentlyRevealed :+ nextDisc)
              }
            }
            val revealedCount = newRevealed.values.flatten.size

            if (newRevealed.values.flatten.toSet.contains(Thorn)) {
              for {
                updatedPlayers <- Players.removeDiscFromThisPlayer(playerId, game.players)
              } yield game.copy(
                players = updatedPlayers,
                round = Some(Finished(
                  activePlayer = playerId,
                  discs = flipping.discs,
                  revealed = newRevealed,
                  successful = false
                ))
              )
            } else if (revealedCount >= flipping.target) {
              for {
                newPlayers <- Players.bumpPlayerScore(playerId, game.players)
              } yield game.copy(
                players = newPlayers,
                round = Some(Finished(
                  activePlayer = playerId,
                  discs = flipping.discs,
                  revealed = newRevealed,
                  successful = true
                ))
              )
            } else {
              Attempt.Right {
                game.copy(
                  round = Some(flipping.copy(
                    revealed = newRevealed
                  ))
                )
              }
            }
          }
        }
      case Some(_: Finished) =>
        failure("finished")
    }
  }

  def newRound(game: Game)(implicit ec: ExecutionContext): Attempt[Game] = {
    val failure = (roundStr: String) => Attempt.Left(Failure(
      s"cannot start new round from $roundStr round",
      "Finish this round before starting a new one",
      400
    ).asAttempt)
    game.round match {
      case None =>
        failure("none")
      case Some(_: InitialDiscs) =>
        failure("initial discs")
      case Some(_: Placing) =>
        failure("placing")
      case Some(_: Bidding) =>
        failure("bidding")
      case Some(_: Flipping) =>
        failure("flipping")
      case Some(finished: Finished) =>
        roundWinner(game.players) match {
          case Some(winner) =>
            Attempt.Left {
              Failure(
                s"Cannot start a new round after a player has won, score:${winner.score}, discs:${winner.roseCount}-${winner.hasThorn}",
                s"Can't start a new round because ${winner.screenName} has won this game",
                400
              )
            }
          case None =>
            for {
              activePlayer <-
                if (finished.successful) {
                  Attempt.Right {
                    finished.activePlayer
                  }
                } else {
                  Attempt.fromOption(
                    finished.revealed.find { case (_, revealedDiscs) =>
                      revealedDiscs.contains(Thorn)
                    }.map(_._1),
                    Failure(
                      "unsuccessful round finished with no revealed discs",
                      "Couldn't determine first player for next round",
                      500
                    ).asAttempt
                  )
                }
            } yield game.copy(
              round = Some(InitialDiscs(
                firstPlayer = activePlayer,
                initialDiscs = Map.empty,
              ))
            )
        }
    }
  }

  private[logic] def roundWinner(players: List[Player]): Option[Player] = {
    val maybeScoreWinner = players.find(_.score >= 2)
    val maybeLastPlayerStanding = players.filter { player =>
      player.roseCount > 0 || player.hasThorn
    } match {
      case lastPlayerStanding :: Nil =>
        Some(lastPlayerStanding)
      case _ =>
        None
    }
    maybeLastPlayerStanding.orElse(maybeScoreWinner)
  }

  /**
   * Usability improvements:
   *
   * Corrects mistaken chars in the submission.
   */
  def normaliseGameCode(joinGame: JoinGame): JoinGame = {
    joinGame.copy(
      gameCode = joinGame.gameCode
        // Zeros look like 'ohs'
        .replace('O', '0')
        .replace('o', '0')
    )
  }
}
