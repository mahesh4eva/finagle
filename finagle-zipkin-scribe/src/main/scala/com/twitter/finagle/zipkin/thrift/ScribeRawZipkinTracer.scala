package com.twitter.finagle.zipkin.thrift

import com.twitter.conversions.StorageUnitOps._
import com.twitter.conversions.DurationOps._
import com.twitter.finagle.Thrift
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.stats.{
  DenylistStatsReceiver,
  ClientStatsReceiver,
  NullStatsReceiver,
  StatsReceiver
}
import com.twitter.finagle.thrift.Protocols
import com.twitter.finagle.tracing._
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.zipkin.{host => Host}
import com.twitter.finagle.zipkin.core.{RawZipkinTracer, Span, TracerCache}
import com.twitter.finagle.zipkin.thriftscala.{LogEntry, ResultCode, Scribe}
import com.twitter.scrooge.TReusableMemoryTransport
import com.twitter.util._
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.{Arrays, Base64}
import java.util.concurrent.ArrayBlockingQueue
import org.apache.thrift.TByteArrayOutputStream
import org.apache.thrift.protocol.TProtocol
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

object ScribeRawZipkinTracer {
  private[this] val tracerCache = new TracerCache[ScribeRawZipkinTracer]
  private[this] val clientLabel = "zipkin-scribe"

  private[this] val clientStatsReceiver: StatsReceiver = new DenylistStatsReceiver(
    ClientStatsReceiver, {
      case Seq(_, "requests") => false
      case Seq(_, "success") => false
      case Seq(_, "failures", _*) => false
      case _ => true
    }
  )

  private[this] def newClient(
    scribeHost: String,
    scribePort: Int,
    clientName: String
  ): Scribe.FutureIface = {
    // only report these finagle metrics (including counters for individual exceptions)

    val transport = ClientBuilder()
      .stack(
        Thrift.client
        // using an arbitrary, but bounded number of waiters to avoid memory leaks
        .withSessionPool.maxWaiters(250)
      )
      .name(clientName)
      .hosts(new InetSocketAddress(scribeHost, scribePort))
      .reportTo(clientStatsReceiver)
      .hostConnectionLimit(5)
      // somewhat arbitrary, but bounded timeouts
      .timeout(1.second)
      .daemon(true)
      .build()

    // Makes sure we don't trace the Scribe logging.
    new Scribe.FinagledClient(new TracelessFilter andThen transport, Protocols.binaryFactory())
  }

  /**
   * Creates a [[com.twitter.finagle.tracing.Tracer]] that sends traces to Zipkin via scribe.
   *
   * @param scribeHost Host to send trace data to
   * @param scribePort Port to send trace data to
   * @param scribeCategory scribe category under which traces will be logged
   * @param statsReceiver Where to log information about tracing success/failures
   * @param clientName Name of the scribe finagle client
   * @param timer A Timer used for timing out spans in the [[DeadlineSpanMap]]
   */
  def apply(
    scribeHost: String = Host().getHostName,
    scribePort: Int = Host().getPort,
    scribeCategory: String = "zipkin",
    statsReceiver: StatsReceiver = NullStatsReceiver,
    clientName: String = clientLabel,
    timer: Timer = DefaultTimer
  ): ScribeRawZipkinTracer =
    tracerCache.getOrElseUpdate(
      scribeHost + scribePort + scribeCategory,
      apply(newClient(scribeHost, scribePort, clientName), scribeCategory, statsReceiver, timer)
    )

  /**
   * Creates a [[com.twitter.finagle.tracing.Tracer]] that sends traces to scribe with the specified
   * scribeCategory.
   *
   * @param client The scribe client used to send traces to scribe
   * @param scribeCategory Category under which the trace data should be scribed
   * @param statsReceiver Where to log information about tracing success/failures
   * @param timer A Timer used for timing out spans in the [[DeadlineSpanMap]]
   */
  def apply(
    client: Scribe.FutureIface,
    scribeCategory: String,
    statsReceiver: StatsReceiver,
    timer: Timer
  ): ScribeRawZipkinTracer = {
    new ScribeRawZipkinTracer(
      client,
      statsReceiver,
      scribeCategory,
      timer
    )
  }

}

