package com.zengularity.storage

import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.scaladsl.Flow

import akka.stream.{ Attributes, FlowShape, Inlet, Outlet, Supervision }
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.stage.{
  GraphStage,
  GraphStageLogic,
  InHandler,
  OutHandler
}

final class FoldAsync[In, Out](
  zero: Out, f: (Out, In) => Future[Out]
)(implicit ec: ExecutionContext)
    extends GraphStage[FlowShape[In, Out]] {

  val in = Inlet[In]("FoldAsync.in")
  val out = Outlet[Out]("FoldAsync.out")
  val shape = FlowShape.of(in, out)

  override def toString: String = "FoldAsync"

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      val decider = inheritedAttributes.get[SupervisionStrategy].
        map(_.decider).getOrElse(Supervision.stoppingDecider)

      private var aggregator: Out = zero
      private var aggregating: Future[Out] = Future.successful(aggregator)

      private def onRestart(t: Throwable): Unit = {
        aggregator = zero
      }

      private val futureCB = getAsyncCallback[Try[Out]]((result: Try[Out]) => {
        result match {
          case Success(update) if update != null => {
            aggregator = update

            if (isClosed(in)) {
              push(out, update)
              completeStage()
            } else if (isAvailable(out) && !hasBeenPulled(in)) tryPull(in)
          }

          case other => {
            val ex = other match {
              case Failure(t) => t
              case Success(s) if s == null =>
                throw new IllegalArgumentException(
                  "Stream element must not be null"
                )
            }
            val supervision = decider(ex)

            if (supervision == Supervision.Stop) failStage(ex)
            else {
              if (supervision == Supervision.Restart) onRestart(ex)

              if (isClosed(in)) {
                push(out, aggregator)
                completeStage()
              } else if (isAvailable(out) && !hasBeenPulled(in)) tryPull(in)
            }
          }
        }
      }).invoke _

      def onPush(): Unit = {
        try {
          aggregating = f(aggregator, grab(in))

          aggregating.value match {
            case Some(result) => futureCB(result) // already completed
            case _            => aggregating.onComplete(futureCB)(ec)
          }
        } catch {
          case NonFatal(ex) => decider(ex) match {
            case Supervision.Stop => failStage(ex)
            case supervision => {
              supervision match {
                case Supervision.Restart => onRestart(ex)
                case _                   => () // just ignore on Resume
              }

              tryPull(in)
            }
          }
        }
      }

      override def onUpstreamFinish(): Unit = {}

      def onPull(): Unit = if (!hasBeenPulled(in)) tryPull(in)

      setHandlers(in, out, this)

      override def toString =
        s"FoldAsync.Logic(completed=${aggregating.isCompleted})"
    }
}

object FoldAsync {
  def apply[In, Out](z: => Out)(f: (Out, In) => Future[Out])(implicit ec: ExecutionContext): Flow[In, Out, NotUsed] = Flow.fromGraph(new FoldAsync[In, Out](z, f)(ec))
}
