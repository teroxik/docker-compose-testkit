// Copyright 2016 Carl Pulley

package cakesolutions.docker.testkit.examples

import akka.actor.{ActorSystem, Address}
import akka.cluster.MemberStatus
import akka.cluster.MemberStatus.Up
import cakesolutions.BuildInfo
import cakesolutions.docker.testkit.DockerComposeTestKit.LogEvent
import cakesolutions.docker.testkit.automata.MatchingAutomata
import cakesolutions.docker.testkit.clients.AkkaClusterClient
import cakesolutions.docker.testkit.clients.AkkaClusterClient.AkkaClusterState
import cakesolutions.docker.testkit.logging.TestLogger
import cakesolutions.docker.testkit.matchers.ObservableMatcher._
import cakesolutions.docker.testkit.network.ImpairmentSpec.Loss
import cakesolutions.docker.testkit.yaml.DockerComposeProtocol
import cakesolutions.docker.testkit.{DockerCompose, DockerComposeTestKit, DockerImage, TimedObservable}
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Inside, Matchers}

import scala.concurrent.duration._

object AutoDownSplitBrainDockerTest {
  import AkkaClusterClient._

  val akkaPort = 2552
  val autoDown = 10.seconds
  val leaderNode = "left-node-A"
  val version = BuildInfo.version
  val title = s"${Console.MAGENTA}${Console.UNDERLINED}"
  val highlight = s"${Console.WHITE}"

  def clusterJoin(node: String): LogEvent => Boolean = { event =>
    event.message.endsWith(s"Leader is moving node [akka.tcp://TestCluster@$node:$akkaPort] to [Up]")
  }

  val welcomeMessage: LogEvent => Boolean = { event =>
    event.message.endsWith(s"Welcome from [akka.tcp://TestCluster@$leaderNode:$akkaPort]")
  }

  final case class AkkaNodeSensors(
    log: TimedObservable.hot[LogEvent],
    available: TimedObservable.cold[Boolean],
    singleton: TimedObservable.cold[Boolean],
    leader: TimedObservable.cold[Address],
    members: TimedObservable.cold[AkkaClusterState],
    status: TimedObservable.cold[MemberStatus],
    unreachable: TimedObservable.cold[List[Address]]
  )
  object AkkaSensors {
    def apply(image: DockerImage)(implicit scheduler: Scheduler): AkkaNodeSensors = {
      AkkaNodeSensors(
        TimedObservable.hot(image.logging().publish),
        TimedObservable.cold(image.isAvailable()),
        TimedObservable.cold(image.isSingleton()),
        TimedObservable.cold(image.leader()),
        TimedObservable.cold(image.members()),
        TimedObservable.cold(image.status()),
        TimedObservable.cold(image.unreachable())
      )
    }
  }

  sealed trait MatchingState
  case object ClusterMemberCheck extends MatchingState
  case object StableCluster extends MatchingState
  case object WaitForLeaderElection extends MatchingState
  case object WaitForUnreachable extends MatchingState
  case object WaitToBeAvailable extends MatchingState
  case object WaitToJoinCluster extends MatchingState
  final case class ReceiveWelcomeMessages(images: Set[String]) extends MatchingState
}

class AutoDownSplitBrainDockerTest extends FreeSpec with Matchers with Inside with BeforeAndAfterAll with DockerComposeTestKit with TestLogger {
  import AutoDownSplitBrainDockerTest._
  import DockerComposeProtocol.Linux._
  import DockerComposeTestKit._
  import MatchingAutomata._

  implicit val testDuration: FiniteDuration = 3.minutes
  implicit val actorSystem = ActorSystem("AutoDownSplitBrainDockerTest")
  implicit val scheduler = Scheduler(actorSystem.dispatcher)

  def clusterNode(name: String, network1: String, network2: String): (String, DockerComposeYaml) =
    name -> DockerComposeYaml(
      Map(
        "template" -> Map(
          "resources" -> List(
            "cakesolutions.docker.jmx.akka",
            "cakesolutions.docker.network.default.linux"
          ),
          "image" -> s"docker-compose-testkit-tests:$version"
        ),
        "environment" -> Map(
          "AKKA_HOST" -> name,
          "AKKA_PORT" -> akkaPort,
          "CLUSTER_AUTO_DOWN" -> autoDown,
          "CLUSTER_SEED_NODE_1" -> s"akka.tcp://TestCluster@$leaderNode:$akkaPort"
        ),
        "expose" -> List(akkaPort),
        "networks" -> List(network1, network2)
      )
    )

  val yaml = DockerComposeYaml(
    Map(
      "version" -> "2",
      "services" -> Map(
        clusterNode("left-node-A", "left", "middle"),
        clusterNode("left-node-B", "left", "middle"),
        clusterNode("right-node-A", "right", "middle"),
        clusterNode("right-node-B", "right", "middle")
      ),
      "networks" -> Map(
        "left" -> Map.empty,
        "middle" -> Map.empty,
        "right" -> Map.empty
      )
    )
  )

  val compose: DockerCompose = up("autodown-split-brain", yaml)
  val leftNodeA: DockerImage = compose.service("left-node-A").docker.head
  val leftNodeB: DockerImage = compose.service("left-node-B").docker.head
  val rightNodeA: DockerImage = compose.service("right-node-A").docker.head
  val rightNodeB: DockerImage = compose.service("right-node-B").docker.head
  val clusterSensors = Map(
    "left.A" -> AkkaSensors(leftNodeA),
    "left.B" -> AkkaSensors(leftNodeB),
    "right.A" -> AkkaSensors(rightNodeA),
    "right.B" -> AkkaSensors(rightNodeB)
  )

