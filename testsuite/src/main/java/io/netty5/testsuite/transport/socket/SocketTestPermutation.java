/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.testsuite.transport.socket;

import io.netty5.bootstrap.AbstractBootstrap;
import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFactory;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.nio.NioHandler;
import io.netty5.channel.socket.DomainSocketAddress;
import io.netty5.channel.socket.nio.NioDatagramChannel;
import io.netty5.channel.socket.nio.NioServerSocketChannel;
import io.netty5.channel.socket.nio.NioSocketChannel;
import io.netty5.testsuite.transport.TestsuitePermutation.BootstrapComboFactory;
import io.netty5.testsuite.transport.TestsuitePermutation.BootstrapFactory;
import io.netty5.util.concurrent.DefaultThreadFactory;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.SystemPropertyUtil;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.ProtocolFamily;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SocketTestPermutation {

    static final String BAD_HOST = SystemPropertyUtil.get("io.netty5.testsuite.badHost", "198.51.100.254");
    static final int BAD_PORT = SystemPropertyUtil.getInt("io.netty5.testsuite.badPort", 65535);

    static {
        InternalLogger logger = InternalLoggerFactory.getInstance(SocketConnectionAttemptTest.class);
        logger.debug("-Dio.netty5.testsuite.badHost: {}", BAD_HOST);
        logger.debug("-Dio.netty5.testsuite.badPort: {}", BAD_PORT);
    }

    static final SocketTestPermutation INSTANCE = new SocketTestPermutation();

    protected static final int BOSSES = 2;
    protected static final int WORKERS = 3;

    protected final EventLoopGroup nioBossGroup =
            new MultithreadEventLoopGroup(BOSSES, new DefaultThreadFactory("testsuite-nio-boss", true),
                    NioHandler.newFactory());
    protected final EventLoopGroup nioWorkerGroup =
            new MultithreadEventLoopGroup(WORKERS, new DefaultThreadFactory("testsuite-nio-worker", true),
                    NioHandler.newFactory());

    protected <A extends AbstractBootstrap<?, ?, ?>, B extends AbstractBootstrap<?, ?, ?>>
    List<BootstrapComboFactory<A, B>> combo(List<BootstrapFactory<A>> sbfs, List<BootstrapFactory<B>> cbfs) {

        List<BootstrapComboFactory<A, B>> list = new ArrayList<>();

        // Populate the combinations
        for (BootstrapFactory<A> sbf: sbfs) {
            for (BootstrapFactory<B> cbf: cbfs) {
                final BootstrapFactory<A> sbf0 = sbf;
                final BootstrapFactory<B> cbf0 = cbf;
                list.add(new BootstrapComboFactory<>() {
                    @Override
                    public A newServerInstance() {
                        return sbf0.newInstance();
                    }

                    @Override
                    public B newClientInstance() {
                        return cbf0.newInstance();
                    }
                });
            }
        }

        return list;
    }

    public List<BootstrapComboFactory<ServerBootstrap, Bootstrap>> socket() {
        // Make the list of ServerBootstrap factories.
        List<BootstrapFactory<ServerBootstrap>> sbfs = serverSocket();

        // Make the list of Bootstrap factories.
        List<BootstrapFactory<Bootstrap>> cbfs = clientSocket();

        // Populate the combinations
        return combo(sbfs, cbfs);
    }

    public List<BootstrapComboFactory<ServerBootstrap, Bootstrap>> domainSocket() {
        // Make the list of ServerBootstrap factories.
        List<BootstrapFactory<ServerBootstrap>> sbfs = serverDomainSocket();

        // Make the list of Bootstrap factories.
        List<BootstrapFactory<Bootstrap>> cbfs = clientDomainSocket();

        // Populate the combinations
        return combo(sbfs, cbfs);
    }

    public List<BootstrapComboFactory<ServerBootstrap, Bootstrap>> socketWithFastOpen() {
        // Make the list of ServerBootstrap factories.
        List<BootstrapFactory<ServerBootstrap>> sbfs = serverSocket();

        // Make the list of Bootstrap factories.
        List<BootstrapFactory<Bootstrap>> cbfs = clientSocketWithFastOpen();

        // Populate the combinations
        List<BootstrapComboFactory<ServerBootstrap, Bootstrap>> list = combo(sbfs, cbfs);

        // Remove the OIO-OIO case which often leads to a dead lock by its nature.
        list.remove(list.size() - 1);

        return list;
    }

    public List<BootstrapComboFactory<Bootstrap, Bootstrap>> datagram(final ProtocolFamily family) {
        final ChannelFactory<?> channelFactory;
        if (NioDomainSocketTestUtil.isDomainSocketFamily(family)) {
            channelFactory = NioDomainSocketTestUtil.newDomainSocketDatagramChannelFactory();
        } else {
            channelFactory = new ChannelFactory<>() {
                @Override
                public Channel newChannel(EventLoop eventLoop) {
                    return new NioDatagramChannel(eventLoop, family);
                }

                @Override
                public String toString() {
                    return NioDatagramChannel.class.getSimpleName() + ".class";
                }
            };
        }
        // Make the list of Bootstrap factories.
        List<BootstrapFactory<Bootstrap>> bfs = Collections.singletonList(
                () -> new Bootstrap().group(nioWorkerGroup).channelFactory(channelFactory)
        );

        // Populare the combinations.
        return combo(bfs, bfs);
    }

    public List<BootstrapFactory<Bootstrap>> domainDatagramSocket() {
        if (!NioDomainSocketTestUtil.isDatagramSupported()) {
            throw new UnsupportedOperationException();
        }
        return Collections.singletonList(
                () -> new Bootstrap().group(nioWorkerGroup)
                        .channelFactory(NioDomainSocketTestUtil.newDomainSocketDatagramChannelFactory())
        );
    }

    public List<BootstrapFactory<ServerBootstrap>> serverSocket() {
        return Collections.singletonList(
                () -> new ServerBootstrap().group(nioBossGroup, nioWorkerGroup)
                        .channel(NioServerSocketChannel.class)
        );
    }

    public List<BootstrapFactory<Bootstrap>> clientSocket() {
        return Collections.singletonList(
                () -> new Bootstrap().group(nioWorkerGroup).channel(NioSocketChannel.class)
        );
    }

    public List<BootstrapFactory<ServerBootstrap>> serverDomainSocket() {
        return Collections.singletonList(
                () -> new ServerBootstrap().group(nioBossGroup, nioWorkerGroup)
                        .channelFactory(NioDomainSocketTestUtil.newDomainSocketServerChannelFactory())
        );
    }

    public List<BootstrapFactory<Bootstrap>> clientDomainSocket() {
        return Collections.singletonList(
                () -> new Bootstrap().group(nioWorkerGroup)
                        .channelFactory(NioDomainSocketTestUtil.newDomainSocketChannelFactory())
        );
    }

    public List<BootstrapFactory<Bootstrap>> clientSocketWithFastOpen() {
        return clientSocket();
    }

    public List<BootstrapFactory<Bootstrap>> datagramSocket() {
        return Collections.singletonList(
                () -> new Bootstrap().group(nioWorkerGroup).channel(NioDatagramChannel.class)
        );
    }
    public static DomainSocketAddress newDomainSocketAddress() {
        try {
            for (int i = 0; i < 10; i++) {
                File file = PlatformDependent.createTempFile("NETTY", "UDS", null);
                if (!file.delete()) {
                    throw new IOException("failed to delete: " + file);
                }
                if (file.getAbsolutePath().length() <= 128) {
                    return new DomainSocketAddress(file);
                }
            }
            throw new IllegalStateException("Could not create temporary file with an absolute path length <= 128");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isJdkDomainSocketSupported() {
        return NioDomainSocketTestUtil.isSocketSupported();
    }

    public static boolean isJdkDomainDatagramSupported() {
        return NioDomainSocketTestUtil.isSocketSupported();
    }
}
