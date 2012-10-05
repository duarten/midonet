/**
 * TestZebraServer.scala - Tests for Zebra server classes.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.quagga

import java.io.{DataInputStream, DataOutputStream, File}
import java.net.Socket
import java.util.UUID

import org.apache.zookeeper.CreateMode
import org.junit.{Assert, After, AfterClass, Before, BeforeClass, Test, Ignore}
import org.junit.Assert._
import org.newsclub.net.unix.{AFUNIXServerSocket, AFUNIXSocket,
                              AFUNIXSocketAddress}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

import com.midokura.midolman.Setup
import com.midokura.midolman.state.zkManagers._
import com.midokura.midolman.state.{ZkPathManager, MockDirectory, PortDirectory}
import com.midokura.midonet.cluster.data.BGP
import com.midokura.packets.{IntIPv4, IPv4, Net}

/**
 * Test for ZebraServer using Open vSwitch database connection.
 */
object TestZebraServer {
    private final val log = LoggerFactory.getLogger(classOf[TestZebraServer])

    final val bridgeName = "testzebra"
    final val bridgeExtIdKey = "midolman-vnet"
    final val bridgeExtIdValue = "f5451278-fddd-8b9c-d658-b167aa6c00cc"
    final val bridgeId: Long = 0x15b138e7fa339bbcL

    private final val portServiceExtIdKey = "midolman_port_service"
    private final val portIdExtIdKey = "midolman_port_id"
    private final val portServiceExtId = "bgp"

    private final var zebra: ZebraServer = _
    private final var client: Socket = _

    private final var bgpMgr: BgpZkManager = _
    private final var portMgr: PortZkManager = _
    private final var routeMgr: RouteZkManager = _
    private final var routerMgr: RouterZkManager = _

    private final val portName = "testbgp0"
    private final var portId: UUID = _
    private final var portConfig: PortDirectory.MaterializedRouterPortConfig = _
    private final val portNwAddr = "192.168.10.0"
    private final val portNwLength = 30
    private final val portAddr = "192.168.10.2"
    private final val peerAddr = "192.168.10.1"
    private final val localAs = 65104
    private final val peerAs = 12345

    private final var bgpConfig: BGP = _
    private final var bgpId: UUID = _

    @AfterClass
    def finalizeTest() {
        //disconnectFromOVSDB
    }

    @BeforeClass
    def initializeTest() {
        //connectToOVSDB
        log.debug("Successfully connected to OVSDB.")

        val dir = new MockDirectory
        val basePath = "/midolman"
        val pathMgr = new ZkPathManager(basePath)
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.createZkDirectoryStructure(dir, basePath)
        bgpMgr = new BgpZkManager(dir, basePath)
        portMgr = new PortZkManager(dir, basePath)
        routeMgr = new RouteZkManager(dir, basePath)
        routerMgr = new RouterZkManager(dir, basePath)

        // Create a provider router.
        val routerId = routerMgr.create

        // Create a materialized router port config.
        portConfig = new PortDirectory.MaterializedRouterPortConfig(
            routerId, Net.convertStringAddressToInt(portNwAddr),
            portNwLength, Net.convertStringAddressToInt(portAddr), null, null,
            null)
        portId = portMgr.create(portConfig)

        // Create a BGP config.
        bgpConfig = new BGP()
            .setId(portId).setLocalAS(localAs).setPeerAS(peerAs)
            .setPeerAddr(IntIPv4.fromString(peerAddr))
        //TODO(pino): Commit 7c9d8fa50a2361e2a78fb162924cdd4f7a6308ef broke this
        //XXX: bgpId = bgpMgr.create(bgpConfig)
    }
}

@Ignore
class TestZebraServer {
    import TestZebraServer._
    import ZebraProtocol._

    @After
    def stopZebraServer() {
        client.close()
        zebra.stop
    }

    @Before
    def connectZebraServer() {
        val socketFile = File.createTempFile("testzebra", ".sock")
        socketFile.delete()
        val server = AFUNIXServerSocket.newInstance
        val address = new AFUNIXSocketAddress(socketFile)
        zebra = new ZebraServer(
            //server,
            address,
            new ZebraProtocolHandler {
                def removeRoute(ribType: RIBType.Value, destination: IntIPv4,
                                gateway: IntIPv4) {}

                def addRoute(ribType: RIBType.Value, destination: IntIPv4,
                             gateway: IntIPv4) {}
            },
            IntIPv4.fromString("0.0.1.1"), "midobgp33")
        client = AFUNIXSocket.newInstance

        try {
            zebra.start
            client.connect(address)
            // Wait until client connects to the server.
            Thread.sleep(10)
        } catch {
            case e: Exception => {
                Assert.fail("couldn't connect to ZebraServer: " + e)
            }
        }
    }

