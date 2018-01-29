package com.zengularity.benji.google

import java.net.URI

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.ObjectStorage

import com.zengularity.benji.spi.{ Injector, StorageFactory, StorageScheme }

/**
 * This factory is using `javax.inject`
 * to resolve [[play.api.libs.ws.StandaloneWSClient].
 */
final class GoogleFactory extends StorageFactory {
  @SuppressWarnings(Array("TryGet"))
  def apply(injector: Injector, uri: URI): ObjectStorage = {
    @inline implicit def ws: StandaloneAhcWSClient =
      injector.instanceOf(classOf[StandaloneAhcWSClient])

    GoogleStorage(GoogleTransport[URI](uri).get)
  }
}

final class GoogleScheme extends StorageScheme {
  val scheme = "google"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[GoogleFactory]
}
