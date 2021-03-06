package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.ActorRefFactory
import akka.util.Timeout
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.messaging.EventHeader
import com.typesafe.config.Config
import scala.collection.immutable.Set

case class AppConfig(mapping: MappingConfig, storage:Set[Config], api: ApiConfig, localStorageConfig: LocalStorageConfig)
case class MappingConfig(path: String, sender: Config, eventHeader: EventHeader, minStorageProviders: Int)

object MappingConfig {
  def apply(config: Config) =
    new MappingConfig(
      config.getString("service.qm.mappingPath"),
      config.getConfig("service.qm.sender"),
      EventHeader(config.getString("service.qm.sender.eventHeader")),
      config.getInt("service.qm.storage.minStorageProviders"))
}

object AppConfig {
  implicit val timeout = Timeout(50L, TimeUnit.SECONDS)
  val apiConfigKey: String = "service.qm.api.public"
  def apply(config: Config) =
    new AppConfig(MappingConfig(config), Set(config), ApiConfig(config, apiConfigKey), new LocalStorageConfig(config))
}

case class LocalStorageConfig(config: Config) extends NamedStorageConfig {
  val storagePath = config.getString("service.qm.storage.providers.local.storagePath")
  val providerId = config.getString("service.qm.storage.providers.local.id")
}

trait NamedStorageConfig  {
  val storagePath: String
  val providerId: String
}
