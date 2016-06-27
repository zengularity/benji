package com.zengularity.ws

import akka.stream.Materializer

import play.api.libs.ws.{ WSClient, WSClientConfig }
import play.api.libs.ws.ahc.{
  AhcWSClient,
  AhcWSClientConfig,
  AhcWSClientConfigFactory
}

object WS {
  /** Returns a WS client (take care to close it once used). */
  def client(config: WSClientConfig = WSClientConfig())(implicit materializer: Materializer): WSClient = ningClient(AhcWSClientConfigFactory forClientConfig config)

  def ningClient(config: AhcWSClientConfig)(implicit materializer: Materializer): AhcWSClient = AhcWSClient(config)
}