/**
 * Receives traces and sends them off to scribe with the specified scribeCategory.
 *
 * @param client The scribe client used to send traces to scribe
 * @param statsReceiver We generate stats to keep track of traces sent, failures and so on
 * @param scribeCategory scribe category under which the trace will be logged
 * @param timer A Timer used for timing out spans in the [[DeadlineSpanMap]]
 * @param poolSize The number of Memory transports to make available for serializing Spans
 * @param initialBufferSize Initial size of each transport
 * @param maxBufferSize Max size to keep around. Transports will grow as needed, but will revert back to `initialBufferSize` when reset if
 * they grow beyond `maxBufferSize`
 */
private[thrift] class ScribeRawZipkinTracer(
  client: Scribe.FutureIface,
  statsReceiver: StatsReceiver,
  scribeCategory: String = "zipkin",
  timer: Timer = DefaultTimer,
  poolSize: Int = 10,
  initialBufferSize: StorageUnit = 512.bytes,
  maxBufferSize: StorageUnit = 1.megabyte)
    extends RawZipkinTracer(statsReceiver, timer) {
  private[this] val scopedReceiver = statsReceiver.scope("log_span")
  private[this] val okCounter = scopedReceiver.counter("ok")
  private[this] val tryLaterCounter = scopedReceiver.counter("try_later")
  private[this] val errorReceiver = scopedReceiver.scope("error")

  private[this] val initialSizeInBytes = initialBufferSize.inBytes.toInt
  private[this] val maxSizeInBytes = maxBufferSize.inBytes.toInt

  private class LimitedSizeByteArrayOutputStream(initSize: Int, maxSize: Int)
      extends TByteArrayOutputStream(initSize) {

    override def reset(): Unit = synchronized {
      if (buf.length > maxSize) {
        buf = new Array[Byte](maxSize)
      }
      super.reset()
    }
  }

  /**
   * A wrapper around the TReusableMemoryTransport from Scrooge that
   * also resets the size of the underlying buffer if it grows larger
   * than `maxBufferSize`
   */
  private class ReusableTransport {
    private[this] val thriftOutput =
      new LimitedSizeByteArrayOutputStream(initialSizeInBytes, maxSizeInBytes)

    private[this] val transport = new TReusableMemoryTransport(thriftOutput)
    val protocol: TProtocol = Protocols.binaryFactory().getProtocol(transport)

    private[this] val base64Output =
      new LimitedSizeByteArrayOutputStream(initialSizeInBytes, maxSizeInBytes)

    def reset(): Unit = {
      transport.reset()
      base64Output.reset()
    }

    def toBase64Line: String = {
      // encode into a reusable OutputStream
      val out = Base64.getEncoder.wrap(base64Output)
      out.write(thriftOutput.get(), 0, thriftOutput.len())
      out.flush()
      out.close()

      // ensure there is space in the byte array for a trailing \n
      val encBytes = base64Output.get()
      val encLen = base64Output.len()
      val withNewline: Array[Byte] =
        if (encLen + 1 <= encBytes.length) encBytes
        else Arrays.copyOf(encBytes, encBytes.length + 1)

      // add the trailing '\n'
      withNewline(encLen) = '\n'
      new String(withNewline, 0, encLen + 1, StandardCharsets.US_ASCII)
    }
  }

  private[this] val bufferPool = new ArrayBlockingQueue[ReusableTransport](poolSize)
  0.until(poolSize).foreach { _ =>
    bufferPool.add(new ReusableTransport)
  }

  /**
   * Serialize the span, base64 encode and shove it all in a list.
   */
  private[this] def createLogEntries(spans: Seq[Span]): Seq[LogEntry] = {
    val entries = new ArrayBuffer[LogEntry](spans.size)

    spans.foreach { span =>
      val transport = bufferPool.take()
      try {
        span.toThrift.write(transport.protocol)
        entries.append(LogEntry(category = scribeCategory, message = transport.toBase64Line))
      } catch {
        case NonFatal(e) => errorReceiver.counter(e.getClass.getName).incr()
      } finally {
        transport.reset()
        bufferPool.add(transport)
      }
    }

    entries.toSeq
  }

  /**
   * Log the span data via Scribe.
   */
  def sendSpans(spans: Seq[Span]): Future[Unit] = {
    client
      .log(createLogEntries(spans))
      .respond {
        case Return(ResultCode.Ok) => okCounter.incr()
        case Return(ResultCode.TryLater) => tryLaterCounter.incr()
        case Return(_) => ()
        case Throw(e) => errorReceiver.counter(e.getClass.getName).incr()
      }
      .unit
  }
}
