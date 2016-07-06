package com.zengularity.google

import scala.concurrent.{ ExecutionContext, Future }

import play.utils.UriEncoding.{ encodePathSegment => encPathSeg }

import play.api.libs.iteratee.{ Enumerator, Enumeratee, Input, Iteratee }
import play.api.libs.ws.WSResponse

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.storage.model.StorageObject

import com.zengularity.ws.{ ContentMD5, Ok, Successful }
import com.zengularity.storage.{
  Bytes,
  Chunk,
  ByteRange,
  ObjectRef,
  Streams
}, Chunk.{ Last, NonEmpty }

final class GoogleObjectRef private[google] (
    val storage: GoogleStorage,
    val bucket: String,
    val name: String
) extends ObjectRef[GoogleStorage] { ref =>
  import GoogleObjectRef.ResumeIncomplete

  @inline def defaultThreshold = GoogleObjectRef.defaultThreshold

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  def exists(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Boolean] = Future {
    gt.client.objects().get(bucket, name).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err                  => Future.failed[Boolean](err)
  }

  val get = new GoogleGetRequest()

  final class GoogleGetRequest private[google] () extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit ec: ExecutionContext, gt: GoogleTransport): Enumerator[Array[Byte]] = Enumerator.flatten {
      Future {
        val req = gt.client.objects().get(bucket, name)

        req.setRequestHeaders {
          val headers = new com.google.api.client.http.HttpHeaders()
          headers.setRange(range.
            map(r => s"bytes=${r.start}-${r.end}").getOrElse(null))
          headers
        }

        req.setDisableGZipContent(storage.disableGZip)

        req.executeMediaAsInputStream()
      }.map(Enumerator.fromStream(_)).recoverWith {
        case HttpResponse(status, msg) =>
          Future.failed[Enumerator[Array[Byte]]](new IllegalStateException(s"Could not get the contents of the object $name in the bucket $bucket. Response: $status - $msg"))

        case cause =>
          Future.failed[Enumerator[Array[Byte]]](cause)
      }
    }
  }

  /**
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload
   */
  final class RESTPutRequest[E, A] private[google] ()
      extends ref.PutRequest[E, A] {

    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None)(f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, tr: Transport, w: Writer[E]): Iteratee[E, A] = {
      val th = threshold

      w.toEnumeratee &>> Streams.consumeAtMost(th).flatMapM {
        case Last(Some(bytes)) => {
          // This one also needs to be fed Input.EOF to finish the upload,
          // we know that we've received Input.EOF as otherwise the threshold
          // would have been exceeded (or we wouldn't be in this function).
          for {
            a <- Future.successful(putSimple(w.contentType, z, f))
            b <- a.feed(Input.El(bytes))
            c <- b.feed(Input.EOF)
          } yield c
        }

        case NonEmpty(bytes) if (th wasExceeded bytes) => {
          // Knowing that we have at least one chunk that
          // is bigger than threshold, it's safe to start
          // a multi-part upload with everything we consumed so far.
          Future.successful(putMulti(bytes, w.contentType, threshold, z, f))
        }

        case _ =>
          Future.failed[Iteratee[Array[Byte], A]](
            new IllegalStateException("cannot upload an empty source")
          )
      }
    }
  }

  def put[E, A] = new RESTPutRequest[E, A]()

  def delete(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Unit] =
    delete(ignoreMissing = false)

  private def delete(ignoreMissing: Boolean)(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Unit] = exists.flatMap {
    case true                 => Future { gt.client.objects().delete(bucket, name).execute() }

    case _ if (ignoreMissing) => Future.successful({})

    case _ => Future.failed[Unit](new IllegalArgumentException(
      s"Could not delete $bucket/$name: doesn't exist"
    ))
  }

  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Unit] = {
    val targetObj = storage.bucket(targetBucketName).obj(targetObjectName)

    for {
      _ <- {
        if (!preventOverwrite) Future.successful({})
        else targetObj.exists.flatMap {
          case true => Future.failed[Unit](new IllegalStateException(
            s"Could not move $bucket/$name: target $targetBucketName/$targetObjectName already exists"
          ))

          case _ => Future.successful({})
        }
      }
      _ <- Future {
        val col = gt.client.objects()
        //def obj = col.get(bucket, name)

        col.copy(bucket, name,
          targetBucketName, targetObjectName, null).execute()

      }.recoverWith {
        case reason =>
          reason.printStackTrace()

          targetObj.delete(ignoreMissing = true).filter(_ => false).
            recoverWith { case _ => Future.failed[Unit](reason) }
      }
      _ <- delete(ignoreMissing = false /* the previous reference */ )
    } yield ()
  }

  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Unit] = Future {
    gt.client.objects().
      copy(bucket, name, targetBucketName, targetObjectName, null).execute()
  }.map(_ => {})

  // Utility methods

  /**
   * Creates an Iteratee that will upload the bytes it consumes in one request,
   * without streaming them.
   *
   * For this operation we need to know the overall content length
   * (the server requires that), which is why we have to buffer everything upfront.
   *
   * If you already know that your upload will exceed the threshold,
   * then use multi-part uploads.
   *
   * @param contentType $contentTypeParam
   *
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#simple
   */
  private def putSimple[A](contentType: Option[String], z: => A, f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, gt: GoogleTransport): Iteratee[Array[Byte], A] = Iteratee.consume[Array[Byte]]().mapM { bytes =>
    lazy val typ = contentType getOrElse "application/octet-stream"
    def content = new ByteArrayContent(typ, bytes)
    def obj = {
      val so = new StorageObject()

      so.setBucket(bucket)
      so.setName(name)
      so.setContentType(typ)
      so.setSize(new java.math.BigInteger(bytes.size.toString))

      so
    }

    Future {
      val req = gt.client.objects().insert(bucket, obj, content)

      req.setDisableGZipContent(storage.disableGZip)

      req.execute()
    }.flatMap(_ => f(z, bytes))
  }

  /**
   * Creates an Iteratee that will upload the bytes.
   * It consumes in multi-part uploads.
   *
   * @param firstChunk the data of the first chunk (already consumed)
   * @param contentType $contentTypeParam
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#multipart
   */
  private def putMulti[A](firstChunk: Array[Byte], contentType: Option[String], threshold: Bytes, z: => A, f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, gt: GoogleTransport): Iteratee[Array[Byte], A] = {
    Enumeratee.grouped(Streams.consumeAtMost(threshold)) &>>
      Iteratee.flatten(initiateUpload(contentType).map { url =>
        Iteratee.foldM[Chunk, (Long, Array[Byte], A)]((0L, firstChunk, z)) {
          case ((offset, prev, st), chunk) =>
            uploadPart(url, prev, offset, contentType).flatMap { _ =>
              chunk match {
                case Last(Some(bytes)) => f(st, prev).flatMap { tmp =>
                  val off = offset + prev.size
                  val sz = off + bytes.size.toLong

                  for {
                    _ <- uploadPart(url, bytes, off, contentType, Some(sz))
                    nst <- f(tmp, bytes)
                  } yield (sz, Array.empty[Byte], nst)
                }

                case NonEmpty(bytes) => f(st, prev).map { nst =>
                  ((offset + prev.size), bytes, nst)
                }

                case _ => f(st, prev).map { (offset, Array.empty[Byte], _) }
              }
            }
        }.mapM[A] {
          case (_, _, res) => Future.successful(res)
        }
      })
  }

  /**
   * Initiates a multi-part upload and returns the upload URL
   * (include the upload ID).
   *
   * @param contentType $contentTypeParam
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#start-resumable
   */
  private def initiateUpload(contentType: Option[String])(implicit ec: ExecutionContext, gt: GoogleTransport): Future[String] = gt.withWSRequest1(
    "upload", s"/b/${encPathSeg(bucket, "UTF-8")}/o"
  ) { req =>
      contentType.fold(req) { typ =>
        req.withHeaders("Content-Type" -> "0", "X-Upload-Content-Type" -> typ)
      }.withQueryString("uploadType" -> "resumable", "name" -> name).
        post(Array.empty[Byte]).flatMap {
          case Successful(response) => response.header("Location") match {
            case Some(url) => Future.successful {
              logger.debug(s"Initiated a resumable upload for $bucket/$name: $url")
              url
            }
            case _ => Future.failed[String](new scala.RuntimeException(s"missing upload URL: ${response.status} - ${response.statusText}: ${response.allHeaders}"))
          }
          case failed => Future.failed[String] {
            val msg = s"Could not initiate the upload for [$bucket/$name]. Response: ${failed.status} - ${failed.statusText}"

            logger.debug(s"$msg\r\b${failed.body}")

            new IllegalStateException(msg)
          }
        }
    }

  /**
   * Uploads a part in a resumable upload.
   * @define offsetParam the offset of the bytes in the global content
   *
   * @param url the URL previously initiated for the resumable uplaod
   * @param bytes the bytes for the part content
   * @param offset $offsetParam
   * @param contentType $contentTypeParam
   * @param globalSz the global size (if known)
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#uploading_the_file_in_chunks
   */
  private def uploadPart(url: String, bytes: Array[Byte], offset: Long, contentType: Option[String], globalSz: Option[Long] = None)(implicit ec: ExecutionContext, gt: GoogleTransport): Future[String] = gt.withWSRequest2(url) { req =>
    val reqRange =
      s"bytes $offset-${offset + bytes.size - 1}/${globalSz getOrElse "*"}"

    val uploadReq = req.withHeaders(
      "Content-Length" -> bytes.size.toString,
      "Content-Range" -> reqRange
    )

    logger.debug(s"Prepare upload part: $reqRange; size = ${bytes.size}")

    contentType.fold(uploadReq) { typ =>
      uploadReq.withHeaders("Content-Type" -> typ)
    }.withHeaders("Content-MD5" -> ContentMD5(bytes)).put(bytes).flatMap {
      case Ok(response) =>
        Future.successful(reqRange)

      case ResumeIncomplete(response) =>
        partResponse(response, offset, bytes.size, url)

      case Successful(response) =>
        partResponse(response, offset, bytes.size, url)

      case response =>
        throw new IllegalStateException(s"Could not upload a part for [$bucket/$name, $url, range: $reqRange]. Response: ${response.status} - ${response.statusText}; ${response.body}")
    }
  }

  /**
   * @param response the WS response from a part upload
   * @param offset the offset of the bytes in the global content
   * @param sz the current upload size
   * @param url the request URL of the given response
   * @return the uploaded range if successful
   */
  @inline private def partResponse(response: WSResponse, offset: Long, sz: Int, url: String): Future[String] = response.header("Range") match {
    case Some(range) => Future.successful {
      logger.trace(s"Uploaded part @$offset with $sz bytes: $url")
      range
    }

    case _ => Future.failed[String](new scala.RuntimeException(s"missing upload range: ${response.status} - ${response.statusText}: ${response.allHeaders}"))
  }
}

object GoogleObjectRef {
  /**
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#uploading_the_file_in_chunks
   */
  val defaultThreshold = Bytes.kilobytes(256)

  private[google] object ResumeIncomplete {
    def unapply(response: WSResponse): Option[WSResponse] =
      if (response.status == 308) Some(response) else None
  }
}
