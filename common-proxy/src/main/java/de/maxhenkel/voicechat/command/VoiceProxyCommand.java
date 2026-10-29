package de.maxhenkel.voicechat.command;

import de.maxhenkel.voicechat.VoiceProxy;
import de.maxhenkel.voicechat.util.BackendServer;
import de.maxhenkel.voicechat.util.PingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The platform-independent implementation of the voice chat proxy command.
 */
public class VoiceProxyCommand {

    /**
     * How long players without the permission to reconnect others have to wait between reconnects
     */
    private static final long RECONNECT_COOLDOWN = 10_000L;

    private final VoiceProxy voiceProxy;
    private final Map<UUID, Long> reconnectTimes = new ConcurrentHashMap<>();

    public VoiceProxyCommand(VoiceProxy voiceProxy) {
        this.voiceProxy = voiceProxy;
    }

    public void execute(CommandSender sender, String[] args) {
        String subcommand = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (subcommand) {
            case "ping":
                if (sender.hasPermission(VoiceProxy.PING_PERMISSION)) {
                    ping(sender, args);
                    return;
                }
                break;
            case "reconnect":
                if (canReconnect(sender)) {
                    reconnect(sender, args);
                    return;
                }
                break;
            case "help":
                break;
            default:
                sender.sendMessage(String.format("Unknown command '%s'", args[0]));
                break;
        }
        help(sender);
    }

