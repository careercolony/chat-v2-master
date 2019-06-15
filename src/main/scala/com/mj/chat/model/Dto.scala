package com.mj.chat.model


import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, RootJsonFormat}


case class MuteDto(from: String, to: String)

case class LoginDto(login: String, password: String)

case class UidDto(uid: String)

case class NickNameDto(nickName: String)

case class UidSessionidDto(uid: String, sessionid: String)

case class UidOuidDto(uid: String, ouid: String)

case class ListSessionDto(uid: String, isPublic: Int)

case class RegisterDto(login: String,
                       nickname: String,
                       password: String,
                       repassword: String,
                       gender: Int)

case class ListMessageDto(uid: String, sessionid: String, page: Int, count: Int)


//Response format for all apis
case class responseMessage(uid: String, errmsg: String, successmsg: String)

object JsonRepo extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val errorMessageDtoFormats: RootJsonFormat[responseMessage] = jsonFormat3(responseMessage)
  implicit val muteDtoFormats: RootJsonFormat[MuteDto] = jsonFormat2(MuteDto)

//  implicit val replyResponseFormats: RootJsonFormat[Reply] = jsonFormat7(Reply)
}
