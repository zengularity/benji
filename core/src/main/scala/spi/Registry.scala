/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.spi

import scala.collection.immutable.Set

/**
 * Register the known [[StorageFactory]]
 * according their associated [[StorageScheme]].
 */
final class Registry private[spi] () {

  /** The known factories per scheme */
  lazy val factories: Map[String, Class[_ <: StorageFactory]] = {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    val services = loader.iterator

    @annotation.tailrec
    def append(
        m: Map[String, Class[_ <: StorageFactory]]
      ): Map[String, Class[_ <: StorageFactory]] = {
      if (!services.hasNext) {
        m
      } else {
        val s = services.next()

        append(m + (s.scheme -> s.factoryClass))
      }
    }

    append(Map.empty[String, Class[_ <: StorageFactory]])
  }

  /** Returns the class of the factory corresponding to the specified scheme. */
  def factoryClass(scheme: String): Option[Class[_ <: StorageFactory]] =
    factories.get(scheme)

  /** The supported schemes. */
  lazy val schemes: Set[String] = factories.keySet
}

/** Registry utility */
object Registry {

  /** Returns a new registry instance. */
  def getInstance: Registry = new Registry()
}
