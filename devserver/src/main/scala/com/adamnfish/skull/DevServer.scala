package com.adamnfish.skull

import com.adamnfish.skull.models.Status
import io.javalin.Javalin

import scala.concurrent.ExecutionContext.Implicits.global


object DevServer {
  val messaging = new DevMessaging

  def main(args: Array[String]): Unit = {
    val app = Javalin.create().start(7000)

    app.ws("/api", { ws =>
      ws.onConnect { wctx =>
        val id = messaging.connect(wctx)
        println(s"id: $id")
      }
      ws.onClose { wctx =>
        messaging.disconnect(wctx)
      }
      ws.onMessage { wctx =>
        val rid = wctx.message
        messaging.send(rid, Status("ok"))
      }
    })

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      app.stop()
    }))
  }
}
