/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman

import java.util.{LinkedList, UUID}

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SimulationResult}
import org.midonet.midolman.simulation.{Bridge, Simulator, PacketContext}
import org.midonet.midolman.state.HappyGoLuckyLeaser
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.{FlowMatches, Packet}
import org.midonet.packets.Ethernet
import org.midonet.packets.util.EthBuilder
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.state.ShardedFlowStateTable
import org.midonet.sdn.state.FlowStateTransaction

@RunWith(classOf[JUnitRunner])
class FlowTracingTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

    var bridge: UUID = _
    var port1: UUID = _
    var port2: UUID = _
    var chain: UUID = _

    val table = new ShardedFlowStateTable[TraceKey,TraceContext](clock)
        .addShard()
    implicit val traceTx = new FlowStateTransaction(table)

    override def beforeTest(): Unit = {
        bridge = newBridge("bridge0")
        port1 = newBridgePort(bridge)
        port2 = newBridgePort(bridge)
        materializePort(port1, hostId, "port1")
        materializePort(port2, hostId, "port2")

        chain = newInboundChainOnBridge("my-chain", bridge)
        fetchChains(chain)
        fetchPorts(port1, port2)
        fetchDevice[Bridge](bridge)
    }

    private def makeFrame(tpDst: Short, tpSrc: Short = 10101) =
        { eth addr "00:02:03:04:05:06" -> "00:20:30:40:50:60" } <<
        { ip4 addr "192.168.0.1" --> "192.168.0.2" } <<
        { udp ports tpSrc ---> tpDst }

    implicit def ethBuilder2Packet(ethBuilder: EthBuilder[Ethernet]): Packet = {
        val frame: Ethernet = ethBuilder
        val flowMatch = FlowMatches.fromEthernetPacket(frame)
        val pkt = new Packet(frame, flowMatch)
        flowMatch.setInputPortNumber(42)
        pkt
    }

    def makePacket(variation: Short): Packet = makeFrame(variation)

    // override to avoid clearing the packet context
    def simulate(pktCtx: PacketContext)
            : (SimulationResult, PacketContext) = {
        pktCtx.initialize(NO_CONNTRACK, NO_NAT, HappyGoLuckyLeaser, traceTx)
        val r = force {
            Simulator.simulate(pktCtx)
        }
        (r, pktCtx)
    }

    feature("Tracing enabled by rule in chain") {
        scenario("tracing enabled by catchall rule") {
            val requestId = UUID.randomUUID
            newTraceRuleOnChain(chain, 1, newCondition(), requestId)

            val pktCtx = packetContextFor(makeFrame(1), port1)
            pktCtx.tracingEnabled(requestId) shouldBe false
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.tracingEnabled(requestId) shouldBe true
                    pktCtx.log should be (PacketContext.traceLog)
            }
            val (simRes, _) = simulate(pktCtx)
            simRes should be (AddVirtualWildcardFlow)
        }

        scenario("tracing enabled by specific rule match") {
            val requestId = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId)

            val pktCtx = packetContextFor(makeFrame(1), port1)
            pktCtx.tracingEnabled(requestId) shouldBe false
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)
            pktCtx.tracingEnabled(requestId) shouldBe false
            pktCtx.log should not be PacketContext.traceLog

            val pktCtx2 = packetContextFor(makeFrame(500), port1)
            pktCtx2.tracingEnabled(requestId) shouldBe false
            try {
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId) shouldBe true
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)

            val pktCtx3 = packetContextFor(makeFrame(1000), port1)
            pktCtx3.tracingEnabled(requestId) shouldBe false
            simulate(pktCtx3)._1 should be (AddVirtualWildcardFlow)
            pktCtx3.tracingEnabled(requestId) shouldBe false
            pktCtx3.log should not be PacketContext.traceLog
        }

        scenario("Multiple rules matching on different things") {
            val requestId1 = UUID.randomUUID
            val requestId2 = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId1)
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpSrc = Some(1000)), requestId2)

            val pktCtx = packetContextFor(makeFrame(500), port1)
            pktCtx.tracingEnabled(requestId1) shouldBe false
            pktCtx.tracingEnabled(requestId2) shouldBe false
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) shouldBe true
                    pktCtx.tracingEnabled(requestId2) shouldBe false
            }
            simulate(pktCtx)._1 should be (AddVirtualWildcardFlow)

            val pktCtx2 = packetContextFor(makeFrame(500, 1000), port1)
            pktCtx2.tracingEnabled(requestId1) shouldBe false
            try {
                // should hit second rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId2) shouldBe true
                    pktCtx2.tracingEnabled(requestId1) shouldBe false
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            try {
                // should hit first rule
                simulate(pktCtx2)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx2.tracingEnabled(requestId2) shouldBe true
                    pktCtx2.tracingEnabled(requestId1) shouldBe true
                    pktCtx2.log should be (PacketContext.traceLog)
            }
            simulate(pktCtx2)._1 should be (AddVirtualWildcardFlow)
        }

        scenario("restart on trace requested exception") {
            val requestId = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId)
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1),
                                      packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(
                List(makePacket(500)).toArray)
            pktCtxs.size() should be (2)
            pktCtxs.pop().runs should be (2)
        }
    }

    feature("Tracing across a simulation restart") {
        scenario("The trace request should not persist across restarts") {
            var trace = true
            var restart = true
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                if (restart) {
                    pktCtx.traceTx.get(TraceKey.fromFlowMatch(pktCtx.origMatch)) should not be null
                    restart = false
                    throw new NotYetException(Future.successful(null))
                }
                pktCtx.traceTx.get(TraceKey.fromFlowMatch(pktCtx.origMatch)) should be (null)
                AddVirtualWildcardFlow
            }, packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(Array(makePacket(500)))
            pktCtxs.size() should be (3)
            val pktCtx = pktCtxs.pop()
            pktCtx.runs should be (3)
            pktCtx should be (pktCtxs.pop())
            pktCtx should be (pktCtxs.pop())
        }

        scenario("The trace request should not persist across different packets") {
            var trace = true
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                AddVirtualWildcardFlow
            }, packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(Array(makePacket(500), makePacket(501)))
            pktCtxs.size() should be (3)
            val tracedPktCtx = pktCtxs.pop()
            tracedPktCtx should be (pktCtxs.pop())
            tracedPktCtx.traceTx.get(TraceKey.fromFlowMatch(tracedPktCtx.origMatch)) should be (null)
            val nonTracePktCtx = pktCtxs.pop()
            nonTracePktCtx.traceTx.get(TraceKey.fromFlowMatch(nonTracePktCtx.origMatch)) should be (null)
            nonTracePktCtx.traceTx.get(TraceKey.fromFlowMatch(tracedPktCtx.origMatch)) should be (null)
        }

        scenario("The trace request should not persist across different packets when an exception is thrown") {
            var trace = true
            val requestId = UUID.randomUUID
            val pktCtxs = new LinkedList[PacketContext]()
            val wkfl = packetWorkflow(Map(42 -> port1), workflowTrap = pktCtx => {
                if (trace) {
                    trace = false
                    pktCtx.enableTracing(requestId)
                    throw TraceRequiredException
                }
                throw new Exception()
            }, packetCtxTrap = pktCtxs)
            wkfl ! PacketWorkflow.HandlePackets(Array(makePacket(500), makePacket(501)))
            pktCtxs.size() should be (3)
            val tracedPktCtx = pktCtxs.pop()
            tracedPktCtx should be (pktCtxs.pop())
            tracedPktCtx.traceTx.get(TraceKey.fromFlowMatch(tracedPktCtx.origMatch)) should be (null)
            val nonTracePktCtx = pktCtxs.pop()
            nonTracePktCtx.traceTx.get(TraceKey.fromFlowMatch(nonTracePktCtx.origMatch)) should be (null)
            nonTracePktCtx.traceTx.get(TraceKey.fromFlowMatch(tracedPktCtx.origMatch)) should be (null)
        }
    }

    feature("Ingress/Egress matching") {
        scenario("Trace generates flow state messages") {
            val requestId1 = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)), requestId1)
            val frame = makeFrame(500)
            val pktCtx = packetContextFor(frame, port1)
            pktCtx.tracingEnabled(requestId1) shouldBe false
            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) shouldBe true
            }
            val key = TraceKey.fromFlowMatch(
                FlowMatches.fromEthernetPacket(frame))
            pktCtx.traceTx.get(key) should not be null
            pktCtx.traceTx.get(key)
                .containsRequest(requestId1) shouldBe true
        }

        scenario("Trace gets enabled on egress host (no tunnel key hint)") {
            val requestId1 = UUID.randomUUID
            newTraceRuleOnChain(chain, 1,
                                newCondition(tpDst = Some(500)),
                                requestId1)

            val frame = makeFrame(500)
            val key = TraceKey.fromFlowMatch(
                FlowMatches.fromEthernetPacket(frame))
            val context = new TraceContext
            context.enable(UUID.randomUUID)
            context.addRequest(requestId1)
            table.putAndRef(key, context)

            val pktCtx = packetContextFor(frame, port1)
            pktCtx.tracingEnabled(requestId1) shouldBe false

            try {
                simulate(pktCtx)
                fail("Should have thrown an exception")
            } catch {
                case TraceRequiredException =>
                    pktCtx.log should be (PacketContext.traceLog)
                    pktCtx.tracingEnabled(requestId1) shouldBe true
            }
            pktCtx.traceTx.size should be (0)
            pktCtx.traceFlowId should be (context.flowTraceId)

            // try with similar, but slightly different packet
            // shouldn't match
            val frame2 = makeFrame(500, 10001)
            val pktCtx2 = packetContextFor(frame2, port1)
            pktCtx2.tracingEnabled(requestId1) shouldBe false
            try {
                simulate(pktCtx2)
            } catch {
                case TraceRequiredException =>
                    pktCtx2.log should be (PacketContext.traceLog)
                    pktCtx2.tracingEnabled(requestId1) shouldBe true
            }
            pktCtx2.traceTx.size should be (1)
            pktCtx2.traceFlowId should not be context.flowTraceId
        }
    }
}
