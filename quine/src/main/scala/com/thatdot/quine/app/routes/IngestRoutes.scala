package com.thatdot.quine.app.routes

import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS

import scala.compat.ExecutionContexts
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Sink
import akka.stream.{Materializer, StreamDetachedException}
import akka.util.Timeout
import akka.{Done, NotUsed}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.codahale.metrics.Metered
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

import com.thatdot.quine.graph.BaseGraph
import com.thatdot.quine.routes._
import com.thatdot.quine.util.{SwitchMode, ValveSwitch}

trait IngestStreamState {

  def addIngestStream(
    name: String,
    settings: IngestStreamConfiguration,
    wasRestoredFromStorage: Boolean,
    timeout: Timeout
  ): Try[Boolean]

  def getIngestStream(name: String): Option[IngestStreamWithControl[IngestStreamConfiguration]]

  def getIngestStreams(): Map[String, IngestStreamWithControl[IngestStreamConfiguration]]

  def removeIngestStream(
    name: String
  ): Option[IngestStreamWithControl[IngestStreamConfiguration]]
}

object IngestStreamWithControl {

  /** Hooks to freeze metrics and log once the ingest stream terminates
    *
    * @param name name of the ingest stream
    */
}

/** Adds to the ingest stream configuration extra information that will be
  * materialized only once the ingest stream is running and which may be
  * needed for stopping the stream
  *
  * @param optWs (for websocket ingest streams only) a Sink and IngestMeter via which additional records may be injected into this ingest stream
  */
final private[thatdot] case class IngestStreamWithControl[+Conf](
  settings: Conf,
  metrics: IngestMetrics,
  valve: IO[ValveSwitch],
  terminated: IO[Future[Done]],
  close: IO[Unit],
  var optWs: Option[(Sink[Json, NotUsed], IngestMeter)] = None,
  var restored: Boolean = false
) extends LazyLogging {

  // Returns a simpler version of status. Only possible values are completed, failed, or running
  private def checkTerminated(implicit materializer: Materializer): Future[IngestStreamStatus] = {
    implicit val ec: ExecutionContext = materializer.executionContext
    for {
      terminated <- terminated.unsafeToFuture()
      result = terminated.value match {
        case Some(Success(Done)) => IngestStreamStatus.Completed
        case Some(Failure(e)) =>
          // If exception occurs, it means that the ingest stream has failed
          println(e.toString)
          IngestStreamStatus.Failed
        case None => IngestStreamStatus.Running
      }
    } yield result
  }

  private def pendingStatusFuture(
    valveSwitch: ValveSwitch
  )(implicit materializer: Materializer): Future[IngestStreamStatus] = {
    /* Add a timeout to work around <https://github.com/akka/akka-stream-contrib/issues/119>
     *
     * Race the actual call to `getMode` with a timeout action
     */
    val theStatus = Promise[IngestStreamStatus]()
    theStatus.completeWith(
      valveSwitch
        .getMode()
        .map {
          case SwitchMode.Open => IngestStreamStatus.Running
          case SwitchMode.Close if restored => IngestStreamStatus.Restored
          case SwitchMode.Close => IngestStreamStatus.Paused
        }(materializer.executionContext)
    )
    materializer.system.scheduler.scheduleOnce(1.second) {
      val _ = theStatus.trySuccess(IngestStreamStatus.Terminated)
    }(materializer.executionContext)
    theStatus.future
  }

  def status(implicit materializer: Materializer): Future[IngestStreamStatus] = {

    val getPendingStatus: IO[IngestStreamStatus] =
      for {
        vs <- valve
        status <- IO.fromFuture(IO.pure(pendingStatusFuture(vs)))
      } yield status

    val getPendingStatusWithTimeout: IO[IngestStreamStatus] =
      getPendingStatus.timeoutTo(50.milliseconds, IO.pure(IngestStreamStatus.Running))

    val checkTerminatedStatus: IO[IngestStreamStatus] =
      IO.fromFuture(IO.pure(checkTerminated))

    val resultStatus: IO[IngestStreamStatus] =
      for {
        terminated <- checkTerminatedStatus
        result <- terminated match {
          case IngestStreamStatus.Completed => IO.pure(IngestStreamStatus.Completed)
          case IngestStreamStatus.Failed => IO.pure(IngestStreamStatus.Failed)
          case _ => getPendingStatusWithTimeout
        }
      } yield result

    resultStatus.unsafeToFuture()
  }
}

final private[thatdot] case class IngestMetrics(
  startTime: Instant,
  private var completionTime: Option[Instant],
  private var meter: IngestMetered
) {
  def stop(completedAt: Instant): Unit = {
    completionTime = Some(completedAt)
    meter = IngestMetered.freeze(meter)
  }

  def millisSinceStart(t: Instant): Long = MILLIS.between(startTime, t)

  private def meterToIngestRates(meter: Metered) =
    RatesSummary(
      meter.getCount,
      meter.getOneMinuteRate,
      meter.getFiveMinuteRate,
      meter.getFifteenMinuteRate,
      meter.getMeanRate
    )

  def toEndpointResponse: IngestStreamStats = IngestStreamStats(
    ingestedCount = meter.getCount,
    rates = meterToIngestRates(meter.counts),
    byteRates = meterToIngestRates(meter.bytes),
    startTime = startTime,
    totalRuntime = millisSinceStart(completionTime getOrElse Instant.now)
  )
}

