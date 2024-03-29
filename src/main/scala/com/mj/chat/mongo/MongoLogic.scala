package com.mj.chat.mongo

import java.io.File
import java.util.Date
import java.util.concurrent.Executors

import com.mj.chat.config.Application
import com.mj.chat.config.Application.configMongoDbname
import com.mj.chat.model.{UpdateResult, User, _}
import com.mj.chat.mongo.MongoOps._
import com.mj.chat.mongo.MongoConnector._
import com.mj.chat.tools.CommonUtils._
import reactivemongo.api.{MongoConnection, MongoDriver}
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson._

import scala.concurrent.ExecutionContext.global
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

object MongoLogic {
  val colUsersName = "users"
  val colSessionsName = "sessions"
  val colMutesName = "mutes"
  val colMessagesName = "messages"
  val colOnlinesName = "onlines"
  implicit val ec: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(50))
  val dbName = configMongoDbname
  val mongoUri = Application.configMongoUri
  val driver = MongoDriver()
  val parsedUri = MongoConnection.parseURI(mongoUri)
  val connection = parsedUri.map(driver.connection)
  val futureConnection = Future.fromTry(connection)
  val db = futureConnection.map(_.database(dbName)).flatMap(f => f)


  val usersCollection: Future[BSONCollection] =
    db.map(_.collection[BSONCollection](colUsersName))
  val sessionsCollection: Future[BSONCollection] =
    db.map(_.collection[BSONCollection](colSessionsName))
  val mutesCollection: Future[BSONCollection] =
    db.map(_.collection[BSONCollection](colMutesName))
  val messagesCollection: Future[BSONCollection] =
    db.map(_.collection[BSONCollection](colMessagesName))
  val onlinesCollection: Future[BSONCollection] =
    db.map(_.collection[BSONCollection](colOnlinesName))

  implicit def sessionStatusHandler = Macros.handler[SessionStatus]

  implicit def userHandler = Macros.handler[User]

  implicit def userStatusHandler = Macros.handler[UserStatus]

  implicit def sessionHandler = Macros.handler[Session]

  implicit def muteHandler = Macros.handler[Mute]

  implicit def messageHandler = Macros.handler[Message]

  implicit def onlineHandler = Macros.handler[Online]



  //create users collection and index
  def createUsersCollection(): Future[String] = {
    val indexSettings = Array(
      //colName, sort, unique, expire
      ("login", 1, true, 0),
      ("nickname", 1, false, 0)
    )
    createIndex(colUsersName, indexSettings)
  }

  //create sessions collection and index
  def createSessionsCollection(): Future[String] = {
    val indexSettings = Array(
      //colName, sort, unique, expire
      ("createuid", 1, false, 0),
      ("ouid", 1, false, 0),
      ("lastUpdate", -1, false, 0)
    )
    createIndex(colSessionsName, indexSettings)
  }

  //create mutes collection and index
  def createMutesCollection(): Future[String] = {
    val indexSettings = Array(
      //colName, sort, unique, expire
      ("from", 1, false, 0),
      ("to", -1, false, 0)
    )
    createIndex(colMutesName, indexSettings)
  }

  //create messages collection and index
  def createMessagesCollection(): Future[String] = {
    val indexSettings = Array(
      //colName, sort, unique, expire
      ("uid", 1, false, 0),
      ("sessionid", 1, false, 0),
      ("dateline", -1, false, 0)
    )
    createIndex(colMessagesName, indexSettings)
  }

  //create onlines collection and index
  def createOnlinesCollection(): Future[String] = {
    val indexSettings = Array(
      //colName, sort, unique, expire
      ("uid", 1, true, 0),
      ("dateline", -1, false, 15 * 60)
    )
    createIndex(colOnlinesName, indexSettings)
  }

  def getUserInfo(uid: String): Future[User] = {
    findCollectionOne[User](usersCollection, document("_id" -> uid))
  }




  //create private session if not exist or get private session
  def createPrivateSession(uid: String,
                           ouid: String): Future[(String, String)] = {
    for {
      user <- findCollectionOne[User](usersCollection, document("_id" -> uid))
      ouser <- findCollectionOne[User](usersCollection, document("_id" -> ouid))
      (session, errmsgUserNotExist) <- {
        var errmsg = ""
        var ret = Future[(Session, String)](null, errmsg)
        if (user != null && ouser != null) {
          val selector = document(
            "$or" -> array(
              document("createuid" -> uid, "ouid" -> ouid),
              document("createuid" -> ouid, "ouid" -> uid)
            )
          )
          ret = findCollectionOne[Session](sessionsCollection, selector).map {
            s =>
              (s, "")
          }
        } else {
          errmsg = "send user or recv user not exist"
          ret = Future(null, errmsg)
        }
        ret
      }
      (sessionid, errmsg) <- {
        var ret = Future("", errmsgUserNotExist)
        if (errmsgUserNotExist == "") {
          if (session != null) {
            ret = Future(session._id, "")
          } else {
            val newSession = Session("",
                                     createuid = uid,
                                     ouid = ouid,
                                     sessionName = "",
                                     sessionIcon = "",
                                     sessionType = 0,
                                     publicType = 0)
            ret = insertCollection[Session](sessionsCollection, newSession)
            for {
              (sessionid, errmsg) <- ret
              uidJoin <- {
                if (sessionid != "") {
                  joinSession(uid, sessionid)
                } else {
                  Future(UpdateResult(0, "sessionid is empty"))
                }
              }
              ouidJoin <- {
                if (sessionid != "") {
                  joinSession(ouid, sessionid)
                } else {
                  Future(UpdateResult(0, "sessionid is empty"))
                }
              }
            } yield {}
          }
        }
        ret
      }
    } yield {
      (sessionid, errmsg)
    }
  }

  def getUserInfoByName(nickname: String): Future[List[User]] = {
    for {
      users <- {
        var users = Future(List[User]())
        users = findCollection[User](usersCollection,
                                     document("nickname" -> nickname))
        users
      }
    } yield {
      (users)
    }
  }

  //get session info and users who join this session
  def getJoinedUsers(sessionid: String): Future[(Session, List[User])] = {
    for {
      session <- findCollectionOne[Session](sessionsCollection,
                                            document("_id" -> sessionid))
      users <- {
        var users = Future(List[User]())
        if (session != null) {
          val uids = session.usersStatus.map(_.uid)
          val selector = document("_id" -> document("$in" -> uids))
          users = findCollection[User](usersCollection, selector)
        }
        users
      }
    } yield {
      (session, users)
    }
  }

  //join new session
  def joinSession(uid: String, sessionid: String): Future[UpdateResult] = {
    var errmsg = ""
    for {
      user <- findCollectionOne[User](
        usersCollection,
        document("_id" -> uid,
                 "sessionsStatus.sessionid" -> document("$ne" -> sessionid)))
      session <- findCollectionOne[Session](sessionsCollection,
                                            document("_id" -> sessionid))
      updateResult <- {
        if (user == null) {
          errmsg = "user not exists or already join session"
        }
        if (session == null) {
          errmsg = "session not exists"
        }
        var ret = Future(UpdateResult(n = 0, errmsg = errmsg))
        if (errmsg == "") {
          ret = for {
            ur1 <- {
              val docSessionStatus =
                document("sessionid" -> sessionid, "newCount" -> 0)
              val update1 = document(
                "$push" -> document("sessionsStatus" -> docSessionStatus))
              updateCollection(usersCollection, document("_id" -> uid), update1)
            }
            ur2 <- {
              val docUserStatus = document("uid" -> uid, "online" -> false)
              val update2 = document(
                "$push" -> document("usersStatus" -> docUserStatus))
              updateCollection(sessionsCollection,
                               document("_id" -> sessionid),
                               update2)
            }
          } yield {
            val nickname = user.nickname
            val avatar = user.avatar
            val sessionName = session.sessionName
            val sessionIcon = session.sessionIcon
            val msgType = "join"
            val content = s"$nickname join session $sessionName"
            val dateline = timeToStr(System.currentTimeMillis())
            ur2
          }
        }
        ret
      }
    } yield {
      updateResult
    }
  }

  //leave session
  def leaveSession(uid: String, sessionid: String): Future[UpdateResult] = {
    for {
      user <- findCollectionOne[User](
        usersCollection,
        document("_id" -> uid, "sessionsStatus.sessionid" -> sessionid))
      session <- findCollectionOne[Session](
        sessionsCollection,
        document("_id" -> sessionid, "usersStatus.uid" -> uid))
      ret <- {
        if (user == null || session == null) {
          val errmsg = "user not exists or not join the session"
          Future(UpdateResult(n = 0, errmsg = errmsg))
        } else {
          for {
            ur1 <- {
              val sessionstatus =
                user.sessionsStatus.filter(_.sessionid == sessionid).head
              val docSessionStatus = document(
                "sessionid" -> sessionstatus.sessionid,
                "newCount" -> sessionstatus.newCount)
              val update1 = document(
                "$pull" -> document("sessionsStatus" -> docSessionStatus))
              updateCollection(usersCollection, document("_id" -> uid), update1)
            }
            ur2 <- {
              val userstatus = session.usersStatus.filter(_.uid == uid).head
              val docUserStatus =
                document("uid" -> userstatus.uid, "online" -> userstatus.online)
              val update2 = document(
                "$pull" -> document("usersStatus" -> docUserStatus))
              updateCollection(sessionsCollection,
                               document("_id" -> sessionid),
                               update2)
            }
          } yield {
            val nickname = user.nickname
            val avatar = user.avatar
            val sessionName = session.sessionName
            val sessionIcon = session.sessionIcon
            val msgType = "leave"
            val content = s"$nickname leave session $sessionName"
            val dateline = timeToStr(System.currentTimeMillis())
            ur2
          }
        }
      }
    } yield {
      ret
    }
  }

  //list public and joined session
  def listSessions(
      uid: String,
      isPublic: Boolean): Future[List[(Session, SessionStatus)]] = {
    for {
      user <- findCollectionOne[User](usersCollection, document("_id" -> uid))
      sessionInfoList <- {
        if (user != null) {
          if (isPublic) {
            val sessionids = user.sessionsStatus.map(_.sessionid)
            var ba = array()
            sessionids.foreach { sessionid =>
              ba = ba.merge(sessionid)
            }
            val selector = document(
              "publicType" -> 1,
              "sessionType" -> 1,
              "_id" -> document(
                "$nin" -> ba
              )
            )
            val sort = document("lastUpdate" -> -1)
            findCollection[Session](sessionsCollection, selector, sort = sort)
              .map { sessions =>
                sessions.map { session =>
                  val sessionStatus = user.sessionsStatus
                    .find(_.sessionid == session._id)
                    .getOrElse(SessionStatus("", 0))
                  (session, sessionStatus)
                }
              }
          } else {
            Future
              .sequence(
                user.sessionsStatus.map { sessionStatus =>
                  findCollectionOne[Session](
                    sessionsCollection,
                    document("_id" -> sessionStatus.sessionid)).map { session =>
                    (session, sessionStatus)
                  }
                }
              )
              .map { sessions =>
                sessions.sortBy {
                  case (session, sessionStatus) => session.lastUpdate * -1
                }
              }
          }
        } else {
          Future(List[(Session, SessionStatus)]())
        }
      }
      sessions <- {
        Future.sequence(
          sessionInfoList.map {
            case (session, sessionStatus) =>
              getSessionNameIcon(uid, session._id).map { sessionToken =>
                session.sessionName = sessionToken.sessionName
                session.sessionIcon = sessionToken.sessionIcon
                (session, sessionStatus)
              }
          }
        )
      }
    } yield {
      sessions
    }
  }

  def listJoinedSessions(
      uid: String): Future[List[(Session, SessionStatus)]] = {
    for {
      user <- findCollectionOne[User](usersCollection, document("_id" -> uid))
      sessionInfoList <- {
        if (user != null) {
          Future
            .sequence(
              user.sessionsStatus.map { sessionStatus =>
                findCollectionOne[Session](
                  sessionsCollection,
                  document("_id" -> sessionStatus.sessionid))
                  .map { session =>
                    getSessionNameIcon(uid, session._id).map { sessionToken =>
                      session.sessionName = sessionToken.sessionName
                      session.sessionIcon = sessionToken.sessionIcon
                      (session, sessionStatus)
                    }
                  }
                  .flatMap(t => t)
              }
            )
            .map { sessions =>
              sessions.sortBy {
                case (session, sessionStatus) => session.lastUpdate * -1
              }
            }
        } else {
          Future(List[(Session, SessionStatus)]())
        }
      }
    } yield {
      sessionInfoList
    }
  }

  //verify user is in session
  def verifySession(senduid: String, sessionid: String): Future[String] = {
    for {
      user <- findCollectionOne[User](
        usersCollection,
        document("_id" -> senduid, "sessionsStatus.sessionid" -> sessionid))
      session <- findCollectionOne[Session](
        sessionsCollection,
        document("_id" -> sessionid, "usersStatus.uid" -> senduid))
    } yield {
      if (user != null && session != null) {
        ""
      } else {
        "no privilege in this session"
      }
    }
  }

  //create a new message
  def insertMessage(uid: String,
                    sessionid: String,
                    msgType: String,
                    content: String = "",
                    fileName: String = "",
                    fileType: String = "",
                    fileid: String = "",
                    thumbid: String = ""): Future[(String, String)] = {
    val message = Message("",
                          uid,
                          sessionid,
                          msgType,
                          content,
                          fileName,
                          fileType,
                          fileid,
                          thumbid)
    for {
      (msgid, errmsg) <- insertCollection[Message](messagesCollection, message)
      session <- {
        if (msgid != "") {
          findCollectionOne[Session](sessionsCollection,
                                     document("_id" -> sessionid))
        } else {
          Future(null)
        }
      }
      updateLastMsgId <- {
        if (session != null) {
          val selector = document("_id" -> sessionid)
          val update = document(
            "$set" ->
              document(
                "lastMsgid" -> msgid,
                "lastUpdate" -> System.currentTimeMillis()
              ))
          updateCollection(sessionsCollection, selector, update)
        } else {
          Future(UpdateResult(n = 0, errmsg = "nothing to update"))
        }
      }
      updateNewCounts <- {
        if (session != null) {
          Future.sequence(
            //update not online users newCount
            session.usersStatus.filterNot(_.online).map { userstatus =>
              //update userstatus nest array
              val selector = document(
                "_id" -> userstatus.uid,
                "sessionsStatus.sessionid" -> sessionid
              )
              val update = document(
                "$inc" -> document(
                  "sessionsStatus.$.newCount" -> 1
                )
              )
              updateCollection(usersCollection, selector, update)
            }
          )
        } else {
          Future(List[UpdateResult]())
        }
      }
    } yield {
      (msgid, errmsg)
    }
  }

  def updateUserOnlineOffline(uid: String,
                              sessionid: String,
                              isOnline: Boolean): Future[UpdateResult] = {
    val selector = document(
      "_id" -> sessionid,
      "usersStatus.uid" -> uid
    )
    val update = document(
      "$set" -> document(
        "usersStatus.$.online" -> isOnline
      )
    )
    updateCollection(sessionsCollection, selector, update)
  }

  def getSessionLastMessage(
      uid: String,
      sessionid: String): Future[(Session, Message, User)] = {

    for {
      session <- findCollectionOne[Session](sessionsCollection,
                                            document("_id" -> sessionid))
      message <- {
        if (session != null) {
          findCollectionOne[Message](messagesCollection,
                                     document("_id" -> session.lastMsgid))
        } else {
          null
        }
      }
      user <- {
        if (message != null) {
          findCollectionOne[User](usersCollection,
                                  document("_id" -> message.uid))
        } else {
          Future(null)
        }
      }
    } yield {
      (session, message, user)
    }

  }

  //list history messages
  def listHistoryMessages(
      uid: String,
      sessionid: String,
      page: Int = 1,
      count: Int = 10,
      sort: BSONDocument): Future[(String, List[(Message, User)])] = {
    for {
      errmsg <- verifySession(uid, sessionid)
      messages <- {
        var messages = Future(List[Message]())
        if (errmsg == "") {
          messages = findCollection[Message](messagesCollection,
                                             document("sessionid" -> sessionid),
                                             sort = sort,
                                             page = page,
                                             count = count)
        }
        messages
      }
      updateNewCount <- {
        if (messages.nonEmpty) {
          val selector = document(
            "_id" -> uid,
            "sessionsStatus.sessionid" -> sessionid
          )
          val update = document(
            "$set" -> document(
              "sessionsStatus.$.newCount" -> 0
            )
          )
          updateCollection(usersCollection, selector, update)
        } else {
          Future(UpdateResult(n = 0, errmsg = "nothing to update"))
        }
      }
      listMessageUser <- {
        Future.sequence(
          messages.map { message =>
            findCollectionOne[User](usersCollection,
                                    document("_id" -> message.uid)).map {
              user =>
                (message, user)
            }
          }
        )
      }
    } yield {
      (errmsg, listMessageUser)
    }
  }

  def getSessionNameIcon(uid: String,
                         sessionid: String): Future[SessionToken] = {
    for {
      session <- findCollectionOne[Session](sessionsCollection,
                                            document("_id" -> sessionid))
      sessionToken <- {
        var futureSessionToken = Future(SessionToken("", "", ""))
        if (session != null) {
          if (session.sessionType == 1) {
            //group session
            futureSessionToken = Future(
              SessionToken(session._id,
                           session.sessionName,
                           session.sessionIcon))
          } else {
            //private session
            if (session.usersStatus.nonEmpty) {
              val ouid =
                session.usersStatus.filter(_.uid != uid).map(_.uid).head
              futureSessionToken =
                findCollectionOne[User](usersCollection,
                                        document("_id" -> ouid)).map { ouser =>
                  if (ouser != null) {
                    SessionToken(session._id, ouser.nickname, ouser.avatar)
                  } else {
                    SessionToken("", "", "")
                  }
                }
            }
          }
        }
        futureSessionToken
      }
    } yield {
      sessionToken
    }
  }

  def getSessionHeader(uid: String,
                       sessionid: String): Future[(Session, SessionToken)] = {
    for {
      session <- findCollectionOne[Session](sessionsCollection,
                                            document("_id" -> sessionid))
      sessionToken <- getSessionNameIcon(uid, sessionid)
    } yield {
      (session, sessionToken)
    }
  }

  def getSessionMenu(uid: String,
                     sessionid: String): Future[(Session, Boolean, Boolean)] = {
    for {
      session <- findCollectionOne[Session](sessionsCollection,
                                            document("_id" -> sessionid))
      user <- findCollectionOne[User](usersCollection, document("_id" -> uid))
    } yield {
      if (session != null && user != null) {
        val joined = session.usersStatus.map(_.uid).contains(uid)
        val editable = session.createuid == uid
        (session, joined, editable)
      } else {
        (null, false, false)
      }
    }
  }

  def getUserMenu(uid: String): Future[Option[User]] = {
    findCollectionOne[User](usersCollection, document("_id" -> uid))
      .map(Option(_))
  }

  def insertMute(muteDto: MuteDto): Future[Mute] = {
    for {
      mute <- findCollectionOne[Mute](mutesCollection,
                                      document("from" -> muteDto.from, "to" -> muteDto.to))

      response <- if (mute._id.isEmpty)
        insert[Mute](mutesCollection, Mute("", muteDto.from, muteDto.to))
      else Future{Mute("",muteDto.from,muteDto.to)}
    } yield response
  }

  def removeMute(muteDto: MuteDto): Future[UpdateResult] = {
    removeCollection(mutesCollection, document("from" -> muteDto.from, "to" -> muteDto.to))
  }

  def isMute(from: String, sessionid: String): Future[Boolean] = {
    for {
      to <- findCollectionOne[Session](sessionsCollection,
                                       document("_id" -> sessionid))
        .map(session => session.usersStatus.filterNot(_.uid == from).head.uid)
      mute <- findCollectionOne[Mute](mutesCollection,
                                      document("from" -> from, "to" -> to))
    } yield {
      Option(mute) match {
        case Some(_) => true
        case _       => false
      }
    }
  }

}
