package com.zengularity.benji.s3

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.xml.Node

import com.zengularity.benji.{ Bytes, Object, VersionedObject }

// !! UNSAFE - Can raise exceptions
private[s3] object Xml {
  def objectFromXml(content: Node): Object =
    Object(
      name = (content \ "Key").text,
      size = size(content),
      lastModifiedAt = lastModified(content))

  def versionDecoder(content: Node): VersionedObject =
    VersionedObject(
      name = (content \ "Key").text,
      size = size(content),
      versionCreatedAt = lastModified(content),
      versionId = (content \ "VersionId").text,
      isDeleteMarker = false)

  def deleteMarkerDecoder(content: Node): VersionedObject =
    VersionedObject(
      name = (content \ "Key").text,
      size = Bytes.zero,
      versionCreatedAt = lastModified(content),
      versionId = (content \ "VersionId").text,
      isDeleteMarker = false)

  // ---

  @inline private def size(content: Node): Bytes =
    Bytes((content \ "Size").text.toLong)

  @inline private def lastModified(content: Node): LocalDateTime =
    LocalDateTime.parse(
      (content \ "LastModified").text,
      DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