/** The Akka HTTP implementation of [[IngestRoutes]] */
trait IngestRoutesImpl
    extends IngestRoutes
    with endpoints4s.akkahttp.server.Endpoints
    with exts.circe.JsonEntitiesFromSchemas
    with exts.ServerQuineEndpoints {

  implicit def timeout: Timeout
  implicit def materializer: Materializer
  def graph: BaseGraph

  private def stream2Info(conf: IngestStreamWithControl[IngestStreamConfiguration]): Future[IngestStreamInfo] =
    conf.status.map { status =>
      IngestStreamInfo(
        status,
        conf.terminated.unsafeToFuture().value collect { case Failure(exception) => exception.toString },
        conf.settings,
        conf.metrics.toEndpointResponse
      )
    }(graph.shardDispatcherEC)

  val serviceState: IngestStreamState

  /** Try to register a new ingest stream */
  private val ingestStreamStartRoute = ingestStreamStart.implementedBy { case (name, settings) =>
    serviceState.addIngestStream(name, settings, wasRestoredFromStorage = false, timeout) match {
      case Success(false) =>
        Left(
          endpoints4s.Invalid(
            s"Cannot create ingest stream `$name` (a stream with this name already exists)"
          )
        )

      case Success(true) => Right(())
      case Failure(err) =>
        err.printStackTrace; Left(endpoints4s.Invalid(s"Failed to create ingest stream `$name`: $err"))
    }
  }

  /** Try to stop an ingest stream */
  private val ingestStreamStopRoute = ingestStreamStop.implementedByAsync { (name: String) =>
    serviceState.removeIngestStream(name) match {
      case None => Future.successful(None)
      case Some(control @ IngestStreamWithControl(settings, metrics, valve @ _, terminated, close, _, _)) =>
        val finalStatus = control.status.map { previousStatus =>
          import IngestStreamStatus._
          previousStatus match {
            // in these cases, the ingest was healthy and runnable/running
            case Running | Paused | Restored => Terminated
            // in these cases, the ingest was not running/runnable
            case Completed | Failed | Terminated => previousStatus
          }
        }(ExecutionContexts.parasitic)

        val terminationMessage: Future[Option[String]] = {
          // start terminating the ingest
          close.unsafeRunAndForget()
          // future will return when termination finishes
          terminated
            .unsafeToFuture()
            .flatMap(t =>
              t
                .map({ case Done => None })(graph.shardDispatcherEC)
                .recover({ case e =>
                  Some(e.toString)
                })(graph.shardDispatcherEC)
            )(graph.shardDispatcherEC)
        }

        finalStatus
          .zip(terminationMessage)
          .map { case (newStatus, message) =>
            Some(
              IngestStreamInfoWithName(
                name,
                newStatus,
                message,
                settings,
                metrics.toEndpointResponse
              )
            )
          }(graph.shardDispatcherEC)
    }
  }

  /** Query out a particular ingest stream */
  private val ingestStreamLookupRoute = ingestStreamLookup.implementedByAsync { (name: String) =>
    serviceState.getIngestStream(name) match {
      case None => Future.successful(None)
      case Some(stream) => stream2Info(stream).map(s => Some(s.withName(name)))(graph.shardDispatcherEC)
    }
  }

  /** List out all of the currently active ingest streams */
  private val ingestStreamListRoute = ingestStreamList.implementedByAsync { _ =>
    Future
      .traverse(
        serviceState.getIngestStreams(): TraversableOnce[(String, IngestStreamWithControl[IngestStreamConfiguration])]
      ) { case (name, ingest) =>
        stream2Info(ingest).map(name -> _)(graph.shardDispatcherEC)
      }(implicitly, graph.shardDispatcherEC)
      .map(_.toMap)(graph.shardDispatcherEC)
  }

  private[this] def setIngestStreamPauseState(
    name: String,
    newState: SwitchMode
  ): Future[Option[IngestStreamInfoWithName]] =
    serviceState.getIngestStream(name) match {
      case None => Future.successful(None)
      case Some(ingest: IngestStreamWithControl[IngestStreamConfiguration]) =>
        val flippedValve = ingest.valve.unsafeToFuture().flatMap(_.flip(newState))(graph.nodeDispatcherEC)
        val ingestStatus = flippedValve.flatMap { _ =>
          ingest.restored = false; // FIXME not threadsafe
          stream2Info(ingest)
        }(graph.nodeDispatcherEC)
        ingestStatus.map(status => Some(status.withName(name)))(ExecutionContexts.parasitic)
    }

  private val ingestStreamPauseRoute = ingestStreamPause.implementedByAsync { (name: String) =>
    setIngestStreamPauseState(name, SwitchMode.Close)
      .map(Right(_))(ExecutionContexts.parasitic)
      .recover { case _: StreamDetachedException =>
        Left(endpoints4s.Invalid("Cannot pause a failed ingest."))
      }(ExecutionContexts.parasitic)
  }

  private val ingestStreamUnpauseRoute = ingestStreamUnpause.implementedByAsync { (name: String) =>
    setIngestStreamPauseState(name, SwitchMode.Open)
      .map(Right(_))(ExecutionContexts.parasitic)
      .recover { case _: StreamDetachedException =>
        Left(endpoints4s.Invalid("Cannot resume a failed ingest."))
      }(ExecutionContexts.parasitic)
  }

  final val ingestRoutes: Route = {
    ingestStreamStartRoute ~
    ingestStreamStopRoute ~
    ingestStreamLookupRoute ~
    ingestStreamListRoute ~
    ingestStreamPauseRoute ~
    ingestStreamUnpauseRoute
  }
}
