package play.modules.benji

import java.net.URI

import play.api.{ ApplicationLoader, BuiltInComponentsFromContext }

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

  private lazy val parsed: Option[URI] =
    BenjiModule.parseConfiguration(configuration).collectFirst {
      case (`name`, uri) => uri
    }

  lazy val parsedUri: URI = parsed.getOrElse(throw configuration.globalError(
    s"Missing Benji configuration for '$name'"))
}
