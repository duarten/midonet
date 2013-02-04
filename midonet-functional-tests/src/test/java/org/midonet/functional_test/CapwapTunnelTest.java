/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.UUID;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.client.resource.*;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.zones.CapwapTunnelZoneHost;
import org.midonet.cluster.data.zones.CapwapTunnelZone;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;
import org.midonet.packets.Ethernet;
import org.midonet.packets.IPv4;
import org.midonet.packets.UDP;
import org.midonet.packets.IPacket;
import org.midonet.packets.GRE;
import org.midonet.packets.Data;
import org.midonet.packets.CAPWAP;
import org.midonet.packets.MalformedPacketException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class CapwapTunnelTest extends BaseTunnelTest {
    private final static Logger log = LoggerFactory.getLogger(CapwapTunnelTest.class);

    protected void setUpTunnelZone() throws Exception {
        // Create a capwap tunnel zone
        log.info("Creating tunnel zone.");
        TunnelZone tunnelZone =
                apiClient.addCapwapTunnelZone().name("CapwapZone").create();

        // add this host to the capwap zone
        log.info("Adding this host to tunnel zone.");
        tunnelZone.addTunnelZoneHost().
                hostId(thisHostId).
                ipAddress(physTapLocalIp.toUnicastString()).
                create();

        log.info("Adding remote host to tunnelzone");
        tunnelZone.addTunnelZoneHost().
                hostId(remoteHostId).
                ipAddress(physTapRemoteIp.toUnicastString()).
                create();
    }

    @Override
    protected IPacket matchTunnelPacket(TapWrapper device,
                                      MAC fromMac, IntIPv4 fromIp,
                                      MAC toMac, IntIPv4 toIp)
                                throws MalformedPacketException {
        byte[] received = device.recv();
        assertNotNull(String.format("Expected packet on %s", device.getName()),
                      received);

        Ethernet eth = Ethernet.deserialize(received);
        log.info("got packet on " + device.getName() + ": " + eth.toString());

        assertEquals("source ethernet address",
            fromMac, eth.getSourceMACAddress());
        assertEquals("destination ethernet address",
            toMac, eth.getDestinationMACAddress());
        assertEquals("ethertype", IPv4.ETHERTYPE, eth.getEtherType());

        assertTrue("payload is IPv4", eth.getPayload() instanceof IPv4);
        IPv4 ipPkt = (IPv4) eth.getPayload();
        assertEquals("source ipv4 address",
            fromIp.addressAsInt(), ipPkt.getSourceAddress());
        assertEquals("destination ipv4 address",
            toIp.addressAsInt(), ipPkt.getDestinationAddress());

        assertTrue("payload is UDP", ipPkt.getPayload() instanceof UDP);
        UDP udpPkt = (UDP) ipPkt.getPayload();
        assertTrue("payload is Data", udpPkt.getPayload() instanceof Data);
        Data data = (Data) udpPkt.getPayload();

        CAPWAP capwap = new CAPWAP();
        capwap.deserialize(ByteBuffer.wrap(data.serialize()));

        return capwap.getPayload();
    }

    // TODO(guillermo) use the CAPWAP packet class to craft the packets.
    @Override
    protected byte[] buildEncapsulatedPacketForPort() {
        byte[] capwapFrame = {
            (byte)0x22, (byte)0xbb, (byte)0xbb, (byte)0xdd,
            (byte)0xdd, (byte)0xdd, (byte)0x22, (byte)0xaa,
            (byte)0xaa, (byte)0xcc, (byte)0xcc, (byte)0xcc,
            (byte)0x08, (byte)0x00, (byte)0x45, (byte)0x00,
            (byte)0x00, (byte)0x65, (byte)0x18, (byte)0x25,
            (byte)0x00, (byte)0x00, (byte)0x40, (byte)0x11,
            (byte)0x9e, (byte)0x75, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x02, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x01, (byte)0xe6, (byte)0x01,
            (byte)0xe6, (byte)0x02, (byte)0x00, (byte)0x51,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x28,
            (byte)0x3c, (byte)0x20, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x0b, (byte)0x80,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x02, (byte)0x22, (byte)0x55,
            (byte)0x55, (byte)0x11, (byte)0x11, (byte)0x11,
            (byte)0x22, (byte)0x33, (byte)0x33, (byte)0x44,
            (byte)0x44, (byte)0x44, (byte)0x08, (byte)0x00,
            (byte)0x45, (byte)0x00, (byte)0x00, (byte)0x27,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x40, (byte)0x11, (byte)0x2b, (byte)0x71,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x02,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x01,
            (byte)0x26, (byte)0x94, (byte)0x09, (byte)0x29,
            (byte)0x00, (byte)0x13, (byte)0x29, (byte)0xfd,
            (byte)0x54, (byte)0x68, (byte)0x65, (byte)0x20,
            (byte)0x50, (byte)0x61, (byte)0x79, (byte)0x6c,
            (byte)0x6f, (byte)0x61, (byte)0x64};
        writeOnPacket(capwapFrame, physTapLocalMac.getAddress(), 0);
        return capwapFrame;
    }

    @Override
    protected byte[] buildEncapsulatedPacketForPortSet() {
        byte[] capwapFrame = {
            (byte)0x22, (byte)0xbb, (byte)0xbb, (byte)0xdd,
            (byte)0xdd, (byte)0xdd, (byte)0x22, (byte)0xaa,
            (byte)0xaa, (byte)0xcc, (byte)0xcc, (byte)0xcc,
            (byte)0x08, (byte)0x00, (byte)0x45, (byte)0x00,
            (byte)0x00, (byte)0x65, (byte)0x18, (byte)0x25,
            (byte)0x00, (byte)0x00, (byte)0x40, (byte)0x11,
            (byte)0x9e, (byte)0x75, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x02, (byte)0x0a, (byte)0xf5,
            (byte)0xd7, (byte)0x01, (byte)0xe6, (byte)0x01,
            (byte)0xe6, (byte)0x02, (byte)0x00, (byte)0x51,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x28,
            (byte)0x3c, (byte)0x20, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x0b, (byte)0x80,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x01, (byte)0x22, (byte)0x55,
            (byte)0x55, (byte)0x11, (byte)0x11, (byte)0x11,
            (byte)0x22, (byte)0x33, (byte)0x33, (byte)0x44,
            (byte)0x44, (byte)0x44, (byte)0x08, (byte)0x00,
            (byte)0x45, (byte)0x00, (byte)0x00, (byte)0x27,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x40, (byte)0x11, (byte)0x2b, (byte)0x71,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x02,
            (byte)0xc0, (byte)0xa8, (byte)0xe7, (byte)0x01,
            (byte)0x26, (byte)0x94, (byte)0x09, (byte)0x29,
            (byte)0x00, (byte)0x13, (byte)0x29, (byte)0xfd,
            (byte)0x54, (byte)0x68, (byte)0x65, (byte)0x20,
            (byte)0x50, (byte)0x61, (byte)0x79, (byte)0x6c,
            (byte)0x6f, (byte)0x61, (byte)0x64};
        writeOnPacket(capwapFrame, physTapLocalMac.getAddress(), 0);
        return capwapFrame;
    }
}

