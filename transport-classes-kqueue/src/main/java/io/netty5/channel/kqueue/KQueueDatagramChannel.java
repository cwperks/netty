/*
 * Copyright 2016 The Netty Project
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
package io.netty5.channel.kqueue;

import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.channel.AddressedEnvelope;
import io.netty5.channel.ChannelException;
import io.netty5.channel.ChannelMetadata;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.DefaultBufferAddressedEnvelope;
import io.netty5.channel.EventLoop;
import io.netty5.channel.FixedRecvBufferAllocator;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.DomainSocketAddress;
import io.netty5.channel.socket.SocketProtocolFamily;
import io.netty5.channel.unix.DatagramSocketAddress;
import io.netty5.channel.unix.DomainDatagramSocketAddress;
import io.netty5.channel.unix.Errors;
import io.netty5.channel.unix.IovArray;
import io.netty5.channel.unix.RecvFromAddressDomainSocket;
import io.netty5.channel.unix.UnixChannel;
import io.netty5.channel.unix.UnixChannelOption;
import io.netty5.channel.unix.UnixChannelUtil;
import io.netty5.util.UncheckedBooleanSupplier;
import io.netty5.util.concurrent.Future;
import io.netty5.util.internal.SilentDispose;
import io.netty5.util.internal.StringUtil;
import io.netty5.util.internal.UnstableApi;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.PortUnreachableException;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Set;

import static io.netty5.channel.ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION;
import static io.netty5.channel.ChannelOption.IP_TOS;
import static io.netty5.channel.ChannelOption.SO_BROADCAST;
import static io.netty5.channel.ChannelOption.SO_RCVBUF;
import static io.netty5.channel.ChannelOption.SO_REUSEADDR;
import static io.netty5.channel.ChannelOption.SO_SNDBUF;
import static io.netty5.channel.unix.UnixChannelOption.SO_REUSEPORT;
import static io.netty5.util.CharsetUtil.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * {@link DatagramChannel} implementation that uses KQueue.
 *
 * <h3>Available options</h3>
 *
 * In addition to the options provided by {@link DatagramChannel} and {@link UnixChannel},
 * {@link KQueueDatagramChannel} allows the following options in the option map:
 *
 * <table border="1" cellspacing="0" cellpadding="6">
 * <tr>
 * <th>Name</th>
 * </tr><tr>
 * <td>{@link UnixChannelOption#SO_REUSEPORT}</td>
 * </tr><tr>
 * <td>{@link KQueueChannelOption#RCV_ALLOC_TRANSPORT_PROVIDES_GUESS}</td>
 * </tr>
 * </table>
 */
