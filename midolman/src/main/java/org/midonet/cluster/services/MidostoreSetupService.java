/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2013 Midokura PTE LTD
 */
package org.midonet.cluster.services;

import com.google.common.util.concurrent.AbstractService;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.state.Directory;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.state.ZkDirectory;
import org.midonet.midolman.version.DataWriteVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;


public class MidostoreSetupService extends AbstractService {

    private static final Logger log = LoggerFactory
            .getLogger(MidostoreSetupService.class);
    @Inject
    Directory directory;

    @Inject
    ZookeeperConfig config;

    @Inject
    SystemDataProvider systemDataProvider;

    @Inject
    CuratorFramework curatorFramework;

    @Override
    protected void doStart() {
        try {
            final String rootKey = config.getMidolmanRootKey();

            Setup.ensureZkDirectoryStructureExists(directory, rootKey);

            verifyVersion();

            verifySystemState();

            // A hack to avoid having this client start in unit tests. Only
            // start if the configuration indicates that an actual zookeeper
            // server is running (not MockDirectory, for example)
            if (directory instanceof ZkDirectory) {
                curatorFramework.start();
            }

            notifyStarted();
        } catch (Exception e) {
            this.notifyFailed(e);
        }
    }

    public void verifySystemState() throws StateAccessException {
        if (systemDataProvider.systemUpgradeStateExists()) {
            throw new RuntimeException("Midolman is locked for "
                        + "upgrade. Please restart when upgrade is"
                        + " complete.");
        }
    }

    public void verifyVersion() throws StateAccessException {

        if (!systemDataProvider.writeVersionExists()) {
            systemDataProvider.setWriteVersion(DataWriteVersion.CURRENT);
        }

        if (systemDataProvider.isBeforeWriteVersion(DataWriteVersion.CURRENT)) {
            throw new RuntimeException("Midolmans version ("
                    + DataWriteVersion.CURRENT
                    + ") is lower than the write version ("
                    + systemDataProvider.getWriteVersion() + ").");
        }
    }

    @Override
    protected void doStop() {
        // The following call works even if it has not been started
        curatorFramework.close();
        notifyStopped();
    }
}
