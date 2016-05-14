package com.zengularity.google

import scala.concurrent.{ ExecutionContext, Future }

import play.api.libs.ws.{ WSClient, WSRequest }

import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential

import com.google.api.services.storage.Storage

import com.zengularity.storage.StoragePack

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
    ws: WSClient,
    baseRestUrl: String,
    servicePath: String,
    requestTimeout: Option[Long] = None
) {
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
        s"fails to get access token: $projectId"
      ))

      case token =>
        logger.trace("Google Access Token refreshed")
        Future.successful(token)
    }
  }

  /**
   * @param service the service name (e.g. `upload`)
   * @param path a path (after the base REST URL)
   */
  private[google] def withWSRequest1[T](service: String, path: String)(f: WSRequest => Future[T])(implicit ec: ExecutionContext): Future[T] = withWSRequest2(s"$baseRestUrl/$service/$servicePath$path") { req =>
    f(req.withHeaders("Content-Type" -> "application/json; charset=UTF-8"))
  }

  /**
   * @param url the full URL to be requested
   */
  private[google] def withWSRequest2[T](url: String)(f: WSRequest => Future[T])(implicit ec: ExecutionContext): Future[T] = accessToken.flatMap { token =>
    logger.trace(s"Prepare WS request: $url")

    def req = ws.url(url).withHeaders("Authorization" -> s"Bearer $token")

    f(requestTimeout.fold(req)(req.withRequestTimeout))
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
  def apply(credential: GoogleCredential, projectId: String, application: String, http: HttpTransport = GoogleNetHttpTransport.newTrustedTransport(), json: JsonFactory = new JacksonFactory(), baseRestUrl: String = Storage.DEFAULT_ROOT_URL, servicePath: String = Storage.DEFAULT_SERVICE_PATH)(implicit ws: WSClient): GoogleTransport = {
    val build = new Storage.Builder(http, json, _: GoogleCredential).
      setApplicationName(application).build()

    new GoogleTransport({
      if (!credential.createScopedRequired) credential else {
        val scopes = StorageScopes.all()
        credential.createScoped(scopes)
      }
    }, projectId, build, ws, stripSlash(baseRestUrl), stripSlash(servicePath))
  }
}

object GoogleStoragePack extends StoragePack {
  type Transport = GoogleTransport
  type Writer[T] = play.api.http.Writeable[T]
}