@UnstableApi
public final class KQueueDatagramChannel
        extends AbstractKQueueChannel<UnixChannel> implements DatagramChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(KQueueDatagramChannel.class);
    private static final Set<ChannelOption<?>> SUPPORTED_OPTIONS = supportedOptions();

    private static final Set<ChannelOption<?>> SUPPORTED_OPTIONS_DOMAIN_SOCKET = supportedOptionsDomainSocket();

    private static final ChannelMetadata METADATA = new ChannelMetadata(true);

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(DatagramPacket.class) + ", " +
                    StringUtil.simpleClassName(AddressedEnvelope.class) + '<' +
                    StringUtil.simpleClassName(Buffer.class) + ", " +
                    StringUtil.simpleClassName(InetSocketAddress.class) + ">, " +
                    StringUtil.simpleClassName(Buffer.class) + ')';

    private static final String EXPECTED_TYPES_DOMAIN =
            " (expected: " +
                    StringUtil.simpleClassName(DatagramPacket.class) + ", " +
                    StringUtil.simpleClassName(AddressedEnvelope.class) + '<' +
                    StringUtil.simpleClassName(Buffer.class) + ", " +
                    StringUtil.simpleClassName(DomainSocketAddress.class) + ">, " +
                    StringUtil.simpleClassName(Buffer.class) + ')';
    private volatile boolean connected;
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    private boolean activeOnOpen;

    public KQueueDatagramChannel(EventLoop eventLoop) {
        this(eventLoop, null);
    }

    public KQueueDatagramChannel(EventLoop eventLoop, ProtocolFamily protocolFamily) {
        super(null, eventLoop, METADATA, new FixedRecvBufferAllocator(2048),
                BsdSocket.newDatagramSocket(protocolFamily), false);
    }

    public KQueueDatagramChannel(EventLoop eventLoop, int fd, ProtocolFamily protocolFamily) {
        this(eventLoop, new BsdSocket(fd, SocketProtocolFamily.of(protocolFamily)), true);
    }

    KQueueDatagramChannel(EventLoop eventLoop, BsdSocket socket, boolean active) {
        super(null, eventLoop, METADATA, new FixedRecvBufferAllocator(2048), socket, active);
    }

    @Override
    protected boolean fetchLocalAddress() {
        return socket.protocolFamily() != SocketProtocolFamily.UNIX;
    }

    @SuppressWarnings( "unchecked")
    @Override
    protected  <T> T getExtendedOption(ChannelOption<T> option) {
        if (isExtendedOptionSupported(option)) {
            if (option == SO_BROADCAST) {
                return (T) Boolean.valueOf(isBroadcast());
            }
            if (option == SO_RCVBUF) {
                return (T) Integer.valueOf(getReceiveBufferSize());
            }
            if (option == SO_SNDBUF) {
                return (T) Integer.valueOf(getSendBufferSize());
            }
            if (option == SO_REUSEADDR) {
                return (T) Boolean.valueOf(isReuseAddress());
            }
            if (option == IP_TOS) {
                return (T) Integer.valueOf(getTrafficClass());
            }
            if (option == DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION) {
                return (T) Boolean.valueOf(activeOnOpen);
            }
            if (option == SO_REUSEPORT) {
                return (T) Boolean.valueOf(isReusePort());
            }
        }
        return super.getExtendedOption(option);
    }

    @Override
    protected  <T> void setExtendedOption(ChannelOption<T> option, T value) {
        if (isExtendedOptionSupported(option)) {
            if (option == SO_BROADCAST) {
                setBroadcast((Boolean) value);
            } else if (option == SO_RCVBUF) {
                setReceiveBufferSize((Integer) value);
            } else if (option == SO_SNDBUF) {
                setSendBufferSize((Integer) value);
            } else if (option == SO_REUSEADDR) {
                setReuseAddress((Boolean) value);
            } else if (option == IP_TOS) {
                setTrafficClass((Integer) value);
            } else if (option == DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION) {
                setActiveOnOpen((Boolean) value);
            } else if (option == SO_REUSEPORT) {
                setReusePort((Boolean) value);
            }
            return;
        }
        super.setExtendedOption(option, value);
    }

    @Override
    protected boolean isExtendedOptionSupported(ChannelOption<?> option) {
        if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
            if (SUPPORTED_OPTIONS_DOMAIN_SOCKET.contains(option)) {
                return true;
            }
        } else if (SUPPORTED_OPTIONS.contains(option)) {
            return true;
        }
        return super.isExtendedOptionSupported(option);
    }

    @SuppressWarnings("deprecation")
    private static Set<ChannelOption<?>> supportedOptions() {
        return newSupportedIdentityOptionsSet(SO_BROADCAST, SO_RCVBUF, SO_SNDBUF, SO_REUSEADDR, IP_TOS,
                DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION, SO_REUSEPORT);
    }


    @SuppressWarnings("deprecation")
    private static Set<ChannelOption<?>> supportedOptionsDomainSocket() {
        return newSupportedIdentityOptionsSet(SO_SNDBUF, SO_RCVBUF, DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION);
    }

    private void setActiveOnOpen(boolean activeOnOpen) {
        if (isRegistered()) {
            throw new IllegalStateException("Can only changed before channel was registered");
        }
        this.activeOnOpen = activeOnOpen;
    }

    private boolean getActiveOnOpen() {
        return activeOnOpen;
    }

    /**
     * Returns {@code true} if the SO_REUSEPORT option is set.
     */
    private boolean isReusePort() {
        try {
            return socket.isReusePort();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    /**
     * Set the SO_REUSEPORT option on the underlying Channel. This will allow to bind multiple
     * {@link KQueueSocketChannel}s to the same port and so accept connections with multiple threads.
     *
     * Be aware this method needs be called before {@link KQueueDatagramChannel#bind(java.net.SocketAddress)} to have
     * any affect.
     */
    private void setReusePort(boolean reusePort) {
        try {
            socket.setReusePort(reusePort);
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private int getSendBufferSize() {
        try {
            return socket.getSendBufferSize();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    public void setSendBufferSize(int sendBufferSize) {
        try {
            socket.setSendBufferSize(sendBufferSize);
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private int getReceiveBufferSize() {
        try {
            return socket.getReceiveBufferSize();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private void setReceiveBufferSize(int receiveBufferSize) {
        try {
            socket.setReceiveBufferSize(receiveBufferSize);
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private int getTrafficClass() {
        try {
            return socket.getTrafficClass();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private void setTrafficClass(int trafficClass) {
        try {
            socket.setTrafficClass(trafficClass);
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private boolean isReuseAddress() {
        try {
            return socket.isReuseAddress();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private void setReuseAddress(boolean reuseAddress) {
        try {
            socket.setReuseAddress(reuseAddress);
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private boolean isBroadcast() {
        try {
            return socket.isBroadcast();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    private void setBroadcast(boolean broadcast) {
        try {
            socket.setBroadcast(broadcast);
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public boolean isActive() {
        return socket.isOpen() && (getActiveOnOpen() && isRegistered() || active);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        super.doBind(localAddress);
        active = true;
    }

    private boolean doWriteMessage(Object msg) throws Exception {
        final Object data;
        final SocketAddress remoteAddress;
        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<?, SocketAddress> envelope = (AddressedEnvelope<?, SocketAddress>) msg;
            data = envelope.content();
            remoteAddress = envelope.recipient();
        } else {
            data = msg;
            remoteAddress = null;
        }

        return doWriteBufferMessage((Buffer) data, remoteAddress);
    }

    private boolean doWriteBufferMessage(Buffer data, SocketAddress remoteAddress) throws IOException {
        final int initialReadableBytes = data.readableBytes();
        if (initialReadableBytes == 0) {
            return true;
        }

        if (data.countReadableComponents() > 1) {
            IovArray array = registration().cleanArray();
            data.forEachReadable(0, array);
            int count = array.count();
            assert count != 0;

            final long writtenBytes;
            if (remoteAddress == null) {
                writtenBytes = socket.writevAddresses(array.memoryAddress(0), count);
            } else {
                if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
                    writtenBytes = socket.sendToAddressesDomainSocket(
                            array.memoryAddress(0), count, ((DomainSocketAddress) remoteAddress).path().getBytes(UTF_8));
                } else {
                    InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteAddress;
                    writtenBytes = socket.sendToAddresses(array.memoryAddress(0), count,
                            inetSocketAddress.getAddress(), inetSocketAddress.getPort());
                }

            }
            return writtenBytes > 0;
        } else {
            if (remoteAddress == null) {
                data.forEachReadable(0, (index, component) -> {
                    int written = socket.writeAddress(component.readableNativeAddress(), 0, component.readableBytes());
                    component.skipReadableBytes(written);
                    return false;
                });
            } else {
                if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
                    byte[] path = ((DomainSocketAddress) remoteAddress).path().getBytes(UTF_8);
                    data.forEachReadable(0, (index, component) -> {
                        int written = socket.sendToAddressDomainSocket(
                                component.readableNativeAddress(), 0, component.readableBytes(), path);
                        component.skipReadableBytes(written);
                        return false;
                    });
                } else {
                    InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteAddress;
                    data.forEachReadable(0, (index, component) -> {
                        int written = socket.sendToAddress(component.readableNativeAddress(), 0, component.readableBytes(),
                                inetSocketAddress.getAddress(), inetSocketAddress.getPort());
                        component.skipReadableBytes(written);
                        return false;
                    });
                }
            }
            return data.readableBytes() < initialReadableBytes;
        }
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
            return filterOutboundMessage0(msg, DomainSocketAddress.class, EXPECTED_TYPES_DOMAIN);
        } else {
            return filterOutboundMessage0(msg, InetSocketAddress.class, EXPECTED_TYPES);
        }
    }

    private Object filterOutboundMessage0(Object msg, Class<? extends SocketAddress> recipientClass,
                                            String expectedTypes) {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            if (recipientClass.isInstance(packet.recipient())) {
                Buffer content = packet.content();
                return UnixChannelUtil.isBufferCopyNeededForWrite(content)?
                        new DatagramPacket(newDirectBuffer(packet, content), packet.recipient()) : msg;
            }
        } else if (msg instanceof Buffer) {
            Buffer buf = (Buffer) msg;
            return UnixChannelUtil.isBufferCopyNeededForWrite(buf)? newDirectBuffer(buf) : buf;
        } else if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<Object, SocketAddress> e = (AddressedEnvelope<Object, SocketAddress>) msg;
            SocketAddress recipient = e.recipient();
            if (recipient == null || recipientClass.isInstance(recipient)) {
                if (e.content() instanceof Buffer) {
                    Buffer buf = (Buffer) e.content();
                    if (UnixChannelUtil.isBufferCopyNeededForWrite(buf)) {
                        try {
                            return new DefaultBufferAddressedEnvelope<>(newDirectBuffer(null, buf), recipient);
                        } finally {
                            SilentDispose.dispose(e, logger); // Don't fail here, because we allocated a buffer.
                        }
                    }
                    return e;
                }
            }
        }
        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + expectedTypes);
    }

    @Override
    protected void doDisconnect() throws Exception {
        socket.disconnect();
        connected = active = false;
        resetCachedAddresses();
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (super.doConnect(remoteAddress, localAddress)) {
            connected = true;
            return true;
        }
        return false;
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        connected = false;
    }

    @Override
    void readReady(KQueueRecvBufferAllocatorHandle allocHandle) {
        assert executor().inEventLoop();
        if (shouldBreakReadReady()) {
            clearReadFilter0();
            return;
        }
        final ChannelPipeline pipeline = pipeline();
        final BufferAllocator allocator = bufferAllocator();
        allocHandle.reset();
        readReadyBefore();

        Throwable exception = null;
        try {
            Buffer buffer = null;
            try {
                boolean connected = isConnected();
                do {
                    buffer = allocHandle.allocate(allocator);
                    allocHandle.attemptedBytesRead(buffer.writableBytes());

                    final DatagramPacket packet;
                    if (connected) {
                        try {
                            allocHandle.lastBytesRead(doReadBytes(buffer));
                        } catch (Errors.NativeIoException e) {
                            // We need to correctly translate connect errors to match NIO behaviour.
                            if (e.expectedErr() == Errors.ERROR_ECONNREFUSED_NEGATIVE) {
                                PortUnreachableException error = new PortUnreachableException(e.getMessage());
                                error.initCause(e);
                                throw error;
                            }
                            throw e;
                        }
                        if (allocHandle.lastBytesRead() <= 0) {
                            // nothing was read, release the buffer.
                            buffer.close();
                            buffer = null;
                            break;
                        }
                        packet = new DatagramPacket(buffer, localAddress(), remoteAddress());
                    } else {
                        SocketAddress localAddress = null;
                        SocketAddress remoteAddress = null;
                        int bytesRead = 0;
                        if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
                            final RecvFromAddressDomainSocket recvFrom = new RecvFromAddressDomainSocket(socket);
                            buffer.forEachWritable(0, recvFrom);
                            DomainDatagramSocketAddress recvAddress = recvFrom.remoteAddress();
                            if (recvAddress != null) {
                                remoteAddress = recvAddress;
                                bytesRead = recvAddress.receivedAmount();
                                localAddress = recvAddress.localAddress();
                            }
                        } else {
                            try (var iterator = buffer.forEachWritable()) {
                                var component = iterator.first();
                                long addr = component.writableNativeAddress();
                                DatagramSocketAddress datagramSocketAddress;
                                if (addr != 0) {
                                    // has a memory address so use optimized call
                                    datagramSocketAddress = socket.recvFromAddress(addr, 0, component.writableBytes());
                                } else {
                                    ByteBuffer nioData = component.writableBuffer();
                                    datagramSocketAddress = socket.recvFrom(nioData, nioData.position(), nioData.limit());
                                }
                                if (datagramSocketAddress != null) {
                                    remoteAddress = datagramSocketAddress;
                                    localAddress = datagramSocketAddress.localAddress();
                                    bytesRead = allocHandle.lastBytesRead();
                                }
                            }
                        }

                        if (remoteAddress == null) {
                            allocHandle.lastBytesRead(-1);
                            buffer.close();
                            break;
                        }
                        if (localAddress == null) {
                            localAddress = localAddress();
                        }
                        allocHandle.lastBytesRead(bytesRead);
                        buffer.skipWritableBytes(allocHandle.lastBytesRead());

                        packet = new DatagramPacket(buffer, localAddress, remoteAddress);
                    }

                    allocHandle.incMessagesRead(1);

                    readPending = false;
                    pipeline.fireChannelRead(packet);

                    buffer = null;

                // We use the TRUE_SUPPLIER as it is also ok to read less then what we did try to read (as long
                // as we read anything).
                } while (allocHandle.continueReading(isAutoRead(), UncheckedBooleanSupplier.TRUE_SUPPLIER));
            } catch (Throwable t) {
                if (buffer != null) {
                    buffer.close();
                }
                exception = t;
            }

            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();

            if (exception != null) {
                pipeline.fireChannelExceptionCaught(exception);
            } else {
                readIfIsAutoRead();
            }
        } finally {
            readReadyFinally();
        }
    }

    private <V> Future<V> newMulticastNotSupportedFuture() {
        return newFailedFuture(new UnsupportedOperationException("Multicast not supported"));
    }

    @Override
    public final Future<Void> joinGroup(InetAddress multicastAddress) {
        requireNonNull(multicastAddress, "multicast");
        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> joinGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(networkInterface, "networkInterface");

        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> leaveGroup(InetAddress multicastAddress) {
        requireNonNull(multicastAddress, "multicast");
        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> leaveGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(networkInterface, "networkInterface");

        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> block(
            InetAddress multicastAddress, NetworkInterface networkInterface,
            InetAddress sourceToBlock) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(sourceToBlock, "sourceToBlock");
        requireNonNull(networkInterface, "networkInterface");

        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> block(InetAddress multicastAddress, InetAddress sourceToBlock) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(sourceToBlock, "sourceToBlock");

        return newMulticastNotSupportedFuture();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int maxMessagesPerWrite = getMaxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }

            try {
                boolean done = false;
                for (int i = getWriteSpinCount(); i > 0; --i) {
                    if (doWriteMessage(msg)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                    maxMessagesPerWrite--;
                } else {
                    break;
                }
            } catch (IOException e) {
                maxMessagesPerWrite--;

                // Continue on write error as a DatagramChannel can write to multiple remote peers
                //
                // See https://github.com/netty/netty/issues/2665
                in.remove(e);
            }
        }

        // Whether all messages were written or not.
        writeFilter(!in.isEmpty());
    }

    @Override
    protected void doShutdown(ChannelShutdownDirection direction) {
        switch (direction) {
            case Inbound:
                inputShutdown = true;
                break;
            case Outbound:
                outputShutdown = true;
                break;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public boolean isShutdown(ChannelShutdownDirection direction) {
        if (!isActive()) {
            return true;
        }
        switch (direction) {
            case Inbound:
                return inputShutdown;
            case Outbound:
                return outputShutdown;
            default:
                throw new AssertionError();
        }
    }
}
