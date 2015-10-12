package hu.bme.mit.incquerydcore.trainbenchmark

import akka.actor._
import akka.remote.RemoteScope
import com.typesafe.config.ConfigFactory
import hu.bme.mit.incquerydcore._

import scala.collection.immutable.HashMap

/**
 * Created by wafle on 10/11/2015.
 */
abstract class RemoteOnlyTrainbenchmarkQuery extends TrainbenchmarkQuery{
  val system = ActorSystem("Master", ConfigFactory.load("master"))
  val remoteAdress = Address("akka.tcp", "Slave1System", System.getenv("SLAVE1IP"), 2552)
  val remoteActors = new collection.mutable.MutableList[ActorRef]()
  def newRemote(props: Props, name: String = ""): ActorRef = {
    val actor = system.actorOf(props.withDeploy(Deploy(scope = RemoteScope(remoteAdress))), name)
    remoteActors += actor
    actor
  }
  override def shutdown: Unit = {
    remoteActors.foreach( actor => actor ! PoisonPill)
    super.shutdown()
  }
}

class RemoteOnlyPosLength extends RemoteOnlyTrainbenchmarkQuery {
  override val production: ActorRef = system.actorOf(Props(new Production("PosLength")))
  val trimmer = newRemote(Props(new Trimmer(production ! _, Vector(0, 1))))

  def condition(n: nodeType) = n match {
    case Vector(a, length: Int) => length <= 0
  }
  val first = newRemote(Props(new Checker(trimmer ! _, condition)))

  val inputNodes: List[(ReteMessage) => Unit] = List(first ! _)
  override val terminator = Terminator(inputNodes, production)

  override val inputLookup: Map[String, (ChangeSet) => Unit] = Map(
    "length" -> ((cs: ChangeSet) => first ! cs)
  )
}

class RemoteOnlyConnectedSegments extends RemoteOnlyTrainbenchmarkQuery {
  val production = system.actorOf(Props(new Production("ConnectedSegments")))
  val sensorJoinSecond = newRemote(Props(new HashJoiner(production ! _, 7, Vector(5, 6), 2, Vector(0, 1))))
  val sensorJoinFirst = newRemote(Props(new HashJoiner(sensorJoinSecond ! Primary(_), 6, Vector(0),  2, Vector(0))))
  val join1234_5 = newRemote(Props(new HashJoiner(sensorJoinFirst ! Primary(_), 5, Vector(4), 2, Vector(0))))

  val join12_34 = newRemote(Props(new HashJoiner(join1234_5 ! Primary(_), 3, Vector(2), 3, Vector(0))))

  val join3_4 = newRemote(Props(new HashJoiner(join12_34 ! Secondary(_), 2, Vector(1), 2, Vector(0))))
  val join1_2 = newRemote(Props(new HashJoiner(join12_34 ! Primary(_), 2, Vector(1), 2, Vector(0))))
  val joinSegment1 = newRemote(Props(new HashJoiner(join1_2 ! Primary(_), 2, Vector(0), 1, Vector(0))))
  override val inputLookup = Map(
    "connectsTo" -> ((cs: ChangeSet) => {
      joinSegment1 ! Primary(cs)
      join1_2 ! Secondary(cs)
      join3_4 ! Primary(cs)
      join3_4 ! Secondary(cs)
      join1234_5 ! Secondary(cs)
    }),
    "sensor" -> ((cs: ChangeSet) => {
      sensorJoinFirst ! Secondary(cs)
      sensorJoinSecond ! Secondary(cs)
    }),
    "type" -> ((rawCS: ChangeSet) => {
      val cs = ChangeSet(
        rawCS.positive.filter( vec=> vec(1) == "Segment").map( vec => Vector(vec(0))),
        rawCS.negative.filter( vec=> vec(1) == "Segment").map( vec => Vector(vec(0)))
      )
      if (cs.positive.size > 0) {
        joinSegment1 ! Secondary(cs)
      } else if (cs.negative.size > 0) {
        joinSegment1 ! Secondary(cs)
      }
    })
  )
  import utils.ReteNode
  val inputNodes: List[ReteMessage => Unit] =
    List(
      joinSegment1.primary, joinSegment1.secondary,
      join1_2.secondary,
      join3_4.primary, join3_4.secondary,
      join1234_5.secondary,
      sensorJoinFirst.secondary,
      sensorJoinSecond.secondary
    )
  override val terminator = Terminator(inputNodes, production)
}
class RemoteOnlySwitchSet extends RemoteOnlyTrainbenchmarkQuery {
  val production = system.actorOf(Props(new Production("SwitchSet")))
  val finalJoin = newRemote(Props(new HashJoiner(production ! _, 3, Vector(2), 4, Vector(0))))

