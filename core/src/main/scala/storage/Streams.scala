package com.zengularity.storage

import akka.NotUsed
import akka.util.{ ByteString, ByteStringBuilder }
import akka.stream.scaladsl.Flow

/** Stream management utility. */
object Streams {

  /**
   * Returns an flow to consume chunks of at least at the specified size.
   *
   * @param size the maximum number of bytes to be read from the source
   */
  def consumeAtLeast(size: Bytes): Flow[ByteString, Chunk, NotUsed] =
    Flow.fromGraph(new ChunkOfAtLeast(size.bytes.toInt))

  /**
   * Returns an flow to consume chunks of at most the specified size.
   *
   * @param size the maximum number of bytes to be read from the source
   */
  def consumeAtMost(size: Bytes): Flow[ByteString, Chunk, NotUsed] =
    Flow.fromGraph(new ChunkOfAtMost(size.bytes.toInt))

  // Internal stages

  import akka.util.ByteString
  import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
  import akka.stream.stage.{
    GraphStage,
    GraphStageLogic,
    InHandler,
    OutHandler
  }

  private class ChunkOfAtMost(limit: Int)
      extends GraphStage[FlowShape[ByteString, Chunk]] {

    val in = Inlet[ByteString]("ChunkOfAtMost.in")
    val out = Outlet[Chunk]("ChunkOfAtMost.out")

    val shape = FlowShape.of(in, out)

    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        private val inbuf = new ByteStringBuilder()
        private var outbuf = Option.empty[ByteString]
        private var downstreamWaiting = false

        override def preStart() {
          pull(in)
          super.preStart()
        }

        setHandler(in, new InHandler {
          def onPush(): Unit = {
            inbuf ++= grab(in)

            val wasWaiting = downstreamWaiting

            if (downstreamWaiting) outbuf.foreach { previous =>
              downstreamWaiting = false
              outbuf = None

              push(out, Chunk(previous))
            }

            if (outbuf.isEmpty && inbuf.length >= limit) {
              val (chunk, rem) = inbuf.result().splitAt(limit)
              outbuf = Some(chunk)

              inbuf.clear()
              inbuf ++= rem
            }

            if (wasWaiting) pull(in)
          }

          override def onUpstreamFinish(): Unit = {
            val rem = outbuf.getOrElse(ByteString.empty) ++ inbuf.result()

            if (rem.nonEmpty) { // Emit the remaining/last chunk(s)
              if (rem.size <= limit) {
                emit(out, Chunk.last(rem))
              } else rem.splitAt(limit) match {
                case (a, b) => emitMultiple(out, List(Chunk(a), Chunk.last(b)))
              }
            }

            completeStage()
          }
        })

        setHandler(out, new OutHandler {
          def onPull(): Unit = {
            downstreamWaiting = true

            if (outbuf.isEmpty && !hasBeenPulled(in)) pull(in)
          }
        })
      }
  }

  private class ChunkOfAtLeast(limit: Int)
      extends GraphStage[FlowShape[ByteString, Chunk]] {

    val in = Inlet[ByteString]("ChunkOfAtLeast.in")
    val out = Outlet[Chunk]("ChunkOfAtLeast.out")

    val shape = FlowShape.of(in, out)

    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        private val inbuf = new ByteStringBuilder()
        private var outbuf = Option.empty[ByteString]
        private var downstreamWaiting = false

        override def preStart() {
          pull(in)
          super.preStart()
        }

        setHandler(in, new InHandler {
          def onPush(): Unit = {
            inbuf ++= grab(in)

            val wasWaiting = downstreamWaiting

            if (downstreamWaiting) outbuf.foreach { previous =>
              downstreamWaiting = false
              outbuf = None

              push(out, Chunk(previous))
            }

            if (outbuf.isEmpty && inbuf.length >= limit) {
              outbuf = Some(inbuf.result())
              inbuf.clear()
            }

            if (wasWaiting) pull(in)
          }

          override def onUpstreamFinish(): Unit = {
            val rem = outbuf.getOrElse(ByteString.empty) ++ inbuf.result()

            if (rem.nonEmpty) { // Emit the remaining/last chunk(s)
              emit(out, Chunk.last(rem))
            }

            completeStage()
          }
        })

        setHandler(out, new OutHandler {
          def onPull(): Unit = {
            downstreamWaiting = true

            if (outbuf.isEmpty && !hasBeenPulled(in)) pull(in)
          }
        })
      }
  }
}
