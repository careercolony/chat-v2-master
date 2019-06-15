package com.mj.chat.mongo

import java.util.concurrent.Executors

import com.mj.chat.config.Application
import com.mj.chat.config.Application.configMongoDbname
import org.joda.time.DateTime
import play.api.libs.iteratee.Enumerator
import reactivemongo.api._
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.gridfs.Implicits._
import reactivemongo.api.gridfs.{DefaultFileToSave, GridFS}
import reactivemongo.bson._
import com.mj.chat.config.Application._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

object MongoConnector {

  implicit val ec: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(50))
  val dbName = configMongoDbname
  val mongoUri = Application.configMongoUri
  val driver = MongoDriver()
  val parsedUri = MongoConnection.parseURI(mongoUri)
  val connection = parsedUri.map(driver.connection)
  val futureConnection = Future.fromTry(connection)
  val db = futureConnection.flatMap(_.database(dbName))

  //insert single document into collection
  /**
    * @param futureCollection : Future[BSONCollection], collection to insert
    * @param record           : T, record is BaseMongoObj
    * @return Future[(id: String, errmsg: String)], inserted id string and errmsg
    */
  def insert[T](futureCollection: Future[BSONCollection], record: T)(
    implicit handler: BSONDocumentReader[T] with BSONDocumentWriter[T] with BSONHandler[BSONDocument, T]): Future[T] = {

    val insertResult = for {
      col <- futureCollection
      wr <- col.insert[T](record)
    } yield {
      if (wr.ok) {
        record
      } else {
        throw new Exception("insert record error:")
      }

    }
    insertResult.recover {
      case e: Throwable =>
        throw new Exception("insert record error:")
    }
  }

  //find in collection return one record
  /**
    * @param futureCollection : Future[BSONCollection], collection to insert
    * @param selector         : BSONDocument, filter
    * @return Future[T], return the record, if not found return null
    */
  def search[T](futureCollection: Future[BSONCollection],
                selector: BSONDocument)(implicit handler: BSONDocumentReader[T] with BSONDocumentWriter[T] with BSONHandler[
    BSONDocument, T]): Future[Option[T]] = {
    val findResult: Future[Option[T]] = for {
      col <- futureCollection
      rs <- col
        .find(selector)
        .cursor[T]()
        .collect(1, Cursor.FailOnError[List[T]]())
    } yield {
      rs.headOption
    }

    findResult.recover {
      case e: Throwable => {
        throw new Exception(e.getMessage)
      }
    }
    findResult
  }

  //find in collection return one record
  /**
    * @param futureCollection : Future[BSONCollection], collection to insert
    * @param selector         : BSONDocument, filter
    * @return Future[T], return the record, if not found return null
    */
  def searchAll[T](futureCollection: Future[BSONCollection],
                   selector: BSONDocument)(implicit handler: BSONDocumentReader[T] with BSONDocumentWriter[T] with BSONHandler[
    BSONDocument, T]): Future[List[T]] = {
    val findResult: Future[List[T]] = for {
      col <- futureCollection
      rs <- col
        .find(selector)
        .cursor[T]()
        .collect(10, Cursor.FailOnError[List[T]]())
    } yield {
      println("rs:" + rs)
      rs
    }

    findResult.recover {
      case e: Throwable => {
        throw new Exception(e.getMessage)
      }
    }
    findResult
  }


  //find in collection return one record
  /**
    * @param futureCollection : Future[BSONCollection], collection to insert
    * @param selector         : BSONDocument, filter
    * @return Future[T], return the record, if not found return null
    */
  def searchWithPagination[T](futureCollection: Future[BSONCollection],
                              selector: BSONDocument, queryOps: QueryOpts, sort: BSONDocument, limit: Int)(implicit handler: BSONDocumentReader[T] with BSONDocumentWriter[T] with BSONHandler[
    BSONDocument, T]): Future[List[T]] = {
    println("queryOps:" + queryOps + "page.limit:" + limit)
    val findResult: Future[List[T]] = for {
      col <- futureCollection
      rs <- col
        .find(selector)
        .options(queryOps)
        .sort(sort)
        .cursor[T]()
        .collect(limit, Cursor.FailOnError[List[T]]())
    } yield {
      println("rs" + rs)
      rs
    }

    findResult.recover {
      case e: Throwable => {
        throw new Exception(e.getMessage)
      }
    }
    findResult
  }

  /**
    * update in collection
    *
    * @param futureCollection : Future[BSONCollection], collection to update
    * @param selector         : BSONDocument, filter
    * @param update           : BSONDocument, update info
    * @param multi            : Boolean = false, update multi records
    * @return Future[UpdateResult], return the update result
    */
  def update(futureCollection: Future[BSONCollection],
             selector: BSONDocument,
             update: BSONDocument,
             multi: Boolean = false, upsert: Boolean = false): Future[String] = {

    val updateResult = for {
      col <- futureCollection
      uwr <- col.update(selector, update, multi = multi, upsert = upsert)
    } yield {

      if (uwr.nModified > 0) "record updated successfully"
      else throw new Exception("Error while updating record in the data store.")

    }
    updateResult.recover {
      case e: Throwable =>
        throw new Exception("No records Updated")
    }
  }


  /**
    * update in collection
    *
    * @param futureCollection : Future[BSONCollection], collection to update
    * @param selector         : BSONDocument, filter
    * @param update           : BSONDocument, update info
    * @param multi            : Boolean = false, update multi records
    * @return Future[UpdateResult], return the update result
    */
  def updateDetails[T](futureCollection: Future[BSONCollection],
                       selector: BSONDocument,
                       update: T,
                       multi: Boolean = false, upsert: Boolean = false)(
                        implicit handler: BSONDocumentReader[T] with BSONDocumentWriter[T] with BSONHandler[BSONDocument, T]): Future[String] = {

    val updateResult = for {
      col <- futureCollection
      uwr <- col.update(selector, update)
    } yield {

      if (uwr.nModified > 0) "record updated successfully"
      else "No records Updated"

    }
    updateResult.recover {
      case e: Throwable =>
        throw new Exception("No records Updated")
    }
  }

  //remove in collection
  /**
    * @param futureCollection : Future[BSONCollection], collection to update
    * @param selector         : BSONDocument, filter
    * @param firstMatchOnly   : Boolean = false, only remove fisrt match record
    * @return Future[UpdateResult], return the update result
    */
  def remove(futureCollection: Future[BSONCollection], selector: BSONDocument, firstMatchOnly: Boolean = false): Future[String] = {
    val updateData: BSONDocument = BSONDocument ( "$set" -> BSONDocument(
      "status" -> deleted ,  "updated_date" -> Some(DateTime.now.toString("yyyy-MM-dd'T'HH:mm:ssZ"))
    ))
    val updateResult = for {
      col <- futureCollection
      uwr <- col.update(selector, updateData)
    } yield {

      if (uwr.nModified > 0) "record updated successfully"
      else "No records Updated"

    }
    updateResult.recover {
      case e: Throwable =>
        throw new Exception("No records Updated")
    }
  }

  //save grid file in mongodb database
  /**
    * @param bytes       : Array[Byte], file bytes
    * @param fileName    : String, file display name
    * @param contentType : String, content mime type
    * @param metaData    : BSONDocument = document(), file metadata
    * @return Future[(BSONValue, errmsg)], return (id, errmsg)
    */
  def saveGridFile(
                    bytes: Array[Byte],
                    fileName: String,
                    contentType: String,
                    metaData: BSONDocument = document()): Future[(BSONValue, String)] = {
    val saveGridFileResult = for {
      db <- db
      readFile <- {
        val gridfs = GridFS[BSONSerializationPack.type](db)
        val data = Enumerator(bytes)
        val gridfsObj = DefaultFileToSave(filename = Some(fileName),
          contentType = Some(contentType),
          metadata = metaData)
        gridfs.saveWithMD5(data, gridfsObj)
      }
    } yield {
      (readFile.id, "")
    }
    saveGridFileResult.recover {
      case e: Throwable =>
        val errmsg =
          s"save grid file error: fileName = $fileName, contentType = $contentType, $e"
        (BSONNull, errmsg)
    }
  }

  //get grid file meta data in mongodb database
  /**
    * @param selector : BSONDocument, selector filter
    * @return Future[(BSONValue, String, String, Long, BSONDocument, String)]
    *         return the grid file info: (id, fileName, fileType, fileSize, fileMetaData, errmsg)
    */
  def getGridFileMeta(selector: BSONDocument)
  : Future[(BSONValue, String, String, Long, BSONDocument, String)] = {
    val getGridFileResult = for {
      db <- db
      bsonFile <- {
        val gridfs = GridFS[BSONSerializationPack.type](db)
        gridfs.find(selector).head
      }
    } yield {
      (bsonFile.id,
        bsonFile.filename.getOrElse(""),
        bsonFile.contentType.getOrElse(""),
        bsonFile.length,
        bsonFile.metadata,
        "")
    }
    getGridFileResult.recover {
      case e: Throwable =>
        val errmsg = s"get grid file meta error: selector = $selector, $e"
        (BSONNull, "", "", 0L, document(), errmsg)
    }
  }

}
