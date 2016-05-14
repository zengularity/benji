package com.zengularity.ws

import com.ning.http.client.AsyncHttpClientConfig

import play.api.libs.ws.{ WSClientConfig, WSClient }
import play.api.libs.ws.ning.{
  NingAsyncHttpClientConfigBuilder,
  NingWSClientConfigFactory,
  NingWSClient
}

object WS {
  /** Returns a WS client (take care to close it once used). */
  def client(config: WSClientConfig = WSClientConfig()): WSClient =
    ningClient(config)

  def ningClient(config: WSClientConfig): NingWSClient = {
    val wsconfig = NingWSClientConfigFactory forClientConfig config
    val ningconfig = new NingAsyncHttpClientConfigBuilder(wsconfig).build
    val builder = new AsyncHttpClientConfig.Builder(ningconfig)

    new NingWSClient(builder.build)
  }
}
