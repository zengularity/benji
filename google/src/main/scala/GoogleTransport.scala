package com.zengularity.benji.google

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import scala.collection.JavaConverters._

import java.net.URI

import akka.stream.Materializer

import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.{ BodyWritable, StandaloneWSRequest }
import play.shaded.ahc.io.netty.handler.codec.http.QueryStringDecoder

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.services.storage.Storage

import com.zengularity.benji.{ StoragePack, URIProvider }

/**
 * @define wsParam the WS client
 *
 * @param credential the Google Cloud Storage credential
 * @param projectId the ID of the Google project authorized for Cloud Storage
 * @param builder the Google builder
 * @param ws $wsParam
 * @param baseRestUrl the base URL for the Google REST API (without the final `/`)
 * @param servicePath the Google service path (without the final `/`; e.g. storage/v1)
 * @param requestTimeout the optional timeout for the prepared requests
 *
 */
final class GoogleTransport(
  credential: => GoogleCredential,
  val projectId: String,
  builder: GoogleCredential => Storage,
  ws: StandaloneAhcWSClient,
  baseRestUrl: String,
  servicePath: String,
  requestTimeout: Option[Long] = None) {
  import scala.concurrent.duration._

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  private lazy val cred = credential

  lazy val client = builder(cred)

  @inline def buckets() = client.buckets

  /**
   * Returns a new transport instance for the specified project.
   *
   * @param id the ID of the Google project
   */
  def withProjectId(id: String) =
    new GoogleTransport(cred, id, builder, ws, servicePath, baseRestUrl)

  private[google] def accessToken()(implicit ec: ExecutionContext): Future[String] = {
    if (cred.getAccessToken != null &&
      // TODO: Use requestTimeout?
      Option(cred.getExpiresInSeconds).fold(Int.MaxValue)(_.toInt) > 2) {

      logger.trace("Google Access Token acquired")
      Future.successful(cred.getAccessToken)
    } else Future {
      cred.refreshToken()
      cred.getAccessToken()
    }.flatMap {
      case null => Future.failed(new scala.RuntimeException(
        s"fails to get access token: $projectId"))

      case token =>
        logger.trace("Google Access Token refreshed")
        Future.successful(token)
    }
  }

  /**
   * @param service the service name (e.g. `upload`)
   * @param path a path (after the base REST URL)
   */
  private[google] def withWSRequest1[T](service: String, path: String)(f: StandaloneWSRequest => Future[T])(implicit m: Materializer): Future[T] = withWSRequest2(s"$baseRestUrl/$service/$servicePath$path") { req =>
    f(req.addHttpHeaders("Content-Type" -> "application/json; charset=UTF-8"))
  }

  /**
   * @param url the full URL to be requested
   */
  private[google] def withWSRequest2[T](url: String)(f: StandaloneWSRequest => Future[T])(implicit m: Materializer): Future[T] = {
    implicit def ec: ExecutionContext = m.executionContext

    accessToken.flatMap { token =>
      logger.trace(s"Prepare WS request: $url")

      def req = ws.url(url).addHttpHeaders("Authorization" -> s"Bearer $token")

      f(requestTimeout.fold(req) { t =>
        req.withRequestTimeout(t.milliseconds)
      })
    }
  }
}

/** Google transport factory. */
object GoogleTransport {
  import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
  import com.google.api.client.json.jackson2.JacksonFactory
  import com.google.api.services.storage.StorageScopes

  @inline private def stripSlash(str: String): String =
    if (str endsWith "/") str.dropRight(1) else str

  /**
   * @param credential the Google Cloud Storage credential
   * @param projectId the ID of the Google project authorized for Cloud Storage
   * @param application the name of the current application
   * @param http the HTTP transport to access Google Cloud
   * @param json the JSON factory to serialize/deserialize messages
   * @param baseRestUrl the base URL for the Google REST API (if there is a final `/`, it will be removed)
   * @param servicePath the Google service path (if there is a final `/`, it will be removed; e.g. storage/v1)
   * @param ws the WS client
   *
   * {{{
   * import java.io.FileInputStream
   * import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
   *
   * def jsonResource = new FileInputStream("/path/to/keys.json")
   * val credential = GoogleCredential.fromStream(jsonResource)
   * implicit val googleTransport = GoogleTransport(credential, "foo")
   * }}}
   */
  def apply(credential: GoogleCredential, projectId: String, application: String, http: HttpTransport = GoogleNetHttpTransport.newTrustedTransport(), json: JsonFactory = new JacksonFactory(), baseRestUrl: String = Storage.DEFAULT_ROOT_URL, servicePath: String = Storage.DEFAULT_SERVICE_PATH)(implicit ws: StandaloneAhcWSClient): GoogleTransport = {
    val build = new Storage.Builder(http, json, _: GoogleCredential).
      setApplicationName(application).build()

    new GoogleTransport({
      if (!credential.createScopedRequired) credential else {
        val scopes = StorageScopes.all()
        credential.createScoped(scopes)
      }
    }, projectId, build, ws, stripSlash(baseRestUrl), stripSlash(servicePath))
  }

  /**
   * Tries to create a GoogleTransport from an URI using the following format:
   * google:http://accessKey:secretKey@s3.amazonaws.com/?style=[virtualHost|path]
   *
   * {{{
   *   GoogleTransport("google:http://accessKey:secretKey@s3.amazonaws.com/?style=virtualHost")
   *   // or
   *   GoogleTransport(new java.net.URI("google:https://accessKey:secretKey@s3.amazonaws.com/?style=path"))
   * }}}
   *
   * @param config the config element used by the provider to generate the URI
   * @param provider a typeclass that try to generate an URI from the config element
   * @tparam T the config type to be consumed by the provider typeclass
   * @return Success if the GoogleTransport was properly created, otherwise Failure
   */
  def apply[T](config: T)(implicit provider: URIProvider[T], ws: StandaloneAhcWSClient): Try[GoogleTransport] =
    provider(config).map { builtUri =>
      if (builtUri == null) {
        throw new IllegalArgumentException("URI provider returned a null URI")
      }

      // URI object fails to parse properly with scheme like "google:http"
      // So we check for "google" scheme and then recreate an URI without it
      if (builtUri.getScheme != "google") {
        throw new IllegalArgumentException("Expected URI with scheme containing \"google:\"")
      }

      val uri = new URI(builtUri.getSchemeSpecificPart)

      val scheme = uri.getScheme

      val credentialStream = scheme match {
        case "classpath" => getClass.getResourceAsStream("/" + uri.getHost + uri.getPath)
        case _ => uri.toURL.openStream()
      }

      val credential = GoogleCredential.fromStream(credentialStream)

      val params = parseQuery(uri)

      val projectId = params.get("projectId") match {
        case Some(Seq(s)) => s
        case Some(_) => throw new IllegalArgumentException("Expected exactly one value for \"projectId\" parameter")
        case None => throw new IllegalArgumentException("Expected URI containing \"projectId\" parameter")
      }

      val application = params.get("application") match {
        case Some(Seq(s)) => s
        case Some(_) => throw new IllegalArgumentException("Expected exactly one value for \"application\" parameter")
        case None => throw new IllegalArgumentException("Expected URI containing \"application\" parameter")
      }

      GoogleTransport(credential, projectId, application)
    }

  private def parseQuery(uri: URI): Map[String, Seq[String]] =
    new QueryStringDecoder(uri.toString).parameters.asScala.mapValues(_.asScala).toMap
}

object GoogleStoragePack extends StoragePack {
  type Transport = GoogleTransport
  type Writer[T] = BodyWritable[T]
}