    /*
    @Test
    def testInterfaceAdd() {
        log.debug("testInterfaceAdd")

        val out = new DataOutputStream(client.getOutputStream)
        val in = new DataInputStream(client.getInputStream)
        sendHeader(out, ZebraInterfaceAdd, 0)
        Thread.sleep(10)
        // There should be no inputs available on the client side because
        // there is no service ports.
        assertEquals(0, in.available)

        try {
            // Add a BGP service port.
            var portBuilder = ovsdb.addInternalPort(bridgeId, portName)
            portBuilder.externalId(portServiceExtIdKey, portServiceExtId)
            portBuilder.externalId(portIdExtIdKey, portId.toString)
            portBuilder.build()
            assertTrue(ovsdb.hasPort(portName))

            sendHeader(out, ZebraInterfaceAdd, 0)
            Thread.sleep(100)
            // There should be response message from the server because there is
            // a BGP service port.
            assertTrue(in.available >= ZebraInterfaceAddSize)
            var response = recvHeader(in)
            var message = response._1
            var length = response._2
            assertEquals(ZebraInterfaceAdd, message)
            assertEquals(ZebraInterfaceAddSize, length)
            var payload = new Array[Byte](length)
            in.read(payload, 0, length)
            assertTrue(in.available >= ZebraInterfaceAddressAddSize)
            response = recvHeader(in)
            message = response._1
            length = response._2
            assertEquals(ZebraInterfaceAddressAdd, message)
            // Skip Ipv4MaxBytelen and Byte x 2 to get port address and
            // prefix length.
            in.skip(Ipv4MaxBytelen + 2)
            val zebraPortAddr = in.readInt
            assertEquals(Net.convertStringAddressToInt(portAddr), zebraPortAddr)
            val zebraNwLength = in.readByte
            assertEquals(portNwLength, zebraNwLength)
        } finally {
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        }
    }

    @Test
    def testInterfaceAddWithTwoPorts() {
        log.debug("testInterfaceAddWithTwoPorts")

        try {
            val out = new DataOutputStream(client.getOutputStream)
            val in = new DataInputStream(client.getInputStream)
            // Add a BGP service port.
            var portBuilder = ovsdb.addInternalPort(bridgeId, portName)
            portBuilder.externalId(portServiceExtIdKey, portServiceExtId)
            portBuilder.externalId(portIdExtIdKey, portId.toString)
            portBuilder.build()

            assertTrue(ovsdb.hasPort(portName))
            // Add another BGP service port.
            val anotherPortName = "testbgp1"
            portBuilder = ovsdb.addInternalPort(bridgeId, anotherPortName)
            portBuilder.externalId(portServiceExtIdKey, portServiceExtId)
            portBuilder.externalId(portIdExtIdKey, portId.toString)
            portBuilder.build()

            assertTrue(ovsdb.hasPort(anotherPortName))
            sendHeader(out, ZebraInterfaceAdd, 0)
            Thread.sleep(10)
            // There should be no inputs available on the client side because
            // multipe service ports are not supported.
            assertEquals(0, in.available)
        } finally {
            for (portName <- ovsdb.getPortNamesByExternalId(
                portServiceExtIdKey, portServiceExtId)) {
                ovsdb.delPort(portName)
            }
            val portNames = ovsdb.getPortNamesByExternalId(portServiceExtIdKey,
                                                           portServiceExtId)
            assertEquals(0, portNames.size)
        }
    }

    @Test
    def testIpv4RouteAddAndDelete() {
        log.debug("testIpv4RouteAddAndDelte")

        try {
            val out = new DataOutputStream(client.getOutputStream)
            val in = new DataInputStream(client.getInputStream)
            // Add a BGP service port. This is required to add a route.
            val portBuilder = ovsdb.addInternalPort(bridgeId, portName)

            portBuilder.externalId(portServiceExtIdKey, portServiceExtId)
            portBuilder.externalId(portIdExtIdKey, portId.toString)
            portBuilder.build()
            assertTrue(ovsdb.hasPort(portName))
            sendHeader(out, ZebraInterfaceAdd, 0)
            // There should be no route in ZK.
            assertEquals(0, routeMgr.listPortRoutes(portId).size)

            sendHeader(out, ZebraIpv4RouteAdd, 0)
            // RIB type
            out.write(ZebraRouteBgp)
            // flags
            out.write(0)
            // message
            out.write(ZAPIMessageNextHop)
            val prefix = Net.convertStringAddressToInt("10.8.8.20")
            val prefixLen = 29
            // prefix length
            out.write(prefixLen)
            // prefix
            out.write(IPv4.toIPv4AddressBytes(prefix), 0, ((prefixLen + 7) / 8))
            // number of next hop
            out.write(1)
            // next hop type
            out.write(ZebraNextHopIpv4)
            out.write(IPv4.toIPv4AddressBytes(peerAddr))
            Thread.sleep(100)
            // Check whether the route is in ZK.
            var routes = routeMgr.listPortRoutes(portId)
            assertEquals(1, routes.size)
            val route = routeMgr.get(routes(0))
            assertEquals(prefix, route.dstNetworkAddr)
            assertEquals(prefixLen, route.dstNetworkLength)
            assertEquals(Net.convertStringAddressToInt(peerAddr),
                         route.nextHopGateway)

            // Delete the route
            sendHeader(out, ZebraIpv4RouteDelete, 0)
            // RIB type
            out.write(ZebraRouteBgp)
            // flags
            out.write(0)
            // message
            out.write(ZAPIMessageNextHop)
            // prefix length
            out.write(prefixLen)
            // prefix
            out.write(IPv4.toIPv4AddressBytes(prefix), 0,
                      ((prefixLen + 7) / 8))
            // number of next hop
            out.write(1)
            // next hop type
            out.write(ZebraNextHopIpv4)
            out.write(IPv4.toIPv4AddressBytes(peerAddr))
            Thread.sleep(100)
            // Check whether the route is deleted.
            routes = routeMgr.listPortRoutes(portId)
            assertEquals(0, routes.size)
        } finally {
            ovsdb.delPort(portName)
            assertFalse(ovsdb.hasPort(portName))
        }
    }*/

