package com.adamnfish.thorn.integration

import com.adamnfish.thorn.Thorn.wake
import com.adamnfish.thorn.models.Wake
import com.adamnfish.thorn.{AttemptValues, TestHelpers, ThornIntegration}
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec


class WakeTest extends AnyFreeSpec with AttemptValues with OptionValues
  with ThornIntegration with TestHelpers {
  "for a valid request" - {
    val wakeRequest = Wake()

    "is successful" in {
      withTestContext { (context, _) =>
        val response = wake(
          wakeRequest,
          context("player-address".address)
        ).value().response.value
        response.message shouldEqual "ok"
      }
    }
  }
}
