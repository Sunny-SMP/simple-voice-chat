package de.maxhenkel.voicechat;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.command.VoiceProxyCommand;
import de.maxhenkel.voicechat.config.ProxyConfig;
import de.maxhenkel.voicechat.logging.VoiceChatLogger;
import de.maxhenkel.voicechat.network.VoiceProxyServer;
import de.maxhenkel.voicechat.sniffer.VoiceProxySniffer;
import de.maxhenkel.voicechat.util.BackendServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public abstract class VoiceProxy {

    public static final String MOD_ID = "voicechat";
    public static final String VOICECHAT_COMMAND = "voicechatproxy";
    public static final String PING_PERMISSION = "voicechat.ping";
    public static final String RECONNECT_PERMISSION = "voicechat.reconnect";
    public static final String RECONNECT_OTHERS_PERMISSION = "voicechat.reconnect.others";
    public static final String MOD_VERSION = BuildConstants.MOD_VERSION;
    public static final int COMPATIBILITY_VERSION = BuildConstants.COMPATIBILITY_VERSION;

    public static final String SECRET_CHANNEL = "voicechat:secret";
    public static final String SECRET_CHANNEL_1_12 = "vc:secret";
    public static final String REQUEST_SECRET_CHANNEL = "voicechat:request_secret";
    public static final String REQUEST_SECRET_CHANNEL_1_12 = "vc:request_secret";

    protected final VoiceProxySniffer voiceProxySniffer = new VoiceProxySniffer(this);
    protected final VoiceProxyCommand voiceProxyCommand = new VoiceProxyCommand(this);
    private final VoiceChatLogger voiceChatLogger;
    protected VoiceProxyServer voiceProxyServer;
    private ProxyConfig voiceProxyConfig;

    public VoiceProxy(VoiceChatLogger logger) {
        voiceChatLogger = logger;
    }

    /**
     * Determine which SocketAddress is used by the player to communicate with the game server
     *
     * @param playerUUID Which player to find the socket for
     * @return The SocketAddress used for game traffic between the game server and the proxy, which may be unresolved, <code>null</code> if the player is not connected to a backend server
     */
    public abstract InetSocketAddress getDefaultBackendSocket(UUID playerUUID);

    /**
     * Determine which SocketAddress is used by the proxy to bind its game port on.
     */
    public abstract InetSocketAddress getDefaultBindSocket();

    /**
     * Returns the Path to the data / config directory for the proxy server plugin
     */
    public abstract Path getDataDirectory();

    public abstract List<BackendServer> getBackendServers();

    /**
     * Finds an online player on the proxy by name
     *
     * @param playerName the name of the player
     * @return the UUID of the player, <code>null</code> if the player is not online
     */
    public abstract UUID getPlayerUUID(String playerName);

    /**
     * Returns the names of all players that are online on the proxy
     */
    public abstract List<String> getPlayerNames();

    /**
     * Returns which port to use for the VoiceProxyServer
     */
    public int getPort() {
        int port = getConfig().port.get();
        if (port == -1) {
            port = getDefaultBindSocket().getPort();
        }
        return port;
    }

    /**
     * Closes any existing VoiceProxyServer instance and starts a fresh VoiceProxyServer
     */
    protected void reloadVoiceProxyServer() {
        try {
            Files.createDirectories(getDataDirectory());
            Path configPath = getDataDirectory().resolve("voicechat-proxy.properties");
            voiceProxyConfig = ConfigBuilder.builder(ProxyConfig::new).path(configPath).build();
        } catch (Exception e) {
            voiceChatLogger.error("Error loading config", e);
        }
        checkCorrectHost();

        if (voiceProxyServer != null) {
            voiceProxyServer.interrupt();
            try {
                // The old server has to release the port before the new one can bind to it
                voiceProxyServer.join();
            } catch (InterruptedException e) {
                voiceChatLogger.error("Interrupted while waiting for the voice chat proxy server to shut down", e);
            }
        }
        voiceProxyServer = new VoiceProxyServer(this);
        voiceProxyServer.start();
    }

    private void checkCorrectHost() {
        String host = voiceProxyConfig.voiceHost.get();
        if (!host.isEmpty()) {
            try {
                new URI("voicechat://" + host);
                voiceChatLogger.info("Voice host is '{}'", host);
            } catch (URISyntaxException e) {
                voiceChatLogger.warn("Failed to parse voice host", e);
            }
        }
    }

    /**
     * Interrupts the bridge of a given player if one exists
     *
     * @param playerUUID The UUID of the player on the proxy
     */
    public void disconnectBridge(UUID playerUUID) {
        if (voiceProxyServer != null) {
            voiceProxyServer.getVoiceProxyBridgeManager().disconnect(playerUUID);
        }
    }

    /**
     * Called whenever a player disconnects from a backend server
     *
     * @param playerUUID The UUID of the player that disconnected from a backend server
     */
    protected void onPlayerServerDisconnected(UUID playerUUID) {
        // The sniffed state has to be removed first, so no new bridge can be created after the old one was interrupted
        voiceProxySniffer.onPlayerServerDisconnect(playerUUID);
        disconnectBridge(playerUUID);
        getLogger().debug("Player {} has disconnected from backend server, interrupting bridge if it exists", playerUUID);
    }

    /**
     * Drops the bridge and the sniffed backend socket of a player.
     * The backend server times out the stale session and sends a new secret, which connects the player through a fresh bridge.
     *
     * @param playerUUID the UUID of the player on the proxy
     * @return <code>false</code> if the player has no voice chat session on this proxy
     */
    public boolean reconnectPlayer(UUID playerUUID) {
        if (voiceProxySniffer.getBackendSocket(playerUUID) == null) {
            return false;
        }
        voiceProxySniffer.resetBackendSocket(playerUUID);
        disconnectBridge(playerUUID);
        voiceChatLogger.info("Reconnecting player {}", playerUUID);
        return true;
    }

    public ProxyConfig getConfig() {
        return voiceProxyConfig;
    }

    public VoiceChatLogger getLogger() {
        return voiceChatLogger;
    }

    public VoiceProxySniffer getSniffer() {
        return voiceProxySniffer;
    }

}
