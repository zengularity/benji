package com.zengularity.benji.demo.forms

import play.api.data.Forms._
import play.api.data._

object BenjiForm {

  val createBucketForm = Form(
    mapping("checkBefore" -> default(boolean, true))(CreateBucketForm.apply)(
      CreateBucketForm.unapply
    )
  )

  val deleteBucketForm = Form(
    mapping(
      "ignore" -> default(boolean, true),
      "recursive" -> default(boolean, false)
    )(DeleteBucketForm.apply)(DeleteBucketForm.unapply)
  )

  val listObjectForm = Form(
    mapping("bacthSize" -> optional(longNumber))(ListObjectForm.apply)(
      ListObjectForm.unapply
    )
  )

  val deleteObjectForm = Form(
    mapping("ignore" -> default(boolean, true))(DeleteObjectForm.apply)(
      DeleteObjectForm.unapply
    )
  )
}

case class CreateBucketForm(checkBefore: Boolean)

case class DeleteBucketForm(ignore: Boolean, recursive: Boolean)

case class ListObjectForm(batchSize: Option[Long])

case class DeleteObjectForm(ignore: Boolean)
