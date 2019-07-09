/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package play.modules.benji

import java.net.URI
import javax.inject._

import scala.util.{ Failure, Success, Try }
import scala.collection.immutable.Set

import akka.stream.Materializer

import play.api._
import play.api.inject.{ Binding, BindingKey, Module }
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.{ Injector, StorageFactory }

/**
 * Benji module.
 */
@Singleton
final class BenjiModule extends Module {
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    bind[Injector].toProvider[PlayInjectorProvider] +:
      bind[StandaloneAhcWSClient].toProvider[WSProvider] +:
      apiBindings(BenjiModule.parseConfiguration(configuration)).toSeq

  private def apiBindings(info: Set[(String, URI)]): Set[Binding[ObjectStorage]] = info.flatMap {
    case (name, uri) =>
      val provider: Provider[ObjectStorage] = {
        @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
        def unsafe = BenjiModule.provider(uri).get

        unsafe
      }

      val annot: NamedStorage = new NamedStorageImpl(name)
      val bs = List(BenjiModule.key(name).
        qualifiedWith(annot).to(provider))

      if (name == "default") {
        bind[ObjectStorage].to(provider) :: bs
      } else bs
  }
}

private[benji] object BenjiModule {
  val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  val registry = com.zengularity.benji.spi.Registry.getInstance

  def key(name: String): BindingKey[ObjectStorage] =
    BindingKey(classOf[ObjectStorage]).qualifiedWith(new NamedStorageImpl(name))

  object ValidUri {
    @SuppressWarnings(Array(
      "org.wartremover.warts.AsInstanceOf",
      "org.wartremover.warts.IsInstanceOf",
      "org.wartremover.warts.ToString"))
    def unapply(value: com.typesafe.config.ConfigValue): Option[URI] =
      if (!value.unwrapped.isInstanceOf[String]) None
      else {
        Try(value.unwrapped.asInstanceOf[String]).map(new URI(_)) match {
          case Failure(cause) => {
            logger.debug(s"Invalid URI: ${value.toString}", cause)
            Option.empty[URI]
          }

          case Success(uri) => {
            val s = uri.getScheme

            if (registry.schemes contains s) {
              Some(uri)
            } else {
              logger.debug(s"Unsupported scheme '$s': ${uri.toString}")
              Option.empty[URI]
            }
          }
        }
      }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def parseConfiguration(configuration: Configuration): Set[(String, URI)] =
    configuration.getOptional[Configuration]("benji") match {
      case Some(subConf) => {
        val parsed = Set.newBuilder[(String, URI)]

        subConf.entrySet.iterator.foreach[Unit] {
          case ("uri", ValidUri(uri)) => {
            parsed += "default" -> uri
            ()
          }

          case ("uri", invalid) =>
            logger.warn(s"Invalid setting for 'benji.uri': ${invalid.toString}")

          case ("default.uri", ValidUri(uri)) => {
            parsed += "default" -> uri
            ()
          }

          case ("default.uri", invalid) =>
            logger.warn(s"Invalid setting for 'benji.default.uri': ${invalid.toString}")

          case (key, ValidUri(uri)) if key.endsWith(".uri") => {
            parsed += key.dropRight(4) -> uri
            ()
          }

          case (key, invalid) =>
            logger.warn(s"Invalid setting for '$key': ${invalid.toString}")
        }

        val uris = parsed.result()

        if (uris.isEmpty) {
          logger.warn("No configuration in the 'benji' section")
        }

        uris
      }

      case _ => {
        logger.warn("No 'benji' section found in the configuration")
        Set.empty
      }
    }

  def provider(configUri: URI): Try[BenjiProvider] =
    registry.factoryClass(configUri.getScheme) match {
      case Some(cls) => Try(new BenjiProvider(cls, configUri))

      case _ => Failure[BenjiProvider](new IllegalArgumentException(
        s"Unsupported storage URI: ${configUri.toString}"))
    }
}

private[benji] final class BenjiProvider(
  factoryClass: Class[_ <: StorageFactory],
  uri: URI) extends Provider[ObjectStorage] {

  @SuppressWarnings(Array(
    "org.wartremover.warts.Var",
    "org.wartremover.warts.Null"))
  @Inject var benjiInjector: Injector = _

  lazy val get: ObjectStorage = {
    val factory = benjiInjector.instanceOf(factoryClass)

    factory(benjiInjector, uri)
  }
}

private[benji] final class PlayInjectorProvider extends Provider[Injector] {
  @SuppressWarnings(Array(
    "org.wartremover.warts.Null",
    "org.wartremover.warts.Var"))
  @Inject var injector: play.api.inject.Injector = _

  lazy val get: Injector = new PlayInjector(injector)
}

/** Utility to bind Benji injector abstraction with Play implementation. */
final class PlayInjector(
  underlying: play.api.inject.Injector) extends Injector {
  def instanceOf[T](cls: Class[T]): T = underlying.instanceOf[T](cls)
}

private[benji] final class WSProvider extends Provider[StandaloneAhcWSClient] {
  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
  @Inject var materializer: Materializer = _

  lazy val get: StandaloneAhcWSClient = {
    implicit def m: Materializer = materializer
    StandaloneAhcWSClient()
  }
}
