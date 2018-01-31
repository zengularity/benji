package com.zengularity.benji.demo

import play.api.ApplicationLoader.Context
import play.api.routing.Router
import play.api.{Application, ApplicationLoader, LoggerConfigurator}
import play.filters.HttpFiltersComponents

import _root_.controllers.AssetsComponents
import router.Routes

import com.zengularity.benji.demo.controllers.BenjiController

import play.modules.benji.BenjiFromContext

class CustomApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }
    new CustomComponents(context).application
  }
}

class CustomComponents(context: Context)
  extends BenjiFromContext(context)
    with AssetsComponents with HttpFiltersComponents {

  implicit val ec = actorSystem.dispatcher

  lazy val applicationController = new BenjiController(controllerComponents, benji)

  lazy val router: Router = new Routes(httpErrorHandler, applicationController, assets)
}