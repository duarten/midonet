/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static java.lang.String.format;

import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.*;
import com.midokura.util.Waiters;
import org.apache.commons.io.FileUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Port;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.tools.timed.Timed;
import com.midokura.util.SystemHelper;
import com.midokura.util.process.ProcessHelper;
import static com.midokura.tools.timed.Timed.newTimedExecution;

/**
 * @author Mihai Claudiu Toader  <mtoader@midokura.com>
 *         Date: 12/7/11
 */
public class FunctionalTestsHelper {

    public static final String LOCK_NAME = "functional-tests";

    private static String zkClient = getZkClient();


    protected final static Logger log = LoggerFactory
            .getLogger(FunctionalTestsHelper.class);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    cleanupZooKeeperData();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    protected static void cleanupZooKeeperData()
        throws IOException, InterruptedException {
        startZookeeperService();
    }

    protected static void stopZookeeperService(ZKLauncher zkLauncher)
            throws IOException, InterruptedException {
        if ( zkLauncher != null ) {
           zkLauncher.stop();
        }
    }

    protected static void removeCassandraFolder() throws IOException, InterruptedException {
        File cassandraFolder = new File("target/cassandra");
        FileUtils.deleteDirectory(cassandraFolder);
    }

    protected static void stopZookeeperService() throws IOException, InterruptedException {
        ProcessHelper.newLocalProcess("service zookeeper stop").withSudo().runAndWait();
    }

    protected static void startZookeeperService() throws IOException, InterruptedException {
        ProcessHelper.newLocalProcess("service zookeeper start").withSudo().runAndWait();
    }

    protected static String getZkClient() {
        // Often zkCli.sh is not in the PATH, use the one from default install
        // otherwise
        String zkClientPath;
        SystemHelper.OsType osType = SystemHelper.getOsType();

        switch (osType) {
            case Mac:
                zkClientPath = "zkCli";
                break;
            case Linux:
            case Unix:
            case Solaris:
                zkClientPath = "zkCli.sh";
                break;
            default:
                zkClientPath = "zkCli.sh";
                break;
        }

        List<String> pathList =
                ProcessHelper.executeLocalCommandLine("which " + zkClientPath);

        if (pathList.isEmpty()) {
            switch (osType) {
                case Mac:
                    zkClientPath = "/usr/local/bin/zkCli";
                    break;
                default:
                    zkClientPath = "/usr/share/zookeeper/bin/zkCli.sh";
            }
        }
        return zkClientPath;
    }

    protected static void cleanupZooKeeperServiceData()
        throws IOException, InterruptedException {
        cleanupZooKeeperServiceData(null);
    }

    protected static void cleanupZooKeeperServiceData(ZKLauncher.ConfigType configType)
            throws IOException, InterruptedException {

        int port = 2181;
        if (configType != null) {
            port = configType.getPort();
        }

        //TODO(pino, mtoader): try removing the ZK directory without restarting
        //TODO:     ZK. If it fails, stop/start/remove, to force the remove,
        //TODO      then throw an error to identify the bad test.

        int exitCode = ProcessHelper
                .newLocalProcess(
                    String.format("%s -server 127.0.0.1:%d rmr /smoketest",
                                  zkClient, port))
                .logOutput(log, "cleaning_zk")
                .runAndWait();

        if (exitCode != 0 && SystemHelper.getOsType() == SystemHelper.OsType.Linux) {
            // Restart ZK to get around the bug where a directory cannot be deleted.
            stopZookeeperService();
            startZookeeperService();

            // Now delete the functional test ZK directory.
            ProcessHelper
                    .newLocalProcess(
                        String.format(
                            "%s -server 127.0.0.1:%d rmr /smoketest",
                            zkClient, port))
                    .logOutput(log, "cleaning_zk")
                    .runAndWait();
        }
    }

    public static void removeRemoteTap(RemoteTap tap) {
        if (tap != null) {
            try {
                tap.remove();
            } catch (Exception e) {
                log.error("While trying to remote a remote tap", e);
            }
        }
    }

    public static void removeTapWrapper(TapWrapper tap) {
        if (tap != null) {
            tap.remove();
        }
    }

    public static void fixQuaggaFolderPermissions()
            throws IOException, InterruptedException {
        // sometimes after a reboot someone will reset the permissions which in
        // turn will make our Zebra implementation unable to bind to the socket
        // so we fix it like a boss.
        ProcessHelper
                .newProcess("chmod 777 /var/run/quagga")
                .withSudo()
                .runAndWait();
    }

    protected void removeMidoPort(Port port) {
        if (port != null) {
            port.delete();
        }
    }

    public static void removeTenant(Tenant tenant) {
        if (null != tenant)
            tenant.delete();
    }

    public static void stopMidolman(MidolmanLauncher mm) {
        if (null != mm)
            mm.stop();
    }

    public static void stopMidolmanMgmt(MockMgmtStarter mgmt) {
        if (null != mgmt)
            mgmt.stop();
    }

    public static void removeBridge(OvsBridge ovsBridge) {
        if (ovsBridge != null) {
            ovsBridge.remove();
        }
    }

    public static void removeVpn(MidolmanMgmt mgmt, MaterializedRouterPort vpn1) {
        if (mgmt != null && vpn1 != null) {
            mgmt.deleteVpn(vpn1.getVpn());
        }
    }

    public static void destroyVM(VMController vm) {
        try {
            if (vm != null) {
                vm.destroy();
            }

            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //
        }
    }

    public static void assertNoMorePacketsOnTap(TapWrapper tapWrapper) {
        assertThat(
                format("Got an unexpected packet from tap %s",
                        tapWrapper.getName()),
                tapWrapper.recv(), nullValue());
    }

    public static void assertNoMorePacketsOnTap(RemoteTap tap) {
        assertThat(
                format("Got an unexpected packet from tap %s", tap.getName()),
                tap.recv(), nullValue());
    }

    public static void assertPacketWasSentOnTap(TapWrapper tap,
                                                byte[] packet) {
        assertThat(
                format("We couldn't send a packet via tap %s", tap.getName()),
                tap.send(packet));
    }

    public static void assertPacketWasSentOnTap(RemoteTap tap,
                                                byte[] packet) {
        assertThat(
                format("We couldn't send a packet via tap %s", tap.getName()),
                tap.send(packet));

    }

    public static int startEmbeddedZookeeper(int port) {
        return EmbeddedZKLauncher.start(port);
    }

    public static void stopEmbeddedZookeeper() {
        EmbeddedZKLauncher.stop();
    }

    public static void startCassandra(String cassandraConfiguration) {
        try {
            EmbeddedCassandraServerHelper.startEmbeddedCassandra(cassandraConfiguration);
        } catch (Exception e) {
            log.error("Failed to start embedded Cassandra.", e);
        }
    }

    public static void startCassandra() {
        try {
            EmbeddedCassandraServerHelper.startEmbeddedCassandra();
        } catch (Exception e) {
            log.error("Failed to start embedded Cassandra.", e);
        }
    }

    public static void stopCassandra() {
        EmbeddedCassandraServerHelper.stopEmbeddedCassandra();
    }
}
