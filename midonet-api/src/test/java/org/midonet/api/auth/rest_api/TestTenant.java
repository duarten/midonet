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
package org.midonet.api.auth.rest_api;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.MockAuthService;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.models.Tenant;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.midonet.api.rest_api.FuncTest._injector;
import static org.midonet.api.rest_api.FuncTest.appDesc;
import static org.midonet.api.rest_api.FuncTest.isCompatApiEnabled;

/**
 * Tests tenant API operations.  Currently assumes MockAuth is being used.
 */
public class TestTenant extends JerseyTest {

    private Topology topology;
    private DtoWebResource dtoResource;

    public TestTenant() {
        super(appDesc);
    }

    private void createActualTenants(int count) {
        Topology.Builder builder = new Topology.Builder(dtoResource);

        for (int i = 0 ; i < count ; i++) {
            String tenantId = Integer.toString(i);
            if (isCompatApiEnabled()) {
                // In the new storage stack we don't store tenants in MidoNet
                // and instead fetch them directly from the AuthService, so
                // let's add them there.
                AuthService as = _injector.getInstance(AuthService.class);
                ((MockAuthService)as).addTenant(tenantId, tenantId);
            } else {
                // TODO: remove when old stack is removed
                DtoBridge bridge = new DtoBridge();
                bridge.setName(tenantId);
                bridge.setTenantId(tenantId);
                builder.create(tenantId, bridge);
            }
        }

        topology = builder.build();
    }

    private static Tenant getExpectedTenant(URI baseUri, String id) {
        return new Tenant(baseUri, id, id);
    }

    private static List<Tenant> getExpectedTenants(URI baseUri,
                                                   int startTenantId,
                                                   int endTenantId) {
        List<Tenant> tenants = new ArrayList<>();

        for (int i = startTenantId; i <= endTenantId; i++) {
            tenants.add(getExpectedTenant(baseUri, Integer.toString(i)));
        }

        return tenants;
    }

    @Before
    public void setUp() {
        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);
        createActualTenants(10);
    }

    @Test
    public void testDtoGeneratesUris() throws Exception {
        String id = "sometenant";
        Tenant t = new Tenant(topology.getApplication().getUri(),
                              id, id + "-name");
        assertEquals(t.getUri().toString(),
                     topology.getApplication().getUri() +
                     ResourceUris.TENANTS + "/" + id);
    }

    @Test
    public void testListTenants() throws Exception {

        // Get the expected list of Tenant objects
        DtoApplication app = topology.getApplication();
        List<Tenant> expected = getExpectedTenants(app.getUri(), 0, 9);

        // Get the actual Tenant objects
        Tenant[] actualRaw = dtoResource.getAndVerifyOk(app.getTenants(),
                VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
                Tenant[].class);
        // Fill in because the URI won't get deserialized
        for (Tenant t : actualRaw) {
            t.setBaseUri(topology.getApplication().getUri());
        }
        // Compare the actual and expected
        assertThat(expected, containsInAnyOrder(actualRaw));

        // Test that the URI for 'tenant' is correct in each item
        for (Tenant t : actualRaw) {

            // Construct the expected object
            Tenant expectedTenant = getExpectedTenant(app.getUri(), t.id);

            // Get the actual object
            Tenant actualTenant = dtoResource.getAndVerifyOk(t.getUri(),
                    VendorMediaType.APPLICATION_TENANT_JSON,
                    Tenant.class);

            // Compare
            assertThat(actualTenant, equalTo(expectedTenant));
        }
    }
}