  val inequality = newRemote(Props(new InequalityChecker(finalJoin ! Secondary(_), 2, Vector(3))))
  val switchPositionCurrentPositionJoin = newRemote(Props(new HashJoiner(inequality ! _, 2, Vector(1), 2, Vector(0))))
  val switchSwitchPositionJoin = newRemote(Props(new HashJoiner(switchPositionCurrentPositionJoin ! Primary(_), 2, Vector(0), 2, Vector(0))))

  val followsEntryJoin = newRemote(Props(new HashJoiner(finalJoin ! Primary(_), 2, Vector(1), 2, Vector(0))))
  val entrySemaphoreJoin = newRemote(Props(new HashJoiner(followsEntryJoin ! Primary(_), 2, Vector(0), 2, Vector(1))))
  val leftTrimmer = newRemote(Props(new Trimmer(entrySemaphoreJoin ! Primary(_), Vector(0))))
  val signalChecker = newRemote(Props(new Checker(leftTrimmer ! _, ((cs) => cs(1) == "SIGNAL_GO"))))


  val inputLookup = Map(
    "signal" -> ((cs: ChangeSet) => signalChecker ! cs),
    "entry" -> ((cs: ChangeSet) => entrySemaphoreJoin ! Secondary(cs)),
    "follows" -> ((cs: ChangeSet) => followsEntryJoin ! Secondary(cs)),
    "switch" -> ((cs: ChangeSet) => switchSwitchPositionJoin ! Primary(cs)),
    "position" -> ((cs: ChangeSet) => switchSwitchPositionJoin ! Secondary(cs)),
    "currentPosition" -> ((cs: ChangeSet) => switchPositionCurrentPositionJoin ! Secondary(cs))
  )
  import utils.ReteNode
  val inputNodes: List[ReteMessage => Unit] =
    List(signalChecker ! _, entrySemaphoreJoin.secondary, followsEntryJoin.secondary,
      switchSwitchPositionJoin.primary, switchSwitchPositionJoin.secondary,
      switchPositionCurrentPositionJoin.secondary
    )
  override val terminator = Terminator(inputNodes, production)


}

class RemoteOnlyNeighbor extends RemoteOnlyTrainbenchmarkQuery {
  val production = system.actorOf(Props(new Production("SemaphoreNeighbor")))
  val antijoin = newRemote(Props(new HashAntiJoiner(production ! _, Vector(2, 5), Vector(1, 2))),"a")
  val inequality = newRemote(Props(new InequalityChecker(antijoin ! Primary(_), 0, Vector(6))),"b")
  val finalJoin = newRemote(Props(new HashJoiner(inequality ! _, 6, Vector(5), 2, Vector(1))),"c")
  val secondToLastJoin = newRemote(Props(new HashJoiner(finalJoin ! Primary(_), 3, Vector(1), 4, Vector(1))),"d")
  val rightMostJoin = newRemote(Props(new HashJoiner(secondToLastJoin ! Secondary(_), 3, Vector(2), 2, Vector(0))),"e")
  val sensorConnects = newRemote(Props(new HashJoiner(rightMostJoin ! Primary(_), 2, Vector(0), 2, Vector(0))),"f")
  val exitDefined = newRemote(Props(new HashJoiner(secondToLastJoin ! Primary(_), 2, Vector(0), 2, Vector(0))),"g")
  val entryDefined = newRemote(Props(new HashJoiner(antijoin ! Secondary(_), 2, Vector(0), 2, Vector(0))),"h")

