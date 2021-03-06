/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.topology

import java.util.UUID

import org.midonet.midolman.BackChannelMessage
import org.midonet.util.collection.IPv4InvalidationArray

import scala.collection.{Set => ROSet}

import com.typesafe.scalalogging.Logger

import org.midonet.cluster.Client
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.layer3.{Route, RoutingTableIfc}
import org.midonet.midolman.simulation.Router
import org.midonet.midolman.simulation.Router.{Config, RoutingTable, TagManager}
import org.midonet.midolman.state.ArpCache
import org.midonet.midolman.topology.RouterManager._
import org.midonet.midolman.topology.VirtualTopologyActor.InvalidateFlowsByTag
import org.midonet.midolman.topology.builders.RouterBuilderImpl
import org.midonet.odp.FlowMatch
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.functors.Callback0

class RoutingTableWrapper[IP <: IPAddr](val rTable: RoutingTableIfc[IP])
    extends RoutingTable {

    /**
     * TODO (ipv6): de facto implementation for ipv4, that explains the casts at
     * this point.
     */
    def lookup(wmatch: FlowMatch): java.util.List[Route] =
        rTable.lookup(wmatch.getNetworkSrcIP.asInstanceOf[IP],
                      wmatch.getNetworkDstIP.asInstanceOf[IP])

    /**
     * TODO (ipv6): de facto implementation for ipv4, that explains the casts at
     * this point.
     */
    def lookup(wmatch: FlowMatch, logger: Logger): java.util.List[Route] =
        rTable.lookup(wmatch.getNetworkSrcIP.asInstanceOf[IP],
                      wmatch.getNetworkDstIP.asInstanceOf[IP],
                      logger.underlying)
}

object RouterManager {
    val Name = "RouterManager"

    case class TriggerUpdate(cfg: Router.Config, arpCache: ArpCache,
                             rTable: RoutingTableWrapper[IPv4Addr])

    case class InvalidateFlows(routerId: UUID,
                               addedRoutes: ROSet[Route],
                               deletedRoutes: ROSet[Route]) extends BackChannelMessage

    // these msg are used for testing
    case class RouterInvTrieTagCountModified(dstIp: IPAddr, count: Int)

}

/**
 * TODO (galo, ipv6) this class is still heavily dependant on IPv4. There are
 * two points to tackle:
 * - Routes and Invalidation Tries. This should be rewritten with an agnostic
 * version so that it can work with both IP versions. A decent suggestion might
 * be to offer a Trie for byte[] since both versions can easily be translated
 * into a block of bytes.
 * - ARP: this is not used in IPv6, an idea can be to make this a generic
 * version for IPv6, then extend adding IPv4 and IPv6 "toolsets" to each.
 */
class RouterManager(id: UUID, val client: Client, val config: MidolmanConfig)
        extends DeviceWithChains {
    import context.system

    override def logSource = s"org.midonet.devices.router.router-$id"

    protected var cfg: Config = null
    private var changed = false
    private var rTable: RoutingTableWrapper[IPv4Addr] = null
    private var arpCache: ArpCache = null

    def topologyReady() {
        log.debug("Sending a Router to the VTA")

        val router = new Router(id, cfg, rTable, new TagManagerImpl, arpCache)

        // Not using context.actorFor("..") because in tests it will
        // bypass the probes and make it harder to fish for these messages
        // Should this need to be decoupled from the VTA, the parent
        // actor reference should be passed in the constructor
        VirtualTopologyActor ! router

        if (changed) {
            VirtualTopologyActor ! InvalidateFlowsByTag(router.deviceTag)
            changed = false
        }
    }

    override def preStart() {
        client.getRouter(id, new RouterBuilderImpl(id, self))
    }

    override def receive = super.receive orElse {
        case TriggerUpdate(newCfg, newArpCache, newRoutingTable) =>
            log.debug("TriggerUpdate with {} {} {}",
                      newCfg, newArpCache, newRoutingTable)

            if (newCfg != cfg && cfg != null)
                changed = true

            cfg = newCfg

            if (arpCache == null && newArpCache != null) {
                arpCache = newArpCache
            } else if (arpCache != newArpCache) {
                throw new RuntimeException("Trying to re-set the arp cache")
            }
            rTable = newRoutingTable

            prefetchTopology(loadBalancer(newCfg.loadBalancer))

        case m: InvalidateFlows => VirtualTopologyActor ! m
    }

    private class TagManagerImpl extends TagManager {
        def addIPv4Tag(dstIp: IPv4Addr, matchLength: Int) {
            val refs = IPv4InvalidationArray.current.ref(dstIp.toInt, matchLength)
            log.debug(s"Increased ref count ip prefix $dstIp/28 to $refs")
            context.system.eventStream.publish(
                new RouterInvTrieTagCountModified(dstIp, refs))

        }

        def getFlowRemovalCallback(dstIp: IPv4Addr) = new Callback0 {
            override def call() {
                val refs = IPv4InvalidationArray.current.unref(dstIp.toInt)
                context.system.eventStream.publish(
                    new RouterInvTrieTagCountModified(dstIp, refs))
            }
        }
    }

}
