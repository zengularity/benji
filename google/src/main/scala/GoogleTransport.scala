/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import java.net.URI

import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._

import akka.NotUsed

import akka.stream.{ Materializer, OverflowStrategy, QueueOfferResult }
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source, SourceQueueWithComplete }

import play.api.libs.ws.StandaloneWSRequest
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.shaded.ahc.io.netty.handler.codec.http.QueryStringDecoder

import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.services.storage.{ model, Storage }
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials

import com.zengularity.benji.{ Compat, URIProvider }
import com.zengularity.benji.exception.BenjiUnknownError

import Compat.javaConverters._

/**
 * Benji transport for Google Cloud Storage
 * (also using Play WS for direct REST call).
 *
 * @param credential the Google Cloud Storage credential
 * @param projectId the ID of the Google project authorized for Cloud Storage
 * @param builder the Google builder
 * @param ws the WS client
 * @param baseRestUrl the base URL for the Google REST API (without the final `/`)
 * @param servicePath the Google service path (without the final `/`; e.g. storage/v1)
 * @param requestTimeout the optional timeout for the prepared requests
 * @param disableGZip if true, disables the GZip compression for upload and download (default: false)
 */
final class GoogleTransport(
  credential: => GoogleCredentials,
  val projectId: String,
  builder: GoogleCredentials => Storage,
  ws: StandaloneAhcWSClient,
  baseRestUrl: String,
  servicePath: String,
  val requestTimeout: Option[Long] = None,
  val disableGZip: Boolean = false) {

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  private lazy val cred = credential

  private[google] lazy val client: Storage = builder(cred)

  @inline private[google] def buckets() = client.buckets

  /**
   * Returns a new transport instance for the specified project.
   *
   * @param id the ID of the Google project
   */
  private[google] def withProjectId(id: String) =
    new GoogleTransport(cred, id, builder, ws, servicePath, baseRestUrl)

  private[google] def accessToken()(implicit ec: ExecutionContext): Future[String] = {
    val accessTok = cred.getAccessToken()

    if (accessTok != null && {
      // TODO: Use requestTimeout?
      val expiration = Option(accessTok.getExpirationTime).map(_.getTime)

      expiration.forall(_ > (System.currentTimeMillis() + 2000L /*2s*/ ))
    }) {
      logger.trace("Google Access Token acquired")
      Future.successful(accessTok.getTokenValue)
    } else Future {
      cred.refresh()

      Option(cred.getAccessToken().getTokenValue)
    }.flatMap {
      case Some(token) => {
        logger.trace(s"Google Access Token refreshed: $token")
        Future.successful(token)
      }

      case _ => Future.failed[String](new scala.RuntimeException(
        s"fails to get access token: $projectId"))

    }
  }

  /**
   * @param service the service name (e.g. `upload`)
   * @param path a path (after the base REST URL)
   */
  private[google] def withWSRequest1[T](service: String, path: String)(f: StandaloneWSRequest => Future[T])(implicit ec: ExecutionContext): Future[T] = withWSRequest2(s"$baseRestUrl$service/$servicePath$path") { req =>
    f(req.addHttpHeaders("Content-Type" -> "application/json; charset=UTF-8"))
  }

  /**
   * @param url the full URL to be requested
   */
  private[google] def withWSRequest2[T](url: String)(f: StandaloneWSRequest => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    accessToken().flatMap { token =>
      logger.trace(s"Prepare WS request: $url")

      def req = ws.url(url).addHttpHeaders("Authorization" -> s"Bearer $token").
        withFollowRedirects(false) // https://github.com/AsyncHttpClient/async-http-client/issues/1628

      f(requestTimeout.fold(req) { t =>
        req.withRequestTimeout(t.milliseconds)
      })
    }
  }

  /**
   * Returns a transport managing the specified timeout on the requests.
   *
   * @param timeout the request timeout
   */
  def withRequestTimeout(requestTimeout: Long): GoogleTransport =
    new GoogleTransport(credential, projectId, builder, ws,
      baseRestUrl, servicePath, Some(requestTimeout), disableGZip)

  /**
   * Returns a transport managing the specified compression on the requests.
   *
   * @param disableGZip disable or not the GZip compression
   */
  def withDisableGZip(disableGZip: Boolean): GoogleTransport =
    new GoogleTransport(credential, projectId, builder, ws,
      baseRestUrl, servicePath, requestTimeout, disableGZip)

  // Bucket operations
  import GoogleTransport.{ BucketOp, CreateBucket, DeleteBucket }

  /*
   * [[https://cloud.google.com/storage/quotas 1 operation per 2-second limit]]
   */
  private val bucketOpFlow = {
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    @volatile var errors = 0L // successive errors

    def execute(op: BucketOp)(f: => Unit): Source[Unit, NotUsed] =
      Source.single[Unit](try {
        f

        op.result.success({})

        errors = 0L // reset internal error counter
      } catch {
        case NonFatal(cause) =>
          op.result.failure(cause)
          errors += 1L
      }).initialDelay(250.milliseconds * errors)

    Flow[BucketOp].throttle(
      elements = 1,
      per = 2.seconds, // hardcoded Google rate limit
      maximumBurst = 0,
      mode = akka.stream.ThrottleMode.Shaping).flatMapConcat {
      case c @ CreateBucket(name) => execute(c) {
        val nb = new model.Bucket()
        nb.setName(name)

        client.buckets().insert(projectId, nb).execute()

        ()
      }

      case d @ DeleteBucket(name) => execute(d) {
        client.buckets().delete(name).execute()

        ()
      }
    }
  }

  private[google] type Q = SourceQueueWithComplete[BucketOp]
  private[google] val queue: Q = {
    import GoogleTransport.adminMaterializer

    Source.queue[BucketOp](1024, OverflowStrategy.backpressure).
      viaMat(bucketOpFlow)(Keep.left[Q, NotUsed]).
      to(Sink.ignore).run()

  }

  private[google] def executeBucketOp(op: BucketOp)(
    implicit
    ec: ExecutionContext): Future[Unit] = {
    logger.debug(s"Enqueue bucket operation: ${op.show}")

    queue.offer(op).flatMap {
      case QueueOfferResult.Failure(cause) =>
        Future.failed[Unit](cause)

      case QueueOfferResult.Dropped =>
        Future.failed[Unit](new BenjiUnknownError(
          s"Fails to enqueue bucket operation: ${op.show}"))

      case QueueOfferResult.QueueClosed =>
        Future.failed[Unit](new BenjiUnknownError(
          s"Bucket operation queue already closed: ${op.show}"))

      case _ =>
        op.result.future
    }
  }
}

