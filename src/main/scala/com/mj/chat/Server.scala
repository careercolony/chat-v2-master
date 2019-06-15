package com.mj.chat

import java.net.InetAddress

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.routing.RoundRobinPool
import akka.stream.ActorMaterializer
import com.mj.chat.config.Application
import com.mj.chat.config.Application._
import com.mj.chat.tools.CommonUtils._
import com.mj.chat.tools.RouteUtils
import com.typesafe.config.ConfigFactory

import scala.concurrent.{ExecutionContext, Future}

object Server extends App {
  val seedNodesStr = seedNodes
    .split(",")
    .map(s => s""" "akka.tcp://users-cluster@$s" """)
    .mkString(",")

  val inetAddress = InetAddress.getLocalHost
  var configCluster = Application.config.withFallback(
    ConfigFactory.parseString(s"akka.cluster.seed-nodes=[$seedNodesStr]"))

  configCluster = configCluster
    .withFallback(
      ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname=$hostName"))
    .withFallback(
      ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$akkaPort"))


  implicit val system: ActorSystem = ActorSystem("chat-cluster", configCluster)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val createMuteProcessor = system.actorOf(RoundRobinPool(poolSize).props(Props[processor.mute.CreateMuteProcessor]), "createMuteProcessor")



  //post
  import system.dispatcher

  Http().bindAndHandle(RouteUtils.logRoute, "0.0.0.0", port)

  consoleLog("INFO",
    s"User server started! Access url: https://$hostName:$port/")
}
