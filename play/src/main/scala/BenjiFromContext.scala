/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package play.modules.benji

import java.net.URI

import play.api.{ ApplicationLoader, BuiltInComponentsFromContext }
import play.api.inject.{ Injector, SimpleInjector }
import play.api.libs.ws.ahc.StandaloneAhcWSClient

/**
 * Can be used for a custom application loader.
 *
 * {{{
 * import play.api.ApplicationLoader
 *
 * class MyApplicationLoader extends ApplicationLoader {
 *   def load(context: ApplicationLoader.Context) =
 *     new MyComponents(context).application
 * }
 *
 * class MyComponents(context: ApplicationLoader.Context)
 *     extends BenjiFromContext(context) {
 *   lazy val router = play.api.routing.Router.empty
 * }
 * }}}
 *
 * @param context the application loader context
 * @param name the name of the Benji configuration to be used
 */
abstract class BenjiFromContext(
  context: ApplicationLoader.Context,
  val name: String) extends BuiltInComponentsFromContext(context) with BenjiComponentsWithInjector {

  /**
   * Initializes Benji components from context using the default configuration.
   */
  def this(context: ApplicationLoader.Context) = this(context, "default")

  /**
   * Default implements just returns the initial injector.
   * Overrides this one to be able to pimp the injector.
   */
  protected def configureBenji(initialInjector: Injector): Injector = {
    val i = new SimpleInjector(initialInjector)

    i + StandaloneAhcWSClient()
  }

  final def benjiInjector: com.zengularity.benji.spi.Injector =
    new PlayInjector(configureBenji(injector))

  private lazy val parsed: Option[URI] =
    BenjiModule.parseConfiguration(configuration).collectFirst {
      case (`name`, uri) => uri
    }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  lazy val parsedUri: URI = parsed.getOrElse(throw configuration.globalError(
    s"Missing Benji configuration for '$name'"))
}