  val inputLookup = Map(
    "entry" -> ((cs: ChangeSet) => entryDefined ! Primary(cs)),
    "definedBy" -> ((cs: ChangeSet) => {
      entryDefined ! Secondary(cs)
      finalJoin ! Secondary(cs)
      exitDefined ! Primary(cs)
    }),
    "exit" -> ((cs: ChangeSet) => exitDefined ! Secondary(cs)),
    "sensor" -> ((cs: ChangeSet) => {
      sensorConnects ! Primary(cs)
      rightMostJoin ! Secondary(cs)
    }),
    "connectsTo" -> ((cs: ChangeSet) => sensorConnects ! Secondary(cs))
  )
  import utils.ReteNode
  val inputNodes: List[ReteMessage => Unit] =
    List(
      entryDefined.primary, entryDefined.secondary,
      exitDefined.primary, exitDefined.secondary,
      sensorConnects.primary, sensorConnects.secondary,
      rightMostJoin.secondary, finalJoin.secondary)
  override val terminator = Terminator(inputNodes, production)

}

class RemoteOnlyRouteSensor extends RemoteOnlyTrainbenchmarkQuery {
  val production = system.actorOf(Props(new Production("RouteSensor")))
  val antijoin = newRemote(Props(new HashAntiJoiner(production ! _, Vector(2, 3), Vector(0, 1))))
  val sensorJoin = newRemote(Props(new HashJoiner(antijoin ! Primary(_), 3, Vector(1), 2, Vector(0))))
  val followsJoin = newRemote(Props(new HashJoiner(sensorJoin ! Primary(_), 2, Vector(0), 2, Vector(1))))
  val inputLookup = HashMap(
    "switch" -> ((cs: ChangeSet) => followsJoin ! Primary(cs)),
    "follows" -> ((cs:ChangeSet) => followsJoin ! Secondary(cs)),
    "sensor" -> ((cs: ChangeSet) => sensorJoin ! Secondary(cs)),
    "definedBy" -> ((cs: ChangeSet) => antijoin ! Secondary(cs))
  )
  import utils.ReteNode
  val inputNodes: List[ReteMessage => Unit] =
    List(
      antijoin.secondary, sensorJoin.secondary,
      followsJoin.primary, followsJoin.secondary
    )
  override val terminator = Terminator(inputNodes, production)
}

class RemoteOnlySwitchSensor extends RemoteOnlyTrainbenchmarkQuery {
  val production = system.actorOf(Props(new Production("SwitchSensor")), "sw-production")
  val antijoin = newRemote(Props(new HashAntiJoiner(production ! _, Vector(0), Vector(0))), "sw-antijoin")

  val trimmer = newRemote(Props(new Trimmer(antijoin ! Secondary(_), Vector(0))), "sw-trimmer")
  val inputLookup = Map(
    "sensor" -> ((cs: ChangeSet) => trimmer ! cs),
    "type" -> ((rawCS: ChangeSet) => {
      val cs = ChangeSet(
        rawCS.positive.filter( vec=> vec(1) == "Switch").map( vec => Vector(vec(0))),
        rawCS.negative.filter( vec=> vec(1) == "Switch").map( vec => Vector(vec(0)))
      )
      if (cs.positive.size > 0) {
        antijoin ! Primary(cs)
      } else if (cs.negative.size > 0) {
        antijoin ! Primary(cs)
      }
    })
  )

  import utils.ReteNode
  val inputNodes: List[ReteMessage => Unit] = List(antijoin.primary, trimmer ! _)
  override val terminator = Terminator(inputNodes, production)
}