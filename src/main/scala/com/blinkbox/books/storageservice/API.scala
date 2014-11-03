package com.blinkbox.books.storageservice

import java.lang.reflect.InvocationTargetException

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.logging.Loggers
import com.blinkbox.books.spray.{HttpServer, v2, Directives => CommonDirectives}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.json4s.MappingException
import org.json4s.jackson.Serialization
import org.slf4j.LoggerFactory
import spray.can.Http
import spray.http.StatusCodes._
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._
import spray.util.LoggingContext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.control.NonFatal
import spray.util.NotImplementedException


class QuarterMasterRoutes(qms: QuarterMasterService) extends HttpService
 with CommonDirectives with BasicUnmarshallers with v2.JsonSupport {

 implicit val timeout = AppConfig.timeout
  val appConfig = qms.appConfig
  val actorRefFactory = appConfig.arf
  val mappingRoute = path(appConfig.mappingUri) {
    get{
      complete(MappingHelper.toJson(qms.mapping))
    }
  }

  val storeAssetRoute = {
 implicit val formUnMarshaller2 = FormDataUnmarshallers.MultipartFormDataUnmarshaller

    implicit def textunMarshaller[T: Manifest] =
      Unmarshaller[T](MediaTypes.`text/plain`) {
        case x: HttpEntity.NonEmpty =>
          try Serialization.read[T](x.asString(defaultCharset = HttpCharsets.`UTF-8`))
          catch {
            case MappingException("unknown error", ite: InvocationTargetException) => throw ite.getCause
          }
      }
    path(appConfig.resourcesUri) {
      post {
          entity(as[MultipartFormData]) { (form) =>
            val dataRight  = new MultipartFormField("data",form.get("data")).as[Array[Byte]]
            val labelRight = new MultipartFormField("label", form.get("label")).as[Int]
            val result = for{
              data <- dataRight.right
              label <- labelRight.right
            } yield ( qms.storeAsset(data, label).map[AssetToken](_._1))
            result  match  {
              case Right(result ) => complete(StatusCodes.Accepted, result)
              case  _ => complete(StatusCodes.InternalServerError)
            }
          }
      }
    }
  }

  val assetUploadStatus =
      get {
        path(appConfig.resourcesUri / Segment).as(AssetToken)
           { (assetToken:AssetToken) =>
            respondWithMediaType(MediaTypes.`application/json`) {
              complete(StatusCodes.OK, qms.getStatus(assetToken))
          }
        }

  }

  val updateMappingRoute =
    path(appConfig.mappingUri) {
      post {
        parameters('mappingJson) {
          (mappingString: String) => {
            complete(StatusCodes.Accepted, qms.updateAndBroadcastMapping(mappingString))
          }
        }
      }
    }

  val reloadMappingRoute = path(appConfig.refreshMappingUri) {
    put {
      respondWithMediaType(MediaTypes.`application/json`) {
        complete(StatusCodes.OK, qms.loadMapping)
      }
    }
  }

  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case e :NotImplementedException =>  log.error(e, "Unhandled error")
      uncacheable(NotImplemented, "code: UnknownLabel")
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }

  val log = LoggerFactory.getLogger(classOf[QuarterMasterRoutes])

  val routes = monitor(log) {
    handleExceptions(exceptionHandler) {
      neverCache {
        rootPath(appConfig.root) {
          mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ appConfig.hsc.healthService.routes ~ storeAssetRoute
        }
      }
    }
  }
}


class WebService(config: AppConfig, qms:QuarterMasterService) extends HttpServiceActor {
  implicit val executionContext = actorRefFactory.dispatcher
  val routes = new QuarterMasterRoutes(qms)
  override def receive: Receive = runRoute(routes.routes)
}

object Boot extends App with Configuration with Loggers with StrictLogging {
  logger.info("Starting quartermaster storage service")
  try {
    implicit val system = ActorSystem("storage-service", config)
     sys.addShutdownHook(system.shutdown())
    implicit val requestTimeout = Timeout(5.seconds)
    val appConfig = AppConfig( config, system)
    val httpActor = IO(Http)
    val webService = system.actorOf(Props(classOf[WebService], appConfig, QuarterMasterService(appConfig, new Mapping("",List[UrlTemplate]()))), "storage-service")
    HttpServer(Http.Bind(webService,  interface = "localhost", port = 8080))
  } catch {
    case e: Throwable =>
      logger.error("Error at startup, exiting", e)
      e.printStackTrace
      sys.exit(-1)
  }
  logger.info("Started quartermaster storage service")
}