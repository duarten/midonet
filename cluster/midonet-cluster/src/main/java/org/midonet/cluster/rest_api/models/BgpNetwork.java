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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPSubnet;

@ZoomClass(clazz = Topology.BgpNetwork.class)
public class BgpNetwork extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @JsonIgnore
    @ZoomField(name = "subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> subnet;

    @JsonIgnore
    @ZoomField(name = "router_id", converter = UUIDUtil.Converter.class)
    public UUID routerId;

    @NotNull
    public String subnetAddress;

    @Min(0)
    @Max(32)
    public byte subnetPrefix;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BGP_NETWORKS, id);
    }

    public URI getRouter() {
        return absoluteUri(ResourceUris.ROUTERS, routerId);
    }

    @Override
    @JsonIgnore
    public void afterFromProto(Message proto) {
        subnetAddress = subnet != null ? subnet.getAddress().toString() : null;
        subnetPrefix = subnet != null ? (byte)subnet.getPrefixLen() : 0;
    }

    @Override
    @JsonIgnore
    public void beforeToProto() {
        subnet = subnetAddress != null ?
                 IPSubnet.fromString(subnetAddress + "/" + subnetPrefix) : null;
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void create(UUID routerId) {
        create();
        this.routerId = routerId;
    }

    @JsonIgnore
    public void update(BgpNetwork from) {
        routerId = from.routerId;
    }

}
