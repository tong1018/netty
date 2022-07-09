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
package io.netty5.channel.socket.nio;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelException;
import io.netty5.channel.ChannelMetadata;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.nio.AbstractNioMessageChannel;
import io.netty5.channel.socket.DefaultServerSocketChannelConfig;
import io.netty5.channel.socket.ServerSocketChannelConfig;
import io.netty5.util.internal.SocketUtils;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.List;
import java.util.Map;

/**
 * A {@link io.netty5.channel.socket.ServerSocketChannel} implementation which uses
 * NIO selector based implementation to accept new connections.
 */
public class NioServerSocketChannel extends AbstractNioMessageChannel<Channel, InetSocketAddress, InetSocketAddress>
                             implements io.netty5.channel.socket.ServerSocketChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioServerSocketChannel.class);

    private static final Method OPEN_SERVER_SOCKET_CHANNEL_WITH_FAMILY =
            SelectorProviderUtil.findOpenMethod("openServerSocketChannel");

    private static ServerSocketChannel newChannel(SelectorProvider provider, ProtocolFamily family) {
        try {
            ServerSocketChannel channel =
                    SelectorProviderUtil.newChannel(OPEN_SERVER_SOCKET_CHANNEL_WITH_FAMILY, provider, family);
            return channel == null ? provider.openServerSocketChannel() : channel;
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final ServerSocketChannelConfig config;
    private final EventLoopGroup childEventLoopGroup;

    /**
     * Create a new instance
     */
    public NioServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup) {
        this(eventLoop, childEventLoopGroup, DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup, SelectorProvider provider) {
        this(eventLoop, childEventLoopGroup, provider, null);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider} and protocol family (supported only since JDK 15).
     */
    public NioServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup,
                                  SelectorProvider provider, ProtocolFamily protocolFamily) {
        this(eventLoop, childEventLoopGroup, newChannel(provider, protocolFamily));
    }

    /**
     * Create a new instance using the given {@link ServerSocketChannel}.
     */
    public NioServerSocketChannel(
            EventLoop eventLoop, EventLoopGroup childEventLoopGroup, ServerSocketChannel channel) {
        super(null, eventLoop, channel, SelectionKey.OP_ACCEPT);
        this.childEventLoopGroup = validateEventLoopGroup(
                childEventLoopGroup, "childEventLoopGroup", NioSocketChannel.class);
        config = new NioServerSocketChannelConfig(this, javaChannel().socket());
    }

    @Override
    public EventLoopGroup childEventLoopGroup() {
        return childEventLoopGroup;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ServerSocketChannelConfig config() {
        return config;
    }

    @Override
    public boolean isActive() {
        // As java.nio.ServerSocketChannel.isBound() will continue to return true even after the channel was closed
        // we will also need to check if it is open.
        return isOpen() && javaChannel().socket().isBound();
    }

    @Override
    protected ServerSocketChannel javaChannel() {
        return (ServerSocketChannel) super.javaChannel();
    }

    @Override
    protected InetSocketAddress localAddress0() {
        return (InetSocketAddress) SocketUtils.localSocketAddress(javaChannel().socket());
    }

    @Override
    protected void doShutdown(ChannelShutdownDirection direction) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShutdown(ChannelShutdownDirection direction) {
        return !isActive();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        javaChannel().bind(localAddress, config.getBacklog());
    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        SocketChannel ch = SocketUtils.accept(javaChannel());

        try {
            if (ch != null) {
                buf.add(new NioSocketChannel(this, childEventLoopGroup().next(), ch));
                return 1;
            }
        } catch (Throwable t) {
            logger.warn("Failed to create a new channel from an accepted socket.", t);

            try {
                ch.close();
            } catch (Throwable t2) {
                logger.warn("Failed to close a socket.", t2);
            }
        }

        return 0;
    }

    // Unnecessary stuff
    @Override
    protected boolean doConnect(
            SocketAddress remoteAddress, SocketAddress localAddress) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean doFinishConnect(InetSocketAddress requestedRemoteAddress) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected InetSocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doDisconnect() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        throw new UnsupportedOperationException();
    }

    private final class NioServerSocketChannelConfig extends DefaultServerSocketChannelConfig {
        private NioServerSocketChannelConfig(NioServerSocketChannel channel, ServerSocket javaSocket) {
            super(channel, javaSocket);
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
        }

        private ServerSocketChannel jdkChannel() {
            return ((NioServerSocketChannel) channel).javaChannel();
        }
    }

    // Override just to to be able to call directly via unit tests.
    @Override
    protected boolean closeOnReadError(Throwable cause) {
        return super.closeOnReadError(cause);
    }
}
