package com.mj.chat.processor.mute

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.util.Timeout
import com.mj.chat.config.MessageConfig
import com.mj.chat.model.{MuteDto, responseMessage}
import com.mj.chat.mongo.MongoLogic.removeMute

import scala.concurrent.ExecutionContext.Implicits.global

class RemoveMuteProcessor extends Actor with MessageConfig {

  implicit val timeout = Timeout(500, TimeUnit.SECONDS)


  def receive = {

    case (muteDto: MuteDto) => {
      val origin = sender()
      val result = removeMute(muteDto).map(resp => origin ! responseMessage("", "", "remove mute success"))




      result.recover {
        case e: Throwable => {
          origin ! responseMessage("", e.getMessage, "")
        }
      }
    }
  }
}