/** Google transport factory. */
object GoogleTransport {
  import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
  import com.google.api.client.json.jackson2.JacksonFactory
  import com.google.api.services.storage.StorageScopes
  import com.zengularity.benji.LongVal

  @inline private def stripSlash(str: String): String =
    if (str endsWith "/") str.dropRight(1) else str

  /**
   * Creates a transport instance.
   *
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
   * import play.api.libs.ws.ahc.StandaloneAhcWSClient
   * import com.google.auth.oauth2.GoogleCredentials
   * import com.zengularity.benji.google.GoogleTransport
   *
   * def jsonResource = new FileInputStream("/path/to/keys.json")
   * val credential = GoogleCredentials.fromStream(jsonResource)
   *
   * def init(implicit wc: StandaloneAhcWSClient) = GoogleTransport(
   *   credential = credential,
   *   projectId = "foo",
   *   application = "appId")
   * }}}
   */
  def apply(credential: GoogleCredentials, projectId: String, application: String, http: HttpTransport = GoogleNetHttpTransport.newTrustedTransport(), json: JsonFactory = new JacksonFactory(), baseRestUrl: String = Storage.DEFAULT_ROOT_URL, servicePath: String = Storage.DEFAULT_SERVICE_PATH)(implicit ws: StandaloneAhcWSClient): GoogleTransport = {
    val build = { (creds: GoogleCredentials) =>
      new Storage.Builder(http, json, new HttpCredentialsAdapter(creds)).
        setApplicationName(application).build()
    }

    new GoogleTransport({
      if (!credential.createScopedRequired) credential else {
        val scopes = StorageScopes.all()
        credential.createScoped(scopes)
      }
    }, projectId, build, ws, stripSlash(baseRestUrl), stripSlash(servicePath))
  }

