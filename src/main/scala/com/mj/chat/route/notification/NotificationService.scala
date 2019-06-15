package com.mj.chat.route.notification

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.mj.chat.notification.{ChatSession, PushSession}


import scala.concurrent.ExecutionContext

/*import com.careercolony.postservices.notification.NotificationRoom
import org.java_websocket._*/


trait NotificationService {

  /* implicit val system: ActorSystem
   implicit val materializer: Materializer*/


  def routeWebsocket(implicit ec: ExecutionContext,
                   system: ActorSystem,
                   materializer: ActorMaterializer): Route =
    get {
      //use for chat service
      path("ws-chat") {
        val chatSession = new ChatSession()
        handleWebSocketMessages(chatSession.chatService)
        //use for push service
      } ~ path("ws-push") {
        val pushSession = new PushSession()
        handleWebSocketMessages(pushSession.pushService)
      }
    }
}

