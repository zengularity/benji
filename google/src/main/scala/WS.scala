package com.zengularity.benji.google

import akka.stream.Materializer

import play.api.libs.ws.ahc.{ AhcWSClientConfig, StandaloneAhcWSClient }

object WS {
  /** Returns a WS client (take care to close it once used). */
  def client(config: AhcWSClientConfig = AhcWSClientConfig())(implicit materializer: Materializer): StandaloneAhcWSClient = StandaloneAhcWSClient(config)
}
