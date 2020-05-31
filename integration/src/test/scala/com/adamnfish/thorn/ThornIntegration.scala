package com.adamnfish.thorn

import java.util.UUID.randomUUID

import com.adamnfish.thorn.attempt.{Attempt, FailedAttempt}
import com.adamnfish.thorn.models.{Context, Message, PlayerAddress}
import com.adamnfish.thorn.persistence.{Database, DynamoDB}
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
import org.scanamo.LocalDynamoDB

import scala.concurrent.ExecutionContext

trait ThornIntegration {
  private val client = LocalDynamoDB.client()

  def withTestContext(f: (PlayerAddress => Context, Database) => Any /* Assertion */): Any /* Assertion */ = {
    val randomSuffix = randomUUID().toString
    val testDb = new DynamoDB(client, Some(randomSuffix))

    LocalDynamoDB.withTable(client)(s"games-$randomSuffix")("gameCode" -> S, "gameId" -> S) {
      LocalDynamoDB.withTable(client)(s"players-$randomSuffix")("gameId" -> S, "playerId" -> S) {
        val addressToContext = Context(
          _,
          testDb,
          new Messaging {
            override def sendMessage(playerAddress: PlayerAddress, message: Message)(implicit ec: ExecutionContext): Attempt[Unit] = {
              Attempt.unit
            }

            override def sendError(playerAddress: PlayerAddress, message: FailedAttempt)(implicit ec: ExecutionContext): Attempt[Unit] = {
              Attempt.unit
            }
          }
        )
        f(addressToContext, testDb)
      }
    }
  }

  implicit class RichAddressString(address: String) {
    def address: PlayerAddress = {
      PlayerAddress(address)
    }
  }
}