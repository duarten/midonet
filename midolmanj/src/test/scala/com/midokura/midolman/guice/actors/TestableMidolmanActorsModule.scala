/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.actors

import com.midokura.midolman.guice.MidolmanActorsModule
import com.midokura.midolman.services.MidolmanActorsService
import collection.mutable
import akka.testkit.{TestActor, TestActorRef, TestKit}
import akka.actor._
import java.util.concurrent.LinkedBlockingDeque
import akka.testkit.TestActor.{AutoPilot, Message}
import scala.Some
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._

/**
 * A [[com.midokura.midolman.guice.MidolmanActorsModule]] that can will override
 * the top level actors with probes and also provide an easy easy to access the
 * actual actors internal state.
 *
 * @see [[com.midokura.midolman.MidolmanTestCase]] for an usage example.
 */
class TestableMidolmanActorsModule(probes: mutable.Map[String, TestKit],
                                   actors: mutable.Map[String, TestActorRef[Actor]])
    extends MidolmanActorsModule {

    protected override def bindMidolmanActorsService() {
        bind(classOf[MidolmanActorsService])
            .toInstance(new TestableMidolmanActorsService())
    }

    class TestableMidolmanActorsService extends MidolmanActorsService {
        protected override def makeActorRef(actorProps: Props, actorName: String): ActorRef = {
            implicit val system = actorSystem

            val testKit = new ProbingTestKit(system, actorName)

            val targetActor = TestActorRef[Actor](actorProps, testKit.testActor, "real")

            testKit.setAutoPilot(new AutoPilot {
                val replyHandlers = mutable.Map[ActorRef, ActorRef]()
                def run(sender: ActorRef, msg: Any): Option[TestActor.AutoPilot] = {

                    msg match {
                        case "stop" => None

                        case OutgoingMessage(m, originalSender) =>
                            originalSender.tell(m, testKit.testActor)
                            Some(this)

                        case m if (sender != targetActor) =>
                            val handler =
                                replyHandlers.get(sender) match {
                                    case None =>
                                        val proxy = testKit.instance.actorOf(Props(new ProxyActor(sender, testKit.testActor)))
                                        replyHandlers.put(sender, proxy)
                                        proxy
                                    case Some(proxy) =>
                                        proxy
                                }

                            targetActor.tell(m, handler)
                            Some(this)
                    }
                }
            })

            probes.put(actorName, testKit)
            actors.put(actorName, targetActor)

            testKit.testActor
        }
    }

    class ProxyActor(originalSender: ActorRef, probeActor: ActorRef) extends Actor {
        val log = Logging(context.system, self)

        protected def receive = {
            case m =>
                probeActor ! OutgoingMessage(m, originalSender)
        }
    }

    class ProbingTestKit(_system: ActorSystem, actorName: String) extends TestKit(_system) {
        var instance: ProbingTestActor = null

        override lazy val testActor: ActorRef = {
            system.actorOf(Props(makeInstance()), actorName)
        }

        private def makeInstance(): ProbingTestActor = {
            val field = this.getClass.getSuperclass.getDeclaredField("akka$testkit$TestKit$$queue")
            field.setAccessible(true)
            val queue = field.get(this).asInstanceOf[LinkedBlockingDeque[Message]]
            instance = new ProbingTestActor(queue)
            instance
        }

        override def expectMsgType[T](implicit m: Manifest[T]) = super.expectMsgType(m)
    }
}

case class OutgoingMessage(m: Any, target: ActorRef)

class ProbingTestActor(queue: LinkedBlockingDeque[Message]) extends TestActor(queue) {
    var value = 0

    def actorOf(props: Props): ActorRef = {
        value += 1
        context.actorOf(props, "proxy-" + value)
    }
}

