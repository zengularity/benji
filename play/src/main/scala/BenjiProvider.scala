/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package play.modules.benji

import java.net.URI
import javax.inject._

import scala.util.{ Failure, Try }

import akka.stream.Materializer

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.{ Injector, StorageFactory }

private[benji] final class BenjiProvider(
    factoryClass: Class[_ <: StorageFactory],
    uri: URI)
    extends Provider[ObjectStorage] {

  @SuppressWarnings(
    Array("org.wartremover.warts.Var", "org.wartremover.warts.Null")
  )
  @Inject var benjiInjector: Injector = _

  lazy val get: ObjectStorage = {
    val factory = benjiInjector.instanceOf(factoryClass)

    factory(benjiInjector, uri)
  }
}

private[benji] object BenjiProvider {

  def from(configUri: URI): Try[BenjiProvider] =
    BenjiConfig.registry.factoryClass(configUri.getScheme) match {
      case Some(cls) => Try(new BenjiProvider(cls, configUri))

      case _ =>
        Failure[BenjiProvider](
          new IllegalArgumentException(
            s"Unsupported storage URI: ${configUri.toString}"
          )
        )
    }
}

private[benji] final class PlayInjectorProvider extends Provider[Injector] {

  @SuppressWarnings(
    Array("org.wartremover.warts.Null", "org.wartremover.warts.Var")
  )
  @Inject var injector: play.api.inject.Injector = _

  lazy val get: Injector = new PlayInjector(injector)
}

/** Utility to bind Benji injector abstraction with Play implementation. */
final class PlayInjector(
    underlying: play.api.inject.Injector)
    extends Injector {
  def instanceOf[T](cls: Class[T]): T = underlying.instanceOf[T](cls)
}

private[benji] final class WSProvider extends Provider[StandaloneAhcWSClient] {

  @SuppressWarnings(
    Array("org.wartremover.warts.Null", "org.wartremover.warts.Var")
  )
  @Inject var materializer: Materializer = _

  lazy val get: StandaloneAhcWSClient = {
    implicit def m: Materializer = materializer
    StandaloneAhcWSClient()
  }
}
