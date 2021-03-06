package hu.bme.mit.incqueryds

import akka.actor.ActorRef

import scala.concurrent.Promise

/**
  * Created by wafle on 12/25/2015.
  */
class ReteMessage() {}

case class ChangeSet(positive: Vector[nodeType] = Vector(), negative: Vector[nodeType] = Vector())
  extends ReteMessage()

case class ExpectMoreTerminators(val id: Int, val inputs: Iterable[ReteMessage => Unit])

case class TerminatorMessage(terminatorID: Int, messageID: Int, route: List[ActorRef] = List.empty) extends ReteMessage()

case class ExpectTerminator(terminatorID: Int, messageID: Int, promise: Promise[Set[nodeType]]) extends ReteMessage()

case class Pause(messageID: Int) extends ReteMessage()

case class Resume(messageID: Int) extends ReteMessage()