  override def afterAll(): Unit = {
    compose.down()
    super.afterAll()
  }

  s"${title}Distributed Akka cluster with auto-downing" - {
    val available = MatchingAutomata[WaitToBeAvailable.type, Boolean](WaitToBeAvailable) {
      case _ => {
        case true =>
          Stop(Accept())
      }
    }

    val leader = MatchingAutomata[WaitForLeaderElection.type, Address](WaitForLeaderElection) {
      case _ => {
        case addr: Address if addr.host.contains("left-node-A") =>
          Stop(Accept())
      }
    }

    val stableCluster = MatchingAutomata[StableCluster.type, List[Address]](StableCluster, 3.seconds) {
      case _ => {
        case addrs: List[Address @unchecked] if addrs.nonEmpty =>
          Stop(Fail(s"Detected $addrs as unreachable"))
        case StateTimeout =>
          Stop(Accept())
      }
    }

    def clusterMembers(nodes: String*) = MatchingAutomata[ClusterMemberCheck.type, AkkaClusterState](ClusterMemberCheck) {
      case _ => {
        case AkkaClusterState(_, members, unreachable) if unreachable.isEmpty && members.filter(_.status == Up).flatMap(_.address.host) == Set(nodes: _*) =>
          Stop(Accept())
      }
    }

    def nodeUnreachable(node: String) = MatchingAutomata[WaitForUnreachable.type, List[Address]](WaitForUnreachable) {
      case _ => {
        case addrs: List[Address @unchecked] if addrs.exists(_.host.contains(node)) =>
          Stop(Accept())
      }
    }

    s"${title}should auto-seed and form a stable cluster" in {
      val superSeed = MatchingAutomata[WaitToJoinCluster.type, LogEvent](WaitToJoinCluster) {
        case _ => {
          case event: LogEvent if clusterJoin("left-node-A")(event) =>
            Stop(Accept())
        }
      }

      val welcome = MatchingAutomata[ReceiveWelcomeMessages, LogEvent](ReceiveWelcomeMessages(Set.empty[String])) {
        case ReceiveWelcomeMessages(members) => {
          case data @ LogEvent(_, image, _) if members.size == 2 && welcomeMessage(data) && ! members.contains(image) =>
            Stop(Accept())
          case data @ LogEvent(_, image, _) if welcomeMessage(data) =>
            Goto(ReceiveWelcomeMessages(members + image))
        }
      }

      for (node <- clusterSensors.keys) {
        clusterSensors(node).log.observable.connect()
      }

      val testSimulation = for {
        _ <- (superSeed.run(clusterSensors("left.A").log) && welcome.run(clusterSensors("left.B").log, clusterSensors("right.A").log, clusterSensors("right.B").log)).outcome
        _ <- clusterMembers("left-node-A", "left-node-B", "right-node-A", "right-node-B").run(clusterSensors("left.A").members).outcome
        _ = note(s"${highlight}cluster formed")
        _ <- (available.run(clusterSensors("left.A").available) && leader.run(clusterSensors("left.A").leader)).outcome
        _ = note(s"${highlight}node left.A is an available leader")
        _ <- stableCluster.run(clusterSensors("left.A").unreachable).outcome
        _ = note(s"${highlight}cluster stable")
      } yield Accept()

      testSimulation should observe(Accept())
    }


    s"${title}Short GC pause should not split-brain cluster" in {
      val testSimulation = for {
        _ <- clusterMembers("left-node-A", "left-node-B", "right-node-A", "right-node-B").run(clusterSensors("left.A").members).outcome
        _ <- (stableCluster.run(clusterSensors("left.A").unreachable) && available.run(clusterSensors("left.A").available) && leader.run(clusterSensors("left.A").leader)).outcome
        _ = note(s"${highlight}cluster stable and left.A is an available leader")
        _ = rightNodeA.pause()
        _ = note(s"${highlight}right.A GC pause starts")
        _ <- nodeUnreachable("right-node-A").run(clusterSensors("left.A").unreachable).outcome
        _ = note(s"${highlight}right.A is unreachable")
        _ = rightNodeA.unpause()
        _ = note(s"${highlight}right.A GC pause ends")
        _ <- clusterMembers("left-node-A", "left-node-B", "right-node-A", "right-node-B").run(clusterSensors("left.A").members).outcome
        _ <- stableCluster.run(clusterSensors("left.A").unreachable).outcome
        _ = note(s"${highlight}cluster stabilized with right.A as a member")
      } yield Accept()

      testSimulation should observe(Accept())
    }

    s"${title}network partition causes cluster to split-brain" in {
      val testSimulation = for {
        _ <- clusterMembers("left-node-A", "left-node-B", "right-node-A", "right-node-B").run(clusterSensors("left.A").members).outcome
        _ <- (stableCluster.run(clusterSensors("left.A").unreachable) && available.run(clusterSensors("left.A").available) && leader.run(clusterSensors("left.A").leader)).outcome
        _ = note(s"${highlight}cluster stable and left.A is an available leader")
        _ = compose.network("middle").impair(Loss("100%"))
        _ = note(s"${highlight}partition into left and right networks")
        _ <- (clusterMembers("left-node-A", "left-node-B").run(clusterSensors("left.A").members) && clusterMembers("right-node-A").run(clusterSensors("right.A").members) && clusterMembers("right-node-B").run(clusterSensors("right.B").members)).outcome
        _ = note(s"${highlight}cluster split brains into 3 clusters: left.A & left.B; right.A; right.B")
      } yield Accept()

      testSimulation should observe(Accept())
    }

  }

}
