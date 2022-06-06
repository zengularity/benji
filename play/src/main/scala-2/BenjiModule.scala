/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package play.modules.benji

import java.net.URI
import javax.inject._

import scala.collection.immutable.Set

import play.api._
import play.api.inject.{ Binding, BindingKey, Module }
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.Injector

/**
 * Benji module.
 */
@Singleton
final class BenjiModule extends Module {

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def bindings(
      environment: Environment,
      configuration: Configuration
    ): Seq[Binding[_]] =
    bind[Injector].toProvider[PlayInjectorProvider] +:
      bind[StandaloneAhcWSClient].toProvider[WSProvider] +:
      apiBindings(BenjiConfig parse configuration).toSeq

  private def apiBindings(
      info: Set[(String, URI)]
    ): Set[Binding[ObjectStorage]] = info.flatMap {
    case (name, uri) =>
      val provider: Provider[ObjectStorage] = {
        @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
        def unsafe = BenjiProvider.from(uri).get

        unsafe
      }

      val annot: NamedStorage = new NamedStorageImpl(name)
      val bs = List(BenjiModule.key(name).qualifiedWith(annot).to(provider))

      if (name == "default") {
        bind[ObjectStorage].to(provider) :: bs
      } else bs
  }
}

private[benji] object BenjiModule {

  def key(name: String): BindingKey[ObjectStorage] =
    BindingKey(classOf[ObjectStorage]).qualifiedWith(new NamedStorageImpl(name))

}