    public List<String> suggest(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return filter(args.length == 0 ? "" : args[0], getSubcommands(sender));
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (subcommand.equals("ping") && sender.hasPermission(VoiceProxy.PING_PERMISSION)) {
                return filter(args[1], voiceProxy.getBackendServers().stream().map(BackendServer::getName).collect(Collectors.toList()));
            }
            if (subcommand.equals("reconnect") && sender.hasPermission(VoiceProxy.RECONNECT_OTHERS_PERMISSION)) {
                return filter(args[1], voiceProxy.getPlayerNames());
            }
        }
        return Collections.emptyList();
    }

    private static List<String> filter(String arg, List<String> list) {
        String lowerArg = arg.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lowerArg)).collect(Collectors.toList());
    }

    private static List<String> getSubcommands(CommandSender sender) {
        List<String> subcommands = new ArrayList<>();
        subcommands.add("help");
        if (sender.hasPermission(VoiceProxy.PING_PERMISSION)) {
            subcommands.add("ping");
        }
        if (canReconnect(sender)) {
            subcommands.add("reconnect");
        }
        return subcommands;
    }

    private static boolean canReconnect(CommandSender sender) {
        return sender.hasPermission(VoiceProxy.RECONNECT_OTHERS_PERMISSION) || (sender.getUniqueId() != null && sender.hasPermission(VoiceProxy.RECONNECT_PERMISSION));
    }

    private static String getUsage(CommandSender sender, String subcommand) {
        String usage;
        switch (subcommand) {
            case "ping":
                usage = "ping <server name> <voice chat port>";
                break;
            case "reconnect":
                if (!sender.hasPermission(VoiceProxy.RECONNECT_OTHERS_PERMISSION)) {
                    usage = "reconnect";
                } else if (sender.getUniqueId() != null) {
                    usage = "reconnect [<player>]";
                } else {
                    usage = "reconnect <player>";
                }
                break;
            default:
                usage = subcommand;
                break;
        }
        return String.format("/%s %s", VoiceProxy.VOICECHAT_COMMAND, usage);
    }

    private void help(CommandSender sender) {
        sender.sendMessage("Simple Voice Chat proxy commands:");
        for (String subcommand : getSubcommands(sender)) {
            sender.sendMessage("  " + getUsage(sender, subcommand));
        }
    }

    private void reconnect(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage("Too many arguments");
            sender.sendMessage("Usage: " + getUsage(sender, "reconnect"));
            return;
        }

        UUID playerUUID;
        String playerName;
        if (args.length == 2) {
            if (!sender.hasPermission(VoiceProxy.RECONNECT_OTHERS_PERMISSION)) {
                sender.sendMessage("You do not have permission to reconnect other players");
                return;
            }
            playerName = args[1];
            playerUUID = voiceProxy.getPlayerUUID(playerName);
            if (playerUUID == null) {
                sender.sendMessage(String.format("Player '%s' not found", playerName));
                return;
            }
        } else if (sender.getUniqueId() != null) {
            playerName = null;
            playerUUID = sender.getUniqueId();
        } else {
            sender.sendMessage("Usage: " + getUsage(sender, "reconnect"));
            return;
        }

        if (sender.getUniqueId() != null && !sender.hasPermission(VoiceProxy.RECONNECT_OTHERS_PERMISSION)) {
            long remaining = getRemainingReconnectCooldown(sender.getUniqueId());
            if (remaining > 0L) {
                sender.sendMessage(String.format("You can reconnect again in %s seconds", remaining));
                return;
            }
        }

        if (!voiceProxy.reconnectPlayer(playerUUID)) {
            if (playerName == null) {
                sender.sendMessage("You are not connected to voice chat through this proxy");
            } else {
                sender.sendMessage(String.format("%s is not connected to voice chat through this proxy", playerName));
            }
            return;
        }
        // The backend server has to time out the old session before it sends a new secret
        if (playerName == null) {
            sender.sendMessage("Reconnecting you to voice chat, this can take a few seconds");
        } else {
            sender.sendMessage(String.format("Reconnecting %s to voice chat, this can take a few seconds", playerName));
        }
    }

    /**
     * Starts the reconnect cooldown of a player if it isn't running already.
     *
     * @return the remaining cooldown in seconds, <code>0</code> if the player can reconnect
     */
    private long getRemainingReconnectCooldown(UUID playerUUID) {
        long now = System.currentTimeMillis();
        reconnectTimes.values().removeIf(time -> now - time >= RECONNECT_COOLDOWN);
        Long lastReconnect = reconnectTimes.putIfAbsent(playerUUID, now);
        if (lastReconnect == null) {
            return 0L;
        }
        return Math.max(1L, (RECONNECT_COOLDOWN - (now - lastReconnect) + 999L) / 1000L);
    }

    private void ping(CommandSender sender, String[] args) {
        List<BackendServer> backendServers = voiceProxy.getBackendServers();
        if (args.length <= 1) {
            if (backendServers.isEmpty()) {
                sender.sendMessage("No servers available to ping");
                return;
            }
            sender.sendMessage("Servers available to ping:");
            for (BackendServer backendServer : backendServers) {
                sender.sendMessage(String.format("  - %s (%s)", backendServer.getName(), backendServer.getAddress()));
            }
            sender.sendMessage("Usage: " + getUsage(sender, "ping"));
            return;
        }
        if (args.length <= 2) {
            sender.sendMessage("No voice chat port provided");
            sender.sendMessage("Usage: " + getUsage(sender, "ping"));
            return;
        }
        if (args.length != 3) {
            sender.sendMessage("Too many arguments");
            sender.sendMessage("Usage: " + getUsage(sender, "ping"));
            return;
        }

        String serverName = args[1];
        int voiceChatPort;
        try {
            voiceChatPort = Integer.parseInt(args[2]);
            if (voiceChatPort <= 0 || voiceChatPort > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(String.format("Invalid voice chat port: %s", args[2]));
            return;
        }

        BackendServer server = backendServers
                .stream()
                .filter(s -> s.getName().equals(serverName))
                .findFirst()
                .orElseGet(() -> backendServers
                        .stream()
                        .filter(s -> s.getName().equalsIgnoreCase(serverName))
                        .findFirst()
                        .orElse(null)
                );

        if (server == null) {
            sender.sendMessage(String.format("Server '%s' not found", serverName));
            return;
        }

        try {
            PingManager.sendPing(server.getAddress(), voiceChatPort, 10, new PingManager.PingListener() {
                @Override
                public void onSend(int attempts) {
                    sender.sendMessage(String.format("Sending ping to server '%s'", server.getName()));
                }

                @Override
                public void onSuccessfulAttempt(int attempts, long pingMilliseconds) {
                    sender.sendMessage(String.format("Received response from server '%s' in %s ms", server.getName(), pingMilliseconds));
                }

                @Override
                public void onFailedAttempt(int attempts) {
                    sender.sendMessage(String.format("No response from server '%s'", server.getName()));
                }

                @Override
                public void onFinish(int successfulAttempts, int timeoutAttempts, long pingMilliseconds) {
                    if (successfulAttempts > 0) {
                        if (timeoutAttempts > 0) {
                            sender.sendMessage(String.format("Connection check unstable for server '%s' (Ping: %s ms, %s/%s attempts timed out)", server.getName(), pingMilliseconds, timeoutAttempts, successfulAttempts + timeoutAttempts));
                        } else {
                            sender.sendMessage(String.format("Connection check successful for server '%s' (Ping: %s ms)", server.getName(), pingMilliseconds));
                        }
                    } else {
                        sender.sendMessage(String.format("Connection check failed for server '%s'", server.getName()));
                    }
                }

                @Override
                public void onError(Exception e) {
                    sender.sendMessage(String.format("Error pinging server '%s': %s", server.getName(), e.getMessage()));
                }

            });
        } catch (Exception e) {
            sender.sendMessage(String.format("Error pinging server '%s': %s", server.getName(), e.getMessage()));
        }
    }

}