    @Test
    def testRouterIdAdd() {
        log.debug("testRouterIdAdd")
        val out = new DataOutputStream(client.getOutputStream)
        val in = new DataInputStream(client.getInputStream)
        sendHeader(out, ZebraRouterIdAdd, 0)
        Thread.sleep(1000)
        assertTrue(in.available != 0)
        val response = recvHeader(in)
        val message = response._1
        val length = response._2
        assertEquals(ZebraRouterIdUpdate, message)
        assertEquals(ZebraRouterIdUpdateSize, length)
        assertEquals(AF_INET, in.readByte)
    }

    def testUnsupportedCmds(message: Short) {
        val out = new DataOutputStream(client.getOutputStream)
        val in = new DataInputStream(client.getInputStream)
        sendHeader(out, message, 0)
        Thread.sleep(10)
        // If the command isn't implemented, the server shouldn't write any
        // data back.
        //log.info("in.available {}", in.available)
        assertEquals(0, in.available)
    }

    @Test
    def testInterfaceDelete() {
        log.debug("testInterfaceDelete")
        testUnsupportedCmds(ZebraInterfaceDelete)
    }

    @Test
    def testInterfaceAddressAdd() {
        log.debug("testInterfaceAddressAdd")
        testUnsupportedCmds(ZebraInterfaceAddressAdd)
    }

    @Test
    def testInterfaceAddressDelete() {
        log.debug("testInterfaceAddressDelete")
        testUnsupportedCmds(ZebraInterfaceAddressDelete)
    }

    @Test
    def testInterfaceUp() {
        log.debug("testInterfaceUp")
        testUnsupportedCmds(ZebraInterfaceUp)
    }

    @Test
    def testInterfaceDown() {
        log.debug("testInterfaceDown")
        testUnsupportedCmds(ZebraInterfaceDown)
    }

    @Test
    def testIpv6RouteAdd() {
        log.debug("testIpv6RouteAdd")
        testUnsupportedCmds(ZebraIpv6RouteAdd)
    }

    @Test
    def testIpv6RouteDelete() {
        log.debug("testIpv6RouteDelete")
        testUnsupportedCmds(ZebraIpv6RouteDelete)
    }

    @Test
    def testRedistributeAdd() {
        log.debug("testRedistributeAdd")
        testUnsupportedCmds(ZebraRedistributeAdd)
    }

    @Test
    def testRedistributeDelete() {
        log.debug("testRedistributeDelete")
        testUnsupportedCmds(ZebraRedistributeDelete)
    }

    @Test
    def testRedistributeDefaultAdd() {
        log.debug("testRedistributeDefaultAdd")
        testUnsupportedCmds(ZebraRedistributeDefaultAdd)
    }

    @Test
    def testRedistributeDefaultDelete() {
        log.debug("testRedistributeDefaultDelete")
        testUnsupportedCmds(ZebraRedistributeDefaultDelete)
    }

    @Test
    def testIpv4NextHopLookup() {
        log.debug("testIpv4NextHopLookup")
        testUnsupportedCmds(ZebraIpv4NextHopLookup)
    }

    @Test
    def testIpv6NextHopLookup() {
        log.debug("testIpv6NextHopLookup")
        testUnsupportedCmds(ZebraIpv6NextHopLookup)
    }

    @Test
    def testIpv4ImportLookup() {
        log.debug("testIpv4ImportLookup")
        testUnsupportedCmds(ZebraIpv4ImportLookup)
    }

    @Test
    def testIpv6ImportLookup() {
        log.debug("testIpv6ImportLookup")
        testUnsupportedCmds(ZebraIpv6ImportLookup)
    }

    @Test
    def testInterfaceRename() {
        log.debug("testInterfaceRename")
        testUnsupportedCmds(ZebraInterfaceRename)
    }

    @Test
    def testRouterIdDelete() {
        log.debug("testRouterIdDelete")
        testUnsupportedCmds(ZebraRouterIdDelete)
    }

    @Test
    def testRouterIdUpdate() {
        log.debug("testRouterIdUpdate")
        testUnsupportedCmds(ZebraRouterIdUpdate)
    }
}
