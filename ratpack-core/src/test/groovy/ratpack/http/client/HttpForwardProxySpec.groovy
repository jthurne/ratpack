/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.http.client

import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import ratpack.error.ServerErrorHandler
import ratpack.error.internal.DefaultDevelopmentErrorHandler
import ratpack.groovy.handling.GroovyChain
import ratpack.groovy.test.embed.GroovyEmbeddedApp
import ratpack.test.embed.EmbeddedApp
import ratpack.test.http.internal.proxy.TestHttpProxyServer
import spock.lang.AutoCleanup
import spock.lang.Shared

import static ratpack.http.Status.INTERNAL_SERVER_ERROR

class HttpForwardProxySpec extends BaseHttpClientSpec {

  @Shared
  @AutoCleanup
  TestHttpProxyServer proxyServer = new TestHttpProxyServer()

  @AutoCleanup
  EmbeddedApp proxyApp

  EmbeddedApp proxyApp(@DelegatesTo(value = GroovyChain, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
    proxyApp = GroovyEmbeddedApp.of {
      registryOf { add ServerErrorHandler, new DefaultDevelopmentErrorHandler() }
      handlers(closure)
    }
  }

  URI proxyAppUrl(String path = "") {
    new URI("$proxyApp.address$path")
  }

  def setup() {
    otherApp {
      get("foo") {
        render "bar"
      }
    }
  }

  def cleanup() {
    proxyServer.stop()
  }

  def "can make simple proxy request using the global HttpClient proxy settings (pooled: #pooled)"() {
    given:
    proxyServer.start()

    when:
    bindings {
      bindInstance(HttpClient, HttpClient.of { config -> config
        .poolSize(pooled ? 8 : 0)
        .proxy { p -> p
          .host(proxyServer.address.hostName)
          .port(proxyServer.address.port)
        }
      })
    }
    handlers {
      get { HttpClient httpClient ->
        httpClient.get(otherAppUrl("foo")) {
        } then { ReceivedResponse response ->
          render response.body.text
        }
      }
    }

    then:
    text == "bar"
    proxyServer.proxied(otherApp.address)

    where:
    pooled << [true, false]
  }

  def "can make proxy request with proxy authentication using the global HttpClient proxy settings (pooled: #pooled)"() {
    given:
    def username = "testUser"
    def password = "testPassword"
    proxyServer.start(username, password)

    when:
    bindings {
      bindInstance(HttpClient, HttpClient.of { config -> config
        .poolSize(pooled ? 8 : 0)
        .proxy { p -> p
          .host(proxyServer.address.hostName)
          .port(proxyServer.address.port)
          .credentials(username, password)
        }
      })
    }
    handlers {
      get { HttpClient httpClient ->
        httpClient.get(otherAppUrl("foo")) {
        } onError(HttpProxyHandler.HttpProxyConnectException) {
          context.response.status(INTERNAL_SERVER_ERROR).send()
        } then { ReceivedResponse receivedResponse ->
          response.status(receivedResponse.status)
          response.send(receivedResponse.body.bytes)
        }
      }
    }

    then:
    def response = client.get()
    response.statusCode == 200
    response.body.text == "bar"
    proxyServer.proxied(otherApp.address)

    where:
    pooled << [true, false]
  }

  def "can make simple proxy request using request-level proxy settings (pooled: #pooled)"() {
    given:
    proxyServer.start()

    when:
    bindings {
      bindInstance(HttpClient, HttpClient.of { config -> config
        .poolSize(pooled ? 8 : 0)
      })
    }
    handlers {
      get { HttpClient httpClient ->
        httpClient.get(otherAppUrl("foo")) { requestSpec ->
          requestSpec.proxy { p -> p
            .host(proxyServer.address.hostName)
            .port(proxyServer.address.port)
          }
        } then { ReceivedResponse response ->
          render response.body.text
        }
      }
    }

    then:
    text == "bar"
    proxyServer.proxied(otherApp.address)

    where:
    pooled << [true, false]
  }

  def "can make proxy request with proxy authentication using request-level proxy settings (pooled: #pooled)"() {
    given:
    def username = "testUser"
    def password = "testPassword"
    proxyServer.start(username, password)

    when:
    bindings {
      bindInstance(HttpClient, HttpClient.of { config -> config
        .poolSize(pooled ? 8 : 0)
      })
    }
    handlers {
      get { HttpClient httpClient ->
        httpClient.get(otherAppUrl("foo")) { requestSpec ->
          requestSpec.proxy { p -> p
            .host(proxyServer.address.hostName)
            .port(proxyServer.address.port)
            .credentials(username, password)
          }
        } onError(HttpProxyHandler.HttpProxyConnectException) {
          context.response.status(INTERNAL_SERVER_ERROR).send()
        } then { ReceivedResponse receivedResponse ->
          response.status(receivedResponse.status)
          response.send(receivedResponse.body.bytes)
        }
      }
    }

    then:
    def response = client.get()
    response.statusCode == 200
    response.body.text == "bar"
    proxyServer.proxied(otherApp.address)

    where:
    pooled << [true, false]
  }

  def "can make simple proxy request when the proxy server requires SSL (pooled: #pooled)"() {
    given:
    proxyServer.start(true)
    SslContext clientContext = SslContextBuilder.forClient()
      .trustManager(proxyServer.sslCertificate)
      .build()

    when:
    bindings {
      bindInstance(HttpClient, HttpClient.of { config -> config
        .poolSize(pooled ? 8 : 0)
        .proxy { p -> p
          .protocol(Proxy.ProxyProtocol.HTTPS)
          .host(proxyServer.address.hostName)
          .port(proxyServer.address.port)
        }
      })
    }
    handlers {
      get { HttpClient httpClient ->
        httpClient.get(otherAppUrl("foo")) {
          it.sslContext(clientContext)
        } then { ReceivedResponse response ->
          render response.body.text
        }
      }
    }

    then:
    text == "bar"
    proxyServer.proxied(otherApp.address)

    where:
    pooled << [true, false]
  }

}
