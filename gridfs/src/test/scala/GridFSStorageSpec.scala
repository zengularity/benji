/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package tests.benji.gridfs

import akka.stream.Materializer

import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.AfterAll

final class GridFSStorageSpec(
    implicit
    @SuppressWarnings(Array("org.wartremover.warts.UnusedMethodParameter"))
    ee: ExecutionEnv)
    extends org.specs2.mutable.Specification
    with AfterAll {

  locally { val _ = ee }

  "GridFS Cloud Storage".title

  sequential

  implicit def materializer: Materializer = TestUtils.materializer

  "GridFS client" should {
    "initialize storage" in {
      val storage = TestUtils.gridfs

      storage must not be null
    }

    "list buckets" in {
      val storage = TestUtils.gridfs
      // Just call buckets to ensure no runtime error
      val bucketStream = storage.buckets()

      bucketStream must not be null
    }
  }

  def afterAll(): Unit = TestUtils.close()
}
