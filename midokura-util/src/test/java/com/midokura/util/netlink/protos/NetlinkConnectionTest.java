/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.modules.junit4.PowerMockRunner;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.reactor.Reactor;

/**
 * // TODO: Explain yourself.
 */
@RunWith(PowerMockRunner.class)
public class NetlinkConnectionTest {

    NetlinkConnection connection;

    NetlinkChannel channel = PowerMockito.mock(NetlinkChannel.class);
    Reactor reactor = PowerMockito.mock(Reactor.class);


    @Before
    public void setUp() throws Exception {

        Netlink.Address remote = new Netlink.Address(0);
        Netlink.Address local = new Netlink.Address(29000);

        PowerMockito.when(channel.getRemoteAddress())
                    .thenReturn(remote);

        PowerMockito.when(channel.getLocalAddress())
                    .thenReturn(local);

        connection = new NetlinkConnection(channel, reactor);
    }

    @Test
    public void testGetFamilyId() throws Exception {

        final byte[] responseBuffer = {
            (byte) 0xC0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x10, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x89, (byte) 0x27, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x02,
            (byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0x00, (byte) 0x02, (byte) 0x00,
            (byte) 0x6F, (byte) 0x76, (byte) 0x73, (byte) 0x5F, (byte) 0x64, (byte) 0x61,
            (byte) 0x74, (byte) 0x61, (byte) 0x70, (byte) 0x61, (byte) 0x74, (byte) 0x68,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x06, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x18, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x08, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x04, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x05, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x54, (byte) 0x00, (byte) 0x06, (byte) 0x00, (byte) 0x14, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x14, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x03, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00,
            (byte) 0x0E, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x14, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x00,
            (byte) 0x02, (byte) 0x00, (byte) 0x0B, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x24, (byte) 0x00, (byte) 0x07, (byte) 0x00, (byte) 0x20, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x02, (byte) 0x00,
            (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x11, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0x6F, (byte) 0x76, (byte) 0x73, (byte) 0x5F,
            (byte) 0x64, (byte) 0x61, (byte) 0x74, (byte) 0x61, (byte) 0x70, (byte) 0x61,
            (byte) 0x74, (byte) 0x68, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
        };

        PowerMockito.when(channel.read(Matchers.<ByteBuffer>any())).then(
            new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock invocation)
                    throws Throwable {
                    ByteBuffer result = ((ByteBuffer) invocation.getArguments()[0]);
                    result.put(responseBuffer);
                    return result.position();
                }
            });

        Future<Short> future = connection.getFamilyId("ovs_datapath");

        // fire the received message
        connection.handleEvent(null);

        // validate decoding
        assertThat("The future was completed",
                   future.isDone(), is(true));

        assertThat("The future was not canceled completed",
                   future.isCancelled(), is(false));

        assertThat("The datapath id was properly parsed from packet data",
                   future.get(), is((short)24));
    }

    @Test
    public void testParseErrorMessageThrowsException() throws Exception {

        final byte[] responseBuffer = {
            (byte)0x54, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x02, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x0F, (byte)0x6C, (byte)0x00, (byte)0x00, (byte)0xEA, (byte)0xFF,
            (byte)0xFF, (byte)0xFF, (byte)0x40, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x10, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x01, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x03, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
        };

        PowerMockito.when(channel.read(Matchers.<ByteBuffer>any())).then(
            new Answer<Object>() {
                @Override
                public Object answer(InvocationOnMock invocation)
                    throws Throwable {
                    ByteBuffer result = ((ByteBuffer) invocation.getArguments()[0]);
                    result.put(responseBuffer);
                    return result.position();
                }
            });

        Future<Short> future = connection.getFamilyId("ovs_datapath");

        // fire the received message
        connection.handleEvent(null);

        // validate decoding
        assertThat("The future was completed",
                   future.isDone(), is(true));

        assertThat("The future was canceled",
                   future.isCancelled(), is(false));

        try {
            assertThat("The datapath id was properly parsed from packet data",
                       future.get(), is((short) 24));

            assertThat("An exception should have been thrown", false);
        } catch (ExecutionException e) {
            assertThat("Cause is an IOException",
                       e.getCause(), instanceOf(IOException.class));
        }
    }
}
