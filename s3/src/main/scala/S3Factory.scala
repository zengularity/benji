package com.zengularity.benji.s3

import java.net.URI

import javax.inject.Inject

import play.api.libs.ws.StandaloneWSClient

import com.zengularity.benji.ObjectStorage

import com.zengularity.benji.spi.{ StorageFactory, StorageScheme }

/**
 * This factory is using `javax.inject`
 * to resolve [[play.api.libs.ws.StandaloneWSClient].
 */
class S3Factory @Inject() (
  wsClient: StandaloneWSClient) extends StorageFactory {

  @SuppressWarnings(Array("TryGet"))
  def apply(uri: URI): ObjectStorage = {
    @inline implicit def ws = wsClient
    S3[URI](uri).get
  }
}

final class S3Scheme extends StorageScheme {
  val scheme = "s3"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[S3Factory]
}
