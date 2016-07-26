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
  z: Out, f: (Out, In) => Future[Out]
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

      private var inFlight = 0

      private var aggregated: Out = z
      private var aggregating: Future[Out] = Future.successful(aggregated)

      private def onResume(t: Throwable): Unit = {
        aggregated = z
      }

      private val futureCB = getAsyncCallback[Try[Out]]((result: Try[Out]) ⇒ {
        inFlight -= 1

        result match {
          case Success(update) if update != null ⇒ {
            aggregated = update

            if (isAvailable(out) && !hasBeenPulled(in)) tryPull(in)
          }

          case other ⇒ {
            val ex = other match {
              case Failure(t) ⇒ t
              case Success(s) if s == null ⇒
                throw new IllegalArgumentException(
                  "Stream element must not be null"
                )
            }

            if (decider(ex) == Supervision.Stop) failStage(ex)
            else if (isClosed(in) && inFlight == 0) completeStage()
            else if (!hasBeenPulled(in)) {
              onResume(ex)
              tryPull(in)
            }
          }
        }
      }).invoke _

      def onPush(): Unit = {
        try {
          aggregating = f(aggregated, grab(in))
          inFlight += 1

          aggregating.value match {
            case Some(result) => futureCB(result) // already completed
            case _            => aggregating.onComplete(futureCB)(ec)
          }
        } catch {
          case NonFatal(ex) ⇒ decider(ex) match {
            case Supervision.Stop => failStage(ex)
            case _                => onResume(ex) // Resume or Restart
          }
        }

        if (inFlight < 1) tryPull(in)
      }

      override def onUpstreamFinish(): Unit = {
        def watchTermination = getAsyncCallback[Try[Out]]((result: Try[Out]) ⇒ {
          result match {
            case Failure(ex) => {
              if (decider(ex) == Supervision.Stop) failStage(ex)
              else {
                emit(out, z)
                completeStage()
              }
            }

            case Success(folded) => {
              emit(out, folded)
              completeStage()
            }
          }
        }).invoke _

        if (inFlight == 0) completeStage()
        else aggregating.onComplete(watchTermination)(ec)
      }

      def onPull(): Unit = {
        if (isClosed(in) && inFlight == 0) completeStage()

        if (!hasBeenPulled(in)) tryPull(in)
      }

      setHandlers(in, out, this)

      override def toString = s"FoldAsync.Logic(inFlight=$inFlight)"
    }
}

object FoldAsync {
  def apply[In, Out](z: => Out)(f: (Out, In) => Future[Out])(implicit ec: ExecutionContext): Flow[In, Out, NotUsed] = Flow.fromGraph(new FoldAsync[In, Out](z, f)(ec))
}
