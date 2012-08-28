// Copyright 2012 Midokura Inc.

/*
 * Test plan:
 *    - Create executor with single thread.
 *    - Schedule operations on the executor for T+1.0s and T+1.5s.
 *    - The first operation records its start time, waits for 5.3s,
 *          and records its stop time.
 *    - The second operation only records its start time.
 *
 * If the first operation's wait blocked the thread, then the second
 * operation's recorded time will be 6.3s, but if it only blocked the
 * execution context and returned the thread to the executor, the recorded
 * time will be 1.5s.
 */

package com.midokura

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.dispatch.{Await, Future, Promise}
import akka.dispatch.Future.flow
import akka.event.Logging
import akka.pattern.pipe
import akka.testkit.CallingThreadDispatcher
import akka.util.duration._
import compat.Platform
import concurrent.ops.spawn
import scala.util.continuations.cps
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers


private class Timer {
    var startTime = Platform.currentTime
    var stopTime = startTime-1

    def stop(): Unit = synchronized { stopTime = Platform.currentTime }
    def elapsed(): Long = synchronized { stopTime - startTime }
}

@RunWith(classOf[JUnitRunner])
class BlockingTest extends Suite with ShouldMatchers {

    val config = ConfigFactory.parseString("""
        akka.actor.default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
                parallelism-max = 1
            }
        }
    """)
    val system = ActorSystem("BlockingTest", ConfigFactory.load(config))
    val initialDelay = 1000L
    val op2Start = 1500L
    val sleepTime = 5300L
    val margin = 150L

    private class Updater(val timer: Timer) extends Actor {
        val log = Logging(system, this)

        def receive = {
            case 1234 => log.info("received #1234"); timer.stop
            case x => log.info("received other {}", x)
        }

        override def preStart() { log.info("Starting time3UpdatingActor.") }
    }

    private def spawnPromiseThread(promise: Promise[Int]) {
        spawn {
            Thread.sleep(initialDelay+sleepTime)
            promise.success(1234)
        }
    }

    def testThreadSleep() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, (actor) => {
            Thread.sleep(sleepTime)
            actor ! 1234
        }, true)
    }

    def testAwaitResult() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, (actor) => {
            actor ! Await.result(promise, 7 seconds)
        }, true)
    }

    def testFlowBlock() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise,
                         (actor) => flow {
                                        actor.tell(promise())
                                    }(system.dispatcher),
                         false)
    }

    def testPipeTo() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, (actor) => (promise pipeTo actor), false)
    }

    private def checkForBlocking(promise: Promise[Int],
                                 thunk: (ActorRef) => Unit,
                                 expectBlocking: Boolean) {
        val timer0 = new Timer
        val timer1 = new Timer
        val timer2 = new Timer
        val timer3 = new Timer
        val time3UpdatingActor = system.actorOf(Props(new Updater(timer3)))

        assert(system.dispatcher.id != CallingThreadDispatcher.Id)
        system.scheduler.scheduleOnce(initialDelay milliseconds) {
            timer1.stop
            thunk(time3UpdatingActor)
        }
        system.scheduler.scheduleOnce(op2Start milliseconds) {
            timer2.stop
        }

        spawnPromiseThread(promise)

        // The executor's one thread runs the first scheduled operation, and has
        // to wait for it to complete before it can run the second scheduled
        // operation, late if the first op blocked.

        timer0.stop
        var elapsed: Long = timer0.elapsed
        elapsed should be >= 0L
        elapsed should be <= margin
        elapsed = timer1.elapsed
        elapsed should be === -1
        elapsed = timer2.elapsed
        elapsed should be === -1
        Thread.sleep(2000)
        elapsed = timer1.elapsed
        elapsed should be >= initialDelay
        elapsed should be <= initialDelay + margin
        elapsed = timer2.elapsed
        if (expectBlocking) {
            elapsed should be === -1
        } else {
            elapsed should be >= op2Start
            elapsed should be <= op2Start + margin
        }
        Thread.sleep(8000)
        if (expectBlocking) {
            elapsed = timer2.elapsed
            elapsed should be >= initialDelay + sleepTime
            elapsed should be <= initialDelay + sleepTime + margin
        }
        elapsed = timer3.elapsed
        elapsed should be >= initialDelay + sleepTime
        elapsed should be <= initialDelay + sleepTime + margin
    }

    def testFutureApplyDirect() {
        val timer = new Timer
        val promise = Promise[Int]()(system.dispatcher)
        spawnPromiseThread(promise)
        flow {
            promise()
            timer.stop
        }(system.dispatcher)
        timer.elapsed should be === -1
        Thread.sleep(6500)
        timer.elapsed should be >= initialDelay + sleepTime
        timer.elapsed should be <= initialDelay + sleepTime + margin
    }

    def testFutureApplyIndirect() {
        val timer = new Timer
        val promise = Promise[Int]()(system.dispatcher)
        val inner = () => { promise(); timer.stop }
        spawnPromiseThread(promise)
        flow {
            inner()
        }(system.dispatcher)
        timer.elapsed should be === -1
        Thread.sleep(6500)
        timer.elapsed should be >= initialDelay + sleepTime
        timer.elapsed should be <= initialDelay + sleepTime + margin
    }

    def testFutureApplyBare() = {
        val timer = new Timer
        val promise = Promise[Int]()(system.dispatcher)
        val inner = () => { promise(); timer.stop }
        spawnPromiseThread(promise)
        inner()
        timer.elapsed should be >= initialDelay + sleepTime
        timer.elapsed should be <= initialDelay + sleepTime + margin
    }

    def testFutureApplyExternalMethod() {
        val timer = new Timer
        val promise = Promise[Int]()(system.dispatcher)
        spawnPromiseThread(promise)
        flow {
            externalMethod(promise, timer)
        }(system.dispatcher)
        timer.elapsed should be === -1
        Thread.sleep(6500)
        timer.elapsed should be >= initialDelay + sleepTime
        timer.elapsed should be <= initialDelay + sleepTime + margin
    }

    // This requires that the method's return type include the @cps annotations
    // to Unit from the method's body.
    private def externalMethod(future: Future[Int], timer: Timer) = {
        future()
        timer.stop
    }

    private def eitherIntOrContinuation1(future: Future[Int],
                                         immediate: Boolean):
            Int @cps[Future[_]] = {
        if (immediate)
            return 1234 : Int @cps[Future[Any]]
        else
            return future()
    }

    private def eitherIntOrContinuation2(future: Future[Int],
                                         immediate: Boolean):
            Int @cps[Future[_]] = {
        if (immediate)
            1234 : Int @cps[Future[Any]]
        else
            future()
    }

    def testNonimmediateReturn() {
        val timer = new Timer
        val promise = Promise[Int]()(system.dispatcher)
        spawnPromiseThread(promise)
        val updater = system.actorOf(Props(new Updater(timer)))
        flow {
            val i: Int = eitherIntOrContinuation2(promise, false)
            updater ! i
        }(system.dispatcher)
        timer.elapsed should be === -1
        Thread.sleep(8000)
        timer.elapsed should be >= initialDelay + sleepTime
        timer.elapsed should be <= initialDelay + sleepTime + margin
    }

}
