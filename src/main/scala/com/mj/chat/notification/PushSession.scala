package com.mj.chat.notification

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.ws.TextMessage.Strict
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream._
import akka.stream.scaladsl._
import com.mj.chat.model._
import com.mj.chat.notification.PushSessionActor
import com.mj.chat.tools.CommonUtils._
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class PushSession()(implicit ec: ExecutionContext, actorSystem: ActorSystem, materializer: ActorMaterializer) {
  implicit val pushMessageWrites = Json.writes[PushMessage]

  val pushSessionActor = actorSystem.actorOf(Props(classOf[PushSessionActor]))
  consoleLog("INFO", s"create new pushSessionActor: $pushSessionActor")

  val source: Source[WsMessageDown, ActorRef] = Source.actorRef[WsMessageDown](bufferSize = Int.MaxValue, OverflowStrategy.fail)

  def pushService: Flow[Message, Strict, ActorRef] = Flow.fromGraph(GraphDSL.create(source) { implicit builder =>
    pushSource =>
      import GraphDSL.Implicits._

      val flowFromWs: FlowShape[Message, UserToken] = builder.add(
        Flow[Message].collect {
          case tm: TextMessage =>
            tm.textStream.runFold("")(_ + _).map { jsonStr =>
              var userTokenStr = ""
              try {
                val json = Json.parse(jsonStr)
                val uid = getJsonString(json, "uid")
                val nickname = getJsonString(json, "nickname")
                val avatar = getJsonString(json, "avatar")
                UserToken(uid, nickname, avatar)
              } catch {
                case e: Throwable =>
                  consoleLog("ERROR", s"parse websocket text message error: $e")
                  throw e
              }
            }
        }.buffer(1024 * 1024, OverflowStrategy.fail).mapAsync(6)(t => t)
      )

      val broadcastWs: UniformFanOutShape[UserToken, UserToken] = builder.add(Broadcast[UserToken](2))

      val filterFailure: FlowShape[UserToken, UserToken] = builder.add(Flow[UserToken].filter(_.uid == ""))
      val flowReject: FlowShape[UserToken, WsTextDown] = builder.add(
        Flow[UserToken].map(_ => WsTextDown("", "", "", "", "", "", "reject", "no privilege to receive push message"))
      )

      val filterSuccess: FlowShape[UserToken, UserToken] = builder.add(Flow[UserToken].filter(_.uid != ""))

      val flowAccept: FlowShape[UserToken, WsMessageDown] = builder.add(
        Flow[UserToken].map { case UserToken(uid, nickname, avatar) => WsTextDown(uid, nickname, avatar, "", "", "", "push", "") }
      )

      val connectedWs: Flow[ActorRef, UserOnline, NotUsed] = Flow[ActorRef].map { actor =>
        UserOnline(actor)
      }

      val mergeAccept: UniformFanInShape[WsMessageDown, WsMessageDown] = builder.add(Merge[WsMessageDown](2))

      val pushActorSink: Sink[WsMessageDown, NotUsed] = Sink.actorRef[WsMessageDown](pushSessionActor, UserOffline)

      val flowAcceptBack: FlowShape[WsMessageDown, WsMessageDown] = builder.add(
        // websocket default timeout after 60 second, to prevent timeout send keepalive message
        // you can config akka.http.server.idle-timeout to set timeout duration
        Flow[WsMessageDown].keepAlive(50.seconds, () => WsTextDown("", "", "", "", "", "", "keepalive", ""))
      )

      val mergeBackWs: UniformFanInShape[WsMessageDown, WsMessageDown] = builder.add(Merge[WsMessageDown](2))

      val flowBackWs: FlowShape[WsMessageDown, Strict] = builder.add(
        Flow[WsMessageDown].collect {
          case WsTextDown(uid, nickname, avatar, sessionid, sessionName, sessionIcon, msgType, content, dateline) =>
            val pushMessage = PushMessage(uid, nickname, avatar, sessionid, sessionName, sessionIcon, msgType, content, fileName = "", fileType = "", fileid = "", thumbid = "", dateline)
            TextMessage(Json.stringify(Json.toJson(pushMessage)))
        }
      )
      flowFromWs ~> broadcastWs
      broadcastWs ~> filterFailure ~> flowReject

      broadcastWs ~> filterSuccess ~> flowAccept ~> mergeAccept.in(0)
      builder.materializedValue ~> connectedWs ~> mergeAccept.in(1)
      mergeAccept ~> pushActorSink // --> to pushSessionActor

      /* from pushSessionActor --> */ pushSource ~> flowAcceptBack ~> mergeBackWs.in(0)
      flowReject ~> mergeBackWs.in(1)
      mergeBackWs ~> flowBackWs

      FlowShape(flowFromWs.in, flowBackWs.out)
  })

}
