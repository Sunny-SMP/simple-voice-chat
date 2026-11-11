package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;

import javax.annotation.Nullable;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.LongAdder;

/**
 * Voice chat server statistics, published over JMX as {@value #OBJECT_NAME}.
 * <p>
 * This fork exposes them for the SunnySMP commons plugin, which reads the MBean and writes it to InfluxDB.
 * JMX keeps this fork free of any dependency on commons, and commons free of a compile-time dependency on this fork.
 * <p>
 * Counters are static so they keep counting when the voice chat server restarts, for example after a port change.
 * Gauges are read from the running {@link Server} when queried, and are 0 while no server is running.
 */
public final class VoicechatStats implements VoicechatStatsMBean {

    public static final String OBJECT_NAME = "de.maxhenkel.voicechat:type=Stats";

    public static final LongAdder PACKETS_RECEIVED = new LongAdder();
    public static final LongAdder PACKETS_DROPPED_QUEUE_FULL = new LongAdder();
    public static final LongAdder PACKETS_DROPPED_EXPIRED = new LongAdder();
    public static final LongAdder PACKETS_INVALID = new LongAdder();
    public static final LongAdder MIC_PACKETS = new LongAdder();
    public static final LongAdder SOUND_PACKETS_SENT = new LongAdder();
    public static final LongAdder SEND_FAILURES = new LongAdder();
    public static final LongAdder CONNECTIONS_ESTABLISHED = new LongAdder();
    public static final LongAdder CONNECTION_TIMEOUTS = new LongAdder();
    public static final LongAdder RECONNECTS = new LongAdder();
    public static final LongAdder VOICE_RATE_LIMIT_KICKS = new LongAdder();
    public static final LongAdder PLUGIN_MESSAGE_RATE_LIMIT_KICKS = new LongAdder();
    public static final LongAdder PLUGIN_MESSAGES_INVALID = new LongAdder();

    private VoicechatStats() {
    }

    /**
     * Publishes the MBean, replacing any earlier registration.
     * An earlier one is left over when the plugin is reloaded, and would still point at the old class loader.
     */
    static void register() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (mBeanServer.isRegistered(name)) {
                mBeanServer.unregisterMBean(name);
            }
            mBeanServer.registerMBean(new VoicechatStats(), name);
        } catch (Exception e) {
            Voicechat.LOGGER.warn("Failed to register the voice chat stats MBean", e);
        }
    }

    static void unregister() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (mBeanServer.isRegistered(name)) {
                mBeanServer.unregisterMBean(name);
            }
        } catch (Exception e) {
            Voicechat.LOGGER.warn("Failed to unregister the voice chat stats MBean", e);
        }
    }

    @Nullable
    private static Server server() {
        ServerVoiceEvents events = Voicechat.SERVER;
        return events == null ? null : events.getServer();
    }

    @Override
    public int getConnectedPlayers() {
        Server server = server();
        return server == null ? 0 : server.getConnections().size();
    }

    @Override
    public int getPendingConnections() {
        Server server = server();
        // Every connected player also has a secret, so the difference is the players still connecting
        return server == null ? 0 : Math.max(0, server.getConnectionSecrets().size() - server.getConnections().size());
    }

    @Override
    public int getGroups() {
        Server server = server();
        return server == null ? 0 : server.getGroupManager().getGroupCount();
    }

    @Override
    public int getPacketQueueSize() {
        Server server = server();
        return server == null ? 0 : server.getPacketQueueSize();
    }

    @Override
    public int getPacketQueueCapacity() {
        Server server = server();
        return server == null ? 0 : server.getPacketQueueCapacity();
    }

    @Override
    public long getPacketsReceived() {
        return PACKETS_RECEIVED.sum();
    }

    @Override
    public long getPacketsDroppedQueueFull() {
        return PACKETS_DROPPED_QUEUE_FULL.sum();
    }

    @Override
    public long getPacketsDroppedExpired() {
        return PACKETS_DROPPED_EXPIRED.sum();
    }

    @Override
    public long getPacketsInvalid() {
        return PACKETS_INVALID.sum();
    }

    @Override
    public long getMicPackets() {
        return MIC_PACKETS.sum();
    }

    @Override
    public long getSoundPacketsSent() {
        return SOUND_PACKETS_SENT.sum();
    }

    @Override
    public long getSendFailures() {
        return SEND_FAILURES.sum();
    }

    @Override
    public long getConnectionsEstablished() {
        return CONNECTIONS_ESTABLISHED.sum();
    }

    @Override
    public long getConnectionTimeouts() {
        return CONNECTION_TIMEOUTS.sum();
    }

    @Override
    public long getReconnects() {
        return RECONNECTS.sum();
    }

    @Override
    public long getVoiceRateLimitKicks() {
        return VOICE_RATE_LIMIT_KICKS.sum();
    }

    @Override
    public long getPluginMessageRateLimitKicks() {
        return PLUGIN_MESSAGE_RATE_LIMIT_KICKS.sum();
    }

    @Override
    public long getPluginMessagesInvalid() {
        return PLUGIN_MESSAGES_INVALID.sum();
    }

}
