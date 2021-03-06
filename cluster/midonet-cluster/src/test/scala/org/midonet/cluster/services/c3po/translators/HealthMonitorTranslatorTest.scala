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

package org.midonet.cluster.services.c3po.translators

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.HealthMonitor
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.util.UUIDUtil

class HealthMonitorTranslatorTestBase extends TranslatorTestBase {
    protected var translator: HealthMonitorTranslator = _

    before {
        initMockStorage()
        translator = new HealthMonitorTranslator(storage)
    }

    protected val hmId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val poolId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 2")
    private val healthMonitorCommonFlds = s"""
            id { $hmId }
            admin_state_up: true
            delay: 1
            max_retries: 10
            timeout: 30
            """
    protected val neutronHealthMonitor =
            nHealthMonitorFromTxt(healthMonitorCommonFlds)
    protected val midoHealthMonitorNoPool =
        mHealthMonitorFromTxt(healthMonitorCommonFlds)
    protected val midoHealthMonitorWithPool = mHealthMonitorFromTxt(
        healthMonitorCommonFlds + s"""
            pool_id { $poolId }
            """)
    protected val neutronHealthMonitorWithPool = nHealthMonitorFromTxt(
            healthMonitorCommonFlds + s"""
            pools {
                pool_id { $poolId }
            }
            """)
    protected val poolWithHmId = mPoolFromTxt(s"""
            id { $poolId }
            health_monitor_id: { $hmId }
            mapping_status: PENDING_CREATE
            """)
}

/**
 * Tests Neutron Health Monitor Create translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorCreateTest
        extends HealthMonitorTranslatorTestBase {

    val nHealthMonitor = neutronHealthMonitor

    "Neutron Health Monitor CREATE with no pool ID" should "fail" in {
        val ex = the [TranslationException] thrownBy
            translator.translate(neutron.Create(nHealthMonitor))
        ex.getMessage should include("Health monitor must have exactly one")
    }

    "CREATE for Neutron Health Monitor with a pool associated" should
    "create a HealthMonitor" in {
        bind(poolId, poolWithHmId)
        val midoOps = translator.translate(
            neutron.Create(neutronHealthMonitorWithPool))
        midoOps should contain inOrderOnly(
            midonet.Create(midoHealthMonitorNoPool),
            midonet.Update(poolWithHmId))
    }
}

/**
 * Tests Neutron Health Monitor Update translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorUpdateTest
        extends HealthMonitorTranslatorTestBase {

    private val updatedHealthMonitorCommonFlds = s"""
            id { $hmId }
            admin_state_up: false
            delay: 2
            max_retries: 20
            timeout: 60
            """
    private val nHealthMonitor =
            nHealthMonitorFromTxt(updatedHealthMonitorCommonFlds + s"""
                pools {
                    pool_id { $poolId }
                    status: "status"
                    status_description: "desc"
                }
                """)
    private val mHealthMonitor =
            mHealthMonitorFromTxt(updatedHealthMonitorCommonFlds + s"""
                pool_id { $poolId }
            """)

    "Neutron Health Monitor UPDATE" should "update a Midonet Health Monitor " +
    "except for its status and pool IDs" in {
        val midoOps = translator.translate(neutron.Update(nHealthMonitor))

        midoOps should contain only midonet.Update(
                mHealthMonitor, HealthMonitorUpdateValidator)
    }
}

/**
 * Tests Neutron Health Monitor Delete translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorDeleteTest
        extends HealthMonitorTranslatorTestBase {

    "Neutron Health Monitor DELETE" should "delete the corresponding Midonet " +
    "Health Monitor." in {
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronHealthMonitor], hmId))

        midoOps should contain only
                midonet.Delete(classOf[HealthMonitor], hmId)
    }
}