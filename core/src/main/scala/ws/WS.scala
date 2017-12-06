package com.zengularity.benji.ws

import akka.stream.Materializer

import play.api.libs.ws.WSClientConfig
import play.api.libs.ws.ahc.{ AhcWSClientConfig, AhcWSClientConfigFactory, StandaloneAhcWSClient }

object WS {
  /** Returns a WS client (take care to close it once used). */
  def client(config: WSClientConfig = WSClientConfig())(implicit materializer: Materializer): StandaloneAhcWSClient =
    ningClient(AhcWSClientConfigFactory.forClientConfig(config))

  def ningClient(config: AhcWSClientConfig)(implicit materializer: Materializer) =
    StandaloneAhcWSClient(config)
}
