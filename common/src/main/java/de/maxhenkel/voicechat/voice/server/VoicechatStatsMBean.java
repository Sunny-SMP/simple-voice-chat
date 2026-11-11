package de.maxhenkel.voicechat.voice.server;

/**
 * The JMX view of {@link VoicechatStats}. Each getter is one MBean attribute, named without the <code>get</code> prefix.
 * <p>
 * Gauges describe the server right now. Counters are totals since the plugin was loaded, so graph their rate of change.
 */
public interface VoicechatStatsMBean {

    // Gauges

    /**
     * @return players whose voice chat connection is validated
     */
    int getConnectedPlayers();

    /**
     * @return players that were sent a secret but haven't validated their connection yet
     */
    int getPendingConnections();

    int getGroups();

    /**
     * @return received packets waiting for the processing thread
     */
    int getPacketQueueSize();

    int getPacketQueueCapacity();

    // Counters

    /**
     * @return UDP packets accepted into the packet queue
     */
    long getPacketsReceived();

    /**
     * @return UDP packets dropped because the packet queue was full
     */
    long getPacketsDroppedQueueFull();

    /**
     * @return packets dropped because they waited in the queue for longer than their TTL
     */
    long getPacketsDroppedExpired();

    /**
     * @return UDP packets that couldn't be read
     */
    long getPacketsInvalid();

    long getMicPackets();

    long getSoundPacketsSent();

    /**
     * @return voice packets that failed to send
     */
    long getSendFailures();

    long getConnectionsEstablished();

    /**
     * @return connections dropped because the client stopped answering keep alives
     */
    long getConnectionTimeouts();

    /**
     * @return reconnects started with <code>/voicechat reconnect</code>
     */
    long getReconnects();

    /**
     * @return players kicked for sending too many voice packets
     */
    long getVoiceRateLimitKicks();

    /**
     * @return players kicked for sending too many plugin messages
     */
    long getPluginMessageRateLimitKicks();

    /**
     * @return plugin messages that couldn't be parsed
     */
    long getPluginMessagesInvalid();

}