  /**
   * Tries to create a GoogleTransport from an URI using the following format:
   * `google:http://accessKey:secretKey@s3.amazonaws.com/?style=[virtualHost|path]`
   *
   * {{{
   * import play.api.libs.ws.ahc.StandaloneAhcWSClient
   * import com.zengularity.benji.google.GoogleTransport
   *
   * def init1(implicit wc: StandaloneAhcWSClient) =
   *   GoogleTransport("google:http://accessKey:secretKey@s3.amazonaws.com/?style=virtualHost")
   *
   * // -- or --
   *
   * def init2(implicit wc: StandaloneAhcWSClient) =
   *   GoogleTransport(new java.net.URI("google:https://accessKey:secretKey@s3.amazonaws.com/?style=path"))
   * }}}
   *
   * @param config the config element used by the provider to generate the URI
   * @param provider a typeclass that try to generate an URI from the config element
   * @tparam T the config type to be consumed by the provider typeclass
   * @return Success if the GoogleTransport was properly created, otherwise Failure
   */
  def apply[T](config: T)(implicit provider: URIProvider[T], ws: StandaloneAhcWSClient): Try[GoogleTransport] = {
    def optParam(ps: Map[String, Seq[String]], key: String): Try[Option[String]] = ps.get(key) match {
      case Some(Seq(s)) => Success(Some(s))

      case Some(Seq()) => Success(Option.empty[String])

      case Some(_) => Failure[Option[String]](new IllegalArgumentException(
        s"""Expected exactly one value for "$key" parameter"""))

      case _ => Success(Option.empty[String])
    }

    def singleParam(ps: Map[String, Seq[String]], key: String): Try[String] =
      optParam(ps, key).flatMap {
        case Some(required) => Success(required)

        case _ => Failure[String](new IllegalArgumentException(
          s"Missing parameter in URI: $key"))
      }

    provider(config).flatMap { builtUri =>
      if (builtUri == null) {
        Failure[GoogleTransport](new IllegalArgumentException("URI provider returned a null URI"))
      } else if (builtUri.getScheme != "google") {
        // URI object fails to parse properly with scheme like "google:http"
        // So we check for "google" scheme and then recreate an URI without it

        Failure[GoogleTransport](new IllegalArgumentException("Expected URI with scheme containing \"google:\""))
      } else {
        val uri = new URI(builtUri.getSchemeSpecificPart)
        val scheme = uri.getScheme

        val credentialStream = scheme match {
          case "classpath" =>
            getClass.getResourceAsStream("/" + uri.getHost + uri.getPath)

          case _ => uri.toURL.openStream()
        }

        val credential = GoogleCredentials.fromStream(credentialStream)
        val params = parseQuery(uri)

        for {
          projectId <- singleParam(params, "projectId")
          application <- singleParam(params, "application")

          reqTimeout <- optParam(params, "requestTimeout").flatMap {
            case Some(LongVal(l)) => Success(Some(l))

            case Some(v) => Failure[Option[Long]](
              new IllegalArgumentException(
                s"Invalid 'requestTimeout' parameter: $v"))

            case _ => Success(Option.empty[Long])
          }

          disableGz <- optParam(params, "disableGZip").flatMap {
            case Some(BoolVal(b)) => Success(Some(b))

            case Some(v) => Failure[Option[Boolean]](
              new IllegalArgumentException(
                s"Invalid 'disableGZip' parameter: $v"))

            case _ => Success(Option.empty[Boolean])
          }

          tx1 = GoogleTransport(credential, projectId, application)
          tx2 = reqTimeout.fold(tx1)(tx1.withRequestTimeout(_))
        } yield disableGz.fold(tx2)(tx2.withDisableGZip(_))
      }
    }
  }

  private def parseQuery(uri: URI): Map[String, Seq[String]] =
    Compat.mapValues(new QueryStringDecoder(uri.toString).
      parameters.asScala.toMap)(_.asScala.toSeq)

  private object BoolVal {
    def unapply(value: String): Option[Boolean] = try {
      def bool = value.toBoolean
      Some(bool)
    } catch {
      case NonFatal(_) => Option.empty[Boolean]
    }
  }

  // ---

  @com.github.ghik.silencer.silent
  private[google] implicit lazy val adminMaterializer: Materializer = {
    val adminSystem = akka.actor.ActorSystem()

    akka.stream.ActorMaterializer.create(adminSystem)
  }

  private[google] sealed trait BucketOp {
    val result = Promise[Unit]()
    def show: String
  }

  private[google] final case class CreateBucket(name: String) extends BucketOp {
    val show = s"create($name)"
  }

  private[google] final case class DeleteBucket(name: String) extends BucketOp {
    val show = s"delete($name)"
  }
}
