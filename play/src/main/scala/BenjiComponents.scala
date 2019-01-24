/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package play.modules.benji

import java.net.URI

import com.zengularity.benji.ObjectStorage

/** Benji components contract */
trait BenjiComponents {
  /** The instance name (default: `default`) */
  def name: String

  /** The connection URI */
  def parsedUri: URI

  /** The ObjectStorage initialized according the current configuration */
  def benji: ObjectStorage
}

/**
 * Default implementation of [[BenjiComponents]].
 *
 * {{{
 * import play.api.ApplicationLoader
 *
 * import com.zengularity.benji.ObjectStorage
 *
 * import play.modules.benji._
 *
 * abstract class OtherComponentsFromContext(
 *   context: ApplicationLoader.Context
 * ) extends play.api.BuiltInComponentsFromContext(context) {
 *   // other components
 * }
 *
 * class MyComponent2(
 *   context: ApplicationLoader.Context,
 *   val name: String, // Name of the storage config (see next section)
 *   val parsedUri: java.net.URI // Benji URI for this component
 * ) extends OtherComponentsFromContext(context) with BenjiComponentsWithInjector {
 *   // can be a Controller, a Play custom Module, ApplicationLoader ...
 *
 *   def benjiInjector = new play.modules.benji.PlayInjector(injector)
 *   def httpFilters: Seq[play.api.mvc.EssentialFilter] = ???
 *   def router: play.api.routing.Router = ???
 * }
 * }}}
 */
trait BenjiComponentsWithInjector extends BenjiComponents {
  /** The injector used to resolve the storage dependencies */
  def benjiInjector: com.zengularity.benji.spi.Injector

  final lazy val benji: ObjectStorage = {
    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    def provider = BenjiModule.provider(parsedUri).get

    val p = provider

    p.benjiInjector = benjiInjector

    p.get
  }
}
