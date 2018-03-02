/*
 * Copyright (C) 2018-2018 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import akka.NotUsed
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.{ ByteString, ByteStringBuilder }

import play.api.libs.ws.{ BodyWritable, EmptyBody, InMemoryBody, SourceBody }

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

  /**
   * Transforms input `E`lements as byte chunks.
   *
   * @param entry content to consume
   * @param bodyWritable body of a request
   * @return Returns a flow to consume chunks of a body from a request
   */
  def chunker[E: BodyWritable]: Flow[E, ByteString, NotUsed] = {
    val w = implicitly[BodyWritable[E]]

    Flow[E].flatMapConcat {
      w.transform(_) match {
        case SourceBody(source) => source.mapMaterializedValue(_ => NotUsed)
        case InMemoryBody(byteString) => Source.single(byteString)
        case EmptyBody => Source.empty[ByteString]
      }
    }
  }

  // Internal stages

  import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
  import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
  import akka.util.ByteString

  private class ChunkOfAtMost(limit: Int)
    extends GraphStage[FlowShape[ByteString, Chunk]] {

    override val toString = "ChunkOfAtMost"
    val in = Inlet[ByteString](s"${toString}.in")
    val out = Outlet[Chunk](s"${toString}.out")

    val shape = FlowShape.of(in, out)

    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private val inbuf = new ByteStringBuilder()
        private var outbuf = Option.empty[ByteString]
        private var downstreamWaiting = false

        override def preStart() {
          pull(in)
          super.preStart()
        }

        def onPush(): Unit = {
          inbuf ++= grab(in)

          var hasPushed = false

          if (downstreamWaiting) outbuf.foreach { previous =>
            downstreamWaiting = false
            outbuf = None

            hasPushed = true

            push(out, Chunk(previous))
          }

          if (outbuf.isEmpty && inbuf.length >= limit) {
            val (chunk, rem) = inbuf.result().splitAt(limit)
            outbuf = Some(chunk)

            inbuf.clear()
            inbuf ++= rem
          }

          if (!hasPushed) pull(in)
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

        def onPull(): Unit = {
          downstreamWaiting = true

          if ( /*outbuf.isEmpty && */ !hasBeenPulled(in)) pull(in)
        }

        setHandlers(in, out, this)

        override val toString = "ChunkOfAtMost.Logic"
      }
  }

  private class ChunkOfAtLeast(limit: Int)
    extends GraphStage[FlowShape[ByteString, Chunk]] {

    override val toString = "ChunkOfAtLeast"
    val in = Inlet[ByteString](s"${toString}.in")
    val out = Outlet[Chunk](s"${toString}.out")

    val shape = FlowShape.of(in, out)

    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private val inbuf = new ByteStringBuilder()
        private var outbuf = Option.empty[ByteString]
        private var downstreamWaiting = false

        override def preStart() {
          pull(in)
          super.preStart()
        }

        def onPush(): Unit = {
          inbuf ++= grab(in)

          var hasPushed = false

          if (downstreamWaiting) outbuf.foreach { previous =>
            downstreamWaiting = false
            outbuf = None

            hasPushed = true

            push(out, Chunk(previous))
          }

          if (outbuf.isEmpty && inbuf.length >= limit) {
            outbuf = Some(inbuf.result())
            inbuf.clear()
          }

          if (!hasPushed) pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          val rem = outbuf.getOrElse(ByteString.empty) ++ inbuf.result()

          if (rem.nonEmpty) { // Emit the remaining/last chunk(s)
            emit(out, Chunk.last(rem))
          }

          completeStage()
        }

        def onPull(): Unit = {
          downstreamWaiting = true

          if ( /*outbuf.isEmpty && */ !hasBeenPulled(in)) pull(in)
        }

        setHandlers(in, out, this)

        override val toString = "ChunkOfAtLeast.Logic"
      }
  }

}
