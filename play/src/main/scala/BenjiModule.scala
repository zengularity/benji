package play.modules.benji

import java.net.URI

import javax.inject._

import scala.util.{ Failure, Success, Try }
import scala.collection.immutable.Set

import play.api._
import play.api.inject.{ Binding, BindingKey, Module }

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.StorageFactory

/**
 * Benji module.
 */
@Singleton
final class BenjiModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = apiBindings(BenjiModule.parseConfiguration(configuration)).toSeq

  private def apiBindings(info: Set[(String, URI)]): Set[Binding[ObjectStorage]] = info.flatMap {
    case (name, uri) =>
      val provider: Provider[ObjectStorage] = {
        @SuppressWarnings(Array("TryGet"))
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
    def unapply(value: com.typesafe.config.ConfigValue): Option[URI] =
      if (!value.unwrapped.isInstanceOf[String]) None
      else {
        Try(value.unwrapped.asInstanceOf[String]).map(new URI(_)) match {
          case Failure(cause) => {
            logger.debug(s"Invalid URI: $value", cause)
            Option.empty[URI]
          }

          case Success(uri) => {
            val s = uri.getScheme

            if (registry.schemes contains s) {
              Some(uri)
            } else {
              logger.debug(s"Unsupported scheme '$s': $uri")
              Option.empty[URI]
            }
          }
        }
      }
  }

  def parseConfiguration(configuration: Configuration): Set[(String, URI)] = configuration.getOptional[Configuration]("benji") match {
    case Some(subConf) => {
      val parsed = Set.newBuilder[(String, URI)]

      subConf.entrySet.iterator.foreach {
        case ("uri", ValidUri(uri)) =>
          parsed += "default" -> uri

        case ("uri", invalid) =>
          logger.warn(s"Invalid setting for 'benji.uri': $invalid")

        case ("default.uri", ValidUri(uri)) =>
          parsed += "default" -> uri

        case ("default.uri", invalid) =>
          logger.warn(s"Invalid setting for 'benji.default.uri': $invalid")

        case (key, ValidUri(uri)) if key.endsWith(".uri") =>
          parsed += key.dropRight(4) -> uri

        case (key, invalid) =>
          logger.warn(s"Invalid setting for '$key': $invalid")
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

      case _ => Failure(new IllegalArgumentException(
        s"Unsupported storage URI: $configUri"))
    }
}

private[benji] final class BenjiProvider(
  factoryClass: Class[_ <: StorageFactory],
  uri: URI) extends Provider[ObjectStorage] {

  @Inject var injector: play.api.inject.Injector = _

  lazy val get: ObjectStorage = {
    val factory = injector.instanceOf(factoryClass)

    factory(uri)
  }
}
