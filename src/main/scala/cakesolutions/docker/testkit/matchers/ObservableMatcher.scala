package cakesolutions.docker.testkit.matchers

import akka.actor.FSM.Failure
import akka.actor.{ActorSystem, FSM, Props}
import cakesolutions.docker.testkit.logging.Logger
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Promise}
import scala.util.Try

object ObservableMatcher {
  sealed trait Action
  case class Fail(reason: String) extends Action
  case class Goto[State, Data](state: State, using: Data = null, forMax: FiniteDuration = null) extends Action
  case object Accept extends Action
  case object Stay extends Action

  case class InitialState[State, Data](state: State, data: Data, timeout: FiniteDuration = null)

  final case class When[State, Data](state: State, stateTimeout: FiniteDuration = null)(val transition: PartialFunction[FSM.Event[Data], Action])

  def observe[E : Manifest, S, D](initial: InitialState[S, D], actions: When[S, D]*)(implicit timeout: FiniteDuration, system: ActorSystem, outerLog: Logger) = Matcher { (obs: Observable[E]) =>
    val result = Promise[Boolean]

    class MatchingFSM extends FSM[S, D] {
      startWith(initial.state, initial.data, Option(initial.timeout))

      actions.foreach {
        case act @ When(state: S, stateTimeout) =>
          when(state, stateTimeout)(act.transition andThen {
            case Goto(state: S, null, null) =>
              goto(state)
            case Goto(state: S, null, max) =>
              goto(state).forMax(max)
            case Goto(state: S, data: D, null) =>
              goto(state).using(data)
            case Goto(state: S, data: D, max) =>
              goto(state).using(data).forMax(max)
            case Stay =>
              stay()
            case Accept =>
              stop()
            case Fail(reason) =>
              stop(Failure(reason))
          })
      }

      whenUnhandled {
        case Event(_, _) =>
          stay()
      }

      onTermination {
        case StopEvent(FSM.Normal, _, _) =>
          result.success(true)
        case StopEvent(FSM.Shutdown, _, _) =>
          result.success(false)
        case StopEvent(FSM.Failure(_), _, _) =>
          result.success(false)
      }

      initialize()
    }

    val checker = system.actorOf(Props(new MatchingFSM))
    obs.foreach { event =>
      checker ! event
    }(Scheduler(system.dispatcher))

    result.future.onComplete {
      case _: Try[_] =>
        system.stop(checker)
    }(system.dispatcher)

    MatchResult(Await.result(result.future, timeout), "+ve ???" , "-ve ???")
  }
}
