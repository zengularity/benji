package com.zengularity.benji.google

import java.net.URI

import javax.inject.Inject

import play.api.libs.ws.StandaloneWSClient

import com.zengularity.benji.ObjectStorage

import com.zengularity.benji.spi.{ StorageFactory, StorageScheme }

/**
 * This factory is using `javax.inject`
 * to resolve [[play.api.libs.ws.StandaloneWSClient].
 */
class GoogleFactory @Inject() (
  wsClient: StandaloneWSClient) extends StorageFactory {

  @SuppressWarnings(Array("TryGet"))
  def apply(uri: URI): ObjectStorage = {
    @inline implicit def ws = wsClient
    GoogleStorage()(GoogleTransport[URI](uri).get)
  }
}

final class GoogleScheme extends StorageScheme {
  val scheme = "google"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[GoogleFactory]
}
