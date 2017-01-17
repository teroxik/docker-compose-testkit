// Copyright 2016 Carl Pulley

package cakesolutions.docker.jmx

import _root_.akka.actor.ActorSystem
import cakesolutions.docker.testkit.clients.AkkaClusterClient
import cakesolutions.docker.testkit.logging.Logger
import cakesolutions.docker.testkit.{DockerImage, Monitor, Notify}
import cats.Now
import monix.execution.Scheduler
import monix.reactive.Observable
import org.atnos.eff.ErrorEffect._
import org.atnos.eff._
import org.atnos.eff.interpret._

package object akka {
  import AkkaClusterClient._

  sealed trait JmxAction[Result]
  final case class Jmx[State](name: String, monitor: Monitor[State, AkkaClusterState]) extends JmxAction[Observable[Notify]]
  final case class TaggedJmx[State](name: String, others: Seq[String], monitor: Monitor[State, (String, AkkaClusterState)]) extends JmxAction[Observable[Notify]]

  type _jmx[Model] = JmxAction |= Model

  def jmx[State, Model: _jmx](monitor: Monitor[State, AkkaClusterState])(name: String): Eff[Model, Observable[Notify]] =
    Eff.send[JmxAction, Model, Observable[Notify]](Jmx(name, monitor))

  def taggedJmx[State, Model: _jmx](monitor: Monitor[State, (String, AkkaClusterState)])(name: String, others: String*): Eff[Model, Observable[Notify]] =
    Eff.send[JmxAction, Model, Observable[Notify]](TaggedJmx(name, others, monitor))

  implicit class JmxRun[R, A](effects: Eff[R, A]) {
    // Currently, it is essential that our first argument to runJmx is not a vararg!
    def runJmx[U](
      db: Map[String, DockerImage]
    )(implicit member: Member.Aux[JmxAction, R, U],
      error: _errorOrOk[U],
      system: ActorSystem,
      scheduler: Scheduler,
      log: Logger
    ): Eff[U, A] = {
      translate(effects)(new Translate[JmxAction, U] {
        def apply[X](jmx: JmxAction[X]): Eff[U, X] = jmx match {
          case Jmx(name, monitor) =>
            ErrorEffect.eval(Now {
              monitor.run(db(name).members()).asInstanceOf[X]
            })
          case TaggedJmx(name, others, monitor) =>
            ErrorEffect.eval(Now {
              val obs: Observable[(String, AkkaClusterState)] =
                Observable(name +: others: _*)
                  .map { nm =>
                    db(nm).members().map(st => nm -> st)
                  }
                  .merge

              monitor.run(obs).asInstanceOf[X]
            })
        }
      })
    }
  }
}