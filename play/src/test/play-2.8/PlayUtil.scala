package tests.benji.play

import play.api.{ ApplicationLoader, Environment, Mode }

import play.api.inject.guice.GuiceApplicationBuilder

object PlayUtil {
  def context = {
    val env = Environment.simple(mode = Mode.Test)

    ApplicationLoader.Context.create(env)
  }

  def configure(initial: GuiceApplicationBuilder): GuiceApplicationBuilder =
    initial.load(
      new play.api.i18n.I18nModule(),
      new play.api.mvc.CookiesModule(),
      new play.api.inject.BuiltinModule(),
      new play.modules.benji.BenjiModule())
}
