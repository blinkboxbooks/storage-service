package com.blinkbox.books.storageservice

import java.io.FileWriter
import java.util.concurrent.atomic.AtomicReference
import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.pattern.ask
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.{Event, EventHeader, JsonEventBody, MediaType}
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import org.slf4j.LoggerFactory
import shapeless.get
import spray.http.DateTime
import spray.util.NotImplementedException
import scala.util.matching.Regex

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal

case class UserId(id: String)
case class Label(label:String){
}
case class AssetData(timeStarted: DateTime, totalSize: Long)
case class UrlTemplate(providerId:ProviderId, label:Label, template:String, extractor: String) {
  val regex = new Regex(extractor)
  def matches(digest: AssetDigest): Boolean = regex.findFirstMatchIn(digest.url).isDefined

  implicit val formats = DefaultFormats + FieldSerializer[UrlTemplate]()
}

case class AssetDigest(url:String) {
  val re = """bbbmap:(\w+):(\S+)""".r
  val re(l, sha1) = url
  val label2 = Label(l)
  def toFileString(): String = url
}

object GenAssetDigest{
  def apply(data: Array[Byte], label:Label): AssetDigest = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val labelString = label.label
    val sha1 = new sun.misc.BASE64Encoder().encode(md.digest(data))
    AssetDigest(s"bbbmap:$labelString:$sha1")
  }
}
case class Mapping(templates: List[UrlTemplate])

trait MappingLoader {
  def load(path: String): String
  def write(path: String, json: String): Unit
}

case class FileMappingLoader() extends MappingLoader {
  override def load(path: String): String =
    Source.fromFile(path).mkString("")

  override def write(path: String, json: String): Unit = {
    val fw = new FileWriter(path)
    try fw.write(json)
    finally fw.close()
  }
}

case class MappingHelper(loader: MappingLoader) extends JsonMethods with v2.JsonSupport {

  val extractorName = "extractor"
  val templatesName = "templates"

  def fromJsonStr(jsonString: String): Mapping = {
    val m = read[Option[Mapping]](jsonString)
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: Mapping): String = write[Mapping](m)

  def store(mappingPath: String, mapping: Mapping): Future[Unit] =
    Future(loader.write(mappingPath, toJson(mapping)))

  def load(path: String): Future[Mapping] = Future(loader.load(path)).map(fromJsonStr)
}

class MessageSender(config: AppConfig, arf: ActorRefFactory) extends JsonMethods with v2.JsonSupport {
  val eventHeader = config.mapping.eventHeader
  private val reliableConnection = RabbitMq.reliableConnection(config.rabbitmq)
  val publisherConfiguration = PublisherConfiguration(config.mapping.sender)
  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")
  val executionContext = DiagnosticExecutionContext(arf.dispatcher)
  implicit val timeout = AppConfig.timeout
  implicit object MappingValue extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.mapping.update.v1+json")
  }

  def broadcastUpdate(mapping: Mapping): Future[Any] = {
    qSender ? Event.json[Mapping](eventHeader, mapping)
  }
}

case class QuarterMasterService(appConfig: AppConfig,  messageSender: MessageSender, storageManager: StorageManager, mappingHelper: MappingHelper) {

  val log = LoggerFactory.getLogger(classOf[QuarterMasterRoutes])

  def cleanUp(assetDigest: AssetDigest): Future[Map[ProviderId, Status]] =
    storageManager.cleanUp(assetDigest).map(_.toMap)

  def storeAsset(bytes: Array[Byte], label: Label): Future[(AssetDigest, Future[Map[ProviderId, Status]])] = Future {
    if (storageManager.getProvidersForLabel(label).size < appConfig.mapping.minStorageProviders) {
      throw new NotImplementedException(s"label $label is has no available storage providers")
    }
    if (bytes.size < 1) {
      throw new IllegalArgumentException(s"no data")
    }
    storageManager.storeAsset(label, bytes)
  }

  def getStatus(assetDigest: AssetDigest): Future[Map[ProviderId, Status]] =
    storageManager.getStatus(assetDigest)

  def updateAndBroadcastMapping(mappingStr: String): Future[String] ={
    val oldMapping = storageManager.mapping.get
    val storeAndBroadcastFuture = for {
      newMapping <- Future(mappingHelper.fromJsonStr(mappingStr))
      _ = storageManager.mapping.compareAndSet(oldMapping,newMapping)
      _ <- mappingHelper.store(appConfig.mapping.path, newMapping)
      _ <- messageSender.broadcastUpdate(newMapping)
    } yield mappingHelper.toJson(newMapping)
    storeAndBroadcastFuture.onFailure {
      case NonFatal(e) =>
        log.error(s"couldnt storeAndBroadcast mapping for $mappingStr",e)
        storageManager.mapping.set(oldMapping)}
    storeAndBroadcastFuture
  }

  def loadMapping(): Future[String] = {
    val oldMapping = storageManager.mapping.get
    val loadAndSetFuture = for {
      newMapping <- mappingHelper.load(appConfig.mapping.path)
      _ = storageManager.mapping.compareAndSet(oldMapping,newMapping)
    } yield mappingHelper.toJson(newMapping)
    loadAndSetFuture.onFailure{
      case NonFatal(e) =>
        log.error("couldnt load mapping",e)
        storageManager.mapping.set(oldMapping)
    }
    loadAndSetFuture
  }
}
