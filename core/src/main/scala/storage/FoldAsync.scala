package com.zengularity.storage

import scala.util.{ Failure, Success, Try }
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.scaladsl.Flow

import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{
  GraphStage,
  GraphStageLogic,
  InHandler,
  OutHandler
}

class FoldAsync[In, Out](
  z: => Out, f: (Out, In) => Future[Out]
)(implicit ec: ExecutionContext)
    extends GraphStage[FlowShape[In, Out]] {

  val in = Inlet[In]("FoldAsync.in")
  val out = Outlet[Out]("FoldAsync.out")

  val shape = FlowShape.of(in, out)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private var aggregated: Out = z
      private var aggregating: Future[Out] = Future.successful(aggregated)

      private def handle(output: Try[Out]): Unit = output match {
        case Failure(reason) => fail(out, reason)
        case Success(o) =>
          aggregated = o
          if (!isClosed(in)) pull(in)
      }

      setHandler(in, new InHandler {
        val asyncCallback = getAsyncCallback[Try[Out]](handle).invoke _

        def onPush(): Unit = {
          aggregating = f(aggregated, grab(in))
          aggregating.onComplete(asyncCallback)
        }

        override def onUpstreamFinish(): Unit = {
          val onTermination: Try[Out] => Unit = {
            case Failure(reason) => failStage(reason)
            case Success(folded) =>
              emit(out, folded)

              completeStage()
          }
          val watchTermination =
            getAsyncCallback[Try[Out]](onTermination).invoke _

          aggregating.onComplete(watchTermination)
        }
      })

      setHandler(out, new OutHandler {
        def onPull(): Unit = if (!hasBeenPulled(in)) pull(in)
      })
    }
}

object FoldAsync {
  def apply[In, Out](z: => Out)(f: (Out, In) => Future[Out])(implicit ec: ExecutionContext): Flow[In, Out, NotUsed] = Flow.fromGraph(new FoldAsync[In, Out](z, f)(ec))
}
