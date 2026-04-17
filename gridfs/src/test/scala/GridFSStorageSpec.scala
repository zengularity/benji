/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package tests.benji.gridfs

import scala.concurrent.duration._

import akka.stream.Materializer

import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.AfterAll

final class GridFSStorageSpec(
    implicit
    ee: ExecutionEnv)
    extends org.specs2.mutable.Specification
    with AfterAll {

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

  def afterAll(): Unit = {
    TestUtils.close()
  }
}
