/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.util

import scala.collection.JavaConversions._

import java.util.UUID

import com.google.inject._
import org.apache.commons.configuration.HierarchicalConfiguration

import org.scalatest.BeforeAndAfter
import org.scalatest.FeatureSpecLike
import org.scalatest.GivenWhenThen
import org.scalatest.Matchers
import org.scalatest.OneInstancePerTest

import com.yammer.metrics.core.Clock

import org.midonet.cluster.services.MidostoreSetupService
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.ConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.guice.state.MockFlowStateStorageModule
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.guice._
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.services.{MidolmanActorsService, HostIdProviderService, MidolmanService}
import org.midonet.midolman.simulation.CustomMatchers
import org.midonet.midolman.util.guice.MockMidolmanModule
import org.midonet.midolman.util.mock.{MockInterfaceScanner, MockMidolmanActors}
import org.midonet.midolman.version.guice.VersionModule
import org.midonet.util.MockClock


object MidolmanSpec {
    val TestDhcpMtu: Short = 4200
}
/**
 * A base trait to be used for new style Midolman simulation tests with Midolman
 * Actors.
 */
trait MidolmanSpec extends FeatureSpecLike
        with VirtualConfigurationBuilders
        with Matchers
        with BeforeAndAfter
        with GivenWhenThen
        with CustomMatchers
        with MockMidolmanActors
        with MidolmanServices
        with VirtualTopologyHelper
        with OneInstancePerTest {
    import MidolmanSpec._

    var injector: Injector = null
    var clock = new MockClock

    /**
     * Override this function to perform a custom set-up needed for the test.
     */
    protected def beforeTest() { }

    /**
     * Override this function to perform a custom shut-down operations needed
     * for the test.
     */
    protected def afterTest() { }

    before {
        try {
            val config = fillConfig(new HierarchicalConfiguration)
            injector = Guice.createInjector(getModules(config))

            injector.getInstance(classOf[MidostoreSetupService])
                .startAsync()
                .awaitRunning()
            injector.getInstance(classOf[MidolmanService])
                .startAsync()
                .awaitRunning()

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {
        afterTest()
        injector.getInstance(classOf[MidolmanService])
            .stopAsync()
            .awaitTerminated()
        injector.getInstance(classOf[MidostoreSetupService])
            .stopAsync()
            .awaitTerminated()
    }

    protected def fillConfig(config: HierarchicalConfiguration)
            : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("cassandra.servers", "localhost:9171")
        config.setProperty("midolman.dhcp_mtu", TestDhcpMtu)
        config
    }

    protected def getModules(config: HierarchicalConfiguration) = {
        List(
            new VersionModule(),
            new SerializationModule(),
            new ConfigProviderModule(config),
            new MockDatapathModule(),
            new MockFlowStateStorageModule(),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[HostIdProviderService])
                            .toInstance(new HostIdProviderService() {
                        val hostId = UUID.randomUUID()
                        def getHostId: UUID = hostId
                    })
                }
            },
            new ClusterClientModule(),
            new MockMidolmanModule(),
            new MidolmanActorsModule {
                override def configure() {
                    bind(classOf[MidolmanActorsService])
                            .toInstance(actorsService)
                    expose(classOf[MidolmanActorsService])
                    bind(classOf[Clock]).toInstance(clock)
                }
            },
            new ResourceProtectionModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                            .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            }
        )
    }
}
