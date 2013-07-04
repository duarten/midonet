/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.reactor;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import com.google.inject.*;

import org.midonet.midolman.services.SelectLoopService;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.TryCatchReactor;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * This is an Guice module that will expose a {@link SelectLoop} and a {@link Reactor}
 * binding to the enclosing injector.
 */
public class ReactorModule extends PrivateModule {
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface WRITE_LOOP {}
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface READ_LOOP {}

    @Override
    protected void configure() {

        bind(Reactor.class)
            .toProvider(NetlinkReactorProvider.class)
            .asEagerSingleton();

        bind(SelectLoop.class)
                .annotatedWith(WRITE_LOOP.class)
                .toProvider(SelectLoopProvider.class)
                .in(Singleton.class);
        bind(SelectLoop.class)
                .annotatedWith(READ_LOOP.class)
                .toProvider(SelectLoopProvider.class)
                .in(Singleton.class);

        expose(Key.get(SelectLoop.class, WRITE_LOOP.class));
        expose(Key.get(SelectLoop.class, READ_LOOP.class));

        bind(SelectLoopService.class)
            .in(Singleton.class);

        expose(Reactor.class);
        expose(SelectLoopService.class);
    }

    public static class SelectLoopProvider implements Provider<SelectLoop> {
        @Override
        public SelectLoop get() {
            try {
                return new SelectLoop();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class NetlinkReactorProvider implements Provider<Reactor> {

        @Override
        public Reactor get() {
            return new TryCatchReactor("netlink", 1);
        }
    }
}