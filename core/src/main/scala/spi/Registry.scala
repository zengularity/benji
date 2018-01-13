package com.zengularity.benji.spi

import scala.collection.immutable.Set

final class Registry private[spi] () {
  lazy val factories: Map[String, Class[_ <: StorageFactory]] = {
    val builder = Map.newBuilder[String, Class[_ <: StorageFactory]]
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    val services = loader.iterator

    while (services.hasNext) {
      val s = services.next()

      builder += (s.scheme -> s.factoryClass)
    }

    builder.result()
  }

  /** Returns the class of the factory corresponding to the specified scheme. */
  def factoryClass(scheme: String): Option[Class[_ <: StorageFactory]] =
    factories.get(scheme)

  /** The supported schemes. */
  lazy val schemes: Set[String] = factories.keySet
}

object Registry {
  def getInstance: Registry = new Registry()
}
