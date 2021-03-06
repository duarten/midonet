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

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.annotation.JsonError;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;

@ZoomClass(clazz = Topology.Route.class)
public class Route extends UriResource {

    private static final UUID NO_ID =
        new UUID(0xffffffffffffffffL, 0xffffffffffffffffL);

    @ZoomEnum(clazz = Topology.Route.NextHop.class)
    public enum NextHop {
        @ZoomEnumValue(value = "PORT")Normal,
        @ZoomEnumValue(value = "BLACKHOLE")BlackHole,
        @ZoomEnumValue(value = "REJECT")Reject,
        @ZoomEnumValue(value = "LOCAL")Local
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @ZoomField(name = "next_hop_port_id", converter = UUIDUtil.Converter.class)
    public UUID nextHopPort;

    @ZoomField(name = "attributes")
    public String attributes;

    @JsonIgnore
    @ZoomField(name = "dst_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> dstSubnet;

    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String dstNetworkAddr;

    @Min(0)
    @Max(32)
    public int dstNetworkLength;

    @JsonIgnore
    @ZoomField(name = "src_subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> srcSubnet;

    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String srcNetworkAddr;

    @Min(0)
    @Max(32)
    public int srcNetworkLength;

    @ZoomField(name = "next_hop_gateway", converter = IPAddressUtil.Converter.class)
    @Pattern(regexp = IPv4.regex)
    public String nextHopGateway;

    public boolean learned;

    @NotNull
    @ZoomField(name = "next_hop")
    @JsonError(message = MessageProperty.ROUTE_NEXT_HOP_PORT_INVALID)
    public NextHop type;

    @ZoomField(name = "weight")
    @Min(0)
    public int weight;

    public Route() { }

    public Route(URI baseUri) {
        setBaseUri(baseUri);
    }

    public Route(String srcNetworkAddr, int srcNetworkLength,
                 String dstNetworkAddr, int dstNetworkLength,
                 NextHop nextHop, UUID nextHopPort, String nextHopGateway,
                 int weight, UUID routerId, boolean learned) {
        this.id = UUID.randomUUID();
        this.srcNetworkAddr = srcNetworkAddr;
        this.srcNetworkLength = srcNetworkLength;
        this.dstNetworkAddr = dstNetworkAddr;
        this.dstNetworkLength = dstNetworkLength;
        this.type = nextHop;
        this.nextHopPort = nextHopPort;
        this.nextHopGateway = nextHopGateway;
        this.weight = weight;
        this.routerId = routerId;
        this.learned = learned;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.ROUTES, id);
    }

    public URI getRouter() {
        return absoluteUri(ResourceUris.ROUTERS, routerId);
    }

    @JsonIgnore
    @Override
    public void afterFromProto(Message message) {
        if (null != dstSubnet) {
            dstNetworkAddr = dstSubnet.getAddress().toString();
            dstNetworkLength = dstSubnet.getPrefixLen();
        }
        if (null != srcSubnet) {
            srcNetworkAddr = srcSubnet.getAddress().toString();
            srcNetworkLength = srcSubnet.getPrefixLen();
        }
    }

    @JsonIgnore
    @Override
    public void beforeToProto() {
        if (StringUtils.isNotEmpty(dstNetworkAddr)) {
            dstSubnet =
                IPSubnet.fromString(dstNetworkAddr + "/" + dstNetworkLength);
        }
        if (StringUtils.isNotEmpty(srcNetworkAddr)) {
            srcSubnet =
                IPSubnet.fromString(srcNetworkAddr + "/" + srcNetworkLength);
        }
    }

    @JsonIgnore
    public void create(UUID routerId) {
        if (null == id) {
            id = UUID.randomUUID();
        }
        this.routerId = routerId;
    }

    public static Route fromLearned(org.midonet.midolman.layer3.Route from) {
        Route route = new Route();
        route.id = NO_ID;
        route.dstNetworkAddr = IPv4Addr.apply(from.dstNetworkAddr).toString();
        route.dstNetworkLength = from.dstNetworkLength;
        route.srcNetworkAddr = IPv4Addr.apply(from.srcNetworkAddr).toString();
        route.srcNetworkLength = from.srcNetworkLength;
        route.nextHopGateway = IPv4Addr.apply(from.nextHopGateway).toString();
        route.weight = from.weight;
        route.routerId = from.routerId;
        route.nextHopPort = from.nextHopPort;
        route.type = NextHop.Normal;
        route.learned = true;
        return route;
    }

}