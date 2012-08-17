/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice.zookeeper;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.name.Names;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.util.eventloop.Reactor;

/**
 * Modules which creates the proper bindings for building a Directory backed up
 * by zookeeper.
 */
public class ZookeeperConnectionModule extends PrivateModule {

    @Override
    protected void configure() {

        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        bind(ZookeeperConfig.class)
            .toProvider(ZookeeperConfigProvider.class)
            .asEagerSingleton();
        expose(ZookeeperConfig.class);

        bindZookeeperConnection();
        bindDirectory();
        bindReactor();

        expose(Key.get(Reactor.class,
                       Names.named(
                           ZKConnectionProvider.DIRECTORY_REACTOR_TAG)));
        expose(Directory.class);
    }

    protected void bindDirectory() {
        bind(Directory.class)
            .toProvider(DirectoryProvider.class)
            .asEagerSingleton();
    }

    protected void bindZookeeperConnection() {
        bind(ZkConnection.class)
            .toProvider(ZKConnectionProvider.class)
            .asEagerSingleton();
    }

    protected void bindReactor() {
        bind(Reactor.class).annotatedWith(
            Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
            .toProvider(ReactorProvider.class)
            .asEagerSingleton();
    }

    /**
     * A {@link Provider} of {@link ZookeeperConfig} instances which uses an
     * existing {@link ConfigProvider} as the configuration backend.
     */
    public static class ZookeeperConfigProvider implements
                                                Provider<ZookeeperConfig> {

        @Inject
        ConfigProvider configProvider;

        @Override
        public ZookeeperConfig get() {
            return configProvider.getConfig(ZookeeperConfig.class);
        }
    }
}
