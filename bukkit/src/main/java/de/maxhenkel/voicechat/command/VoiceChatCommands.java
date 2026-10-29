package de.maxhenkel.voicechat.command;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.FallbackCompatibility;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.PingManager;
import de.maxhenkel.voicechat.voice.server.Server;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class VoiceChatCommands implements CommandExecutor, TabCompleter {

    public static final String VOICECHAT_COMMAND = "voicechat";

    /**
     * How long players without the admin permission have to wait between reconnects
     */
    private static final long RECONNECT_COOLDOWN = 10_000L;
    private static final Map<UUID, Long> RECONNECT_TIMES = new ConcurrentHashMap<>();

    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
        String subcommand = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "help";

        // Console and admins can use some commands without voice chat, players without it can't use any
        if (commandSender instanceof Player && !isAdmin(commandSender) && checkNoVoicechat(commandSender)) {
            return true;
        }

        switch (subcommand) {
            case "test":
                if (isAdmin(commandSender)) {
                    return testCommand(commandSender, label, args);
                }
                break;
            case "reconnect":
                if (canReconnect(commandSender)) {
                    return reconnectCommand(commandSender, label, args);
                }
                break;
            case "invite":
                return inviteCommand(commandSender, label, args);
            case "join":
                return joinCommand(commandSender, label, args);
            case "leave":
                return leaveCommand(commandSender);
        }
        return helpCommand(commandSender, label);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return tabCompleteList(args[0], getSubcommands(commandSender));
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if (subcommand.equals("invite") || (isAdmin(commandSender) && (subcommand.equals("test") || subcommand.equals("reconnect")))) {
                return tabCompleteList(args[1], getVisiblePlayerNames(commandSender));
            } else if (subcommand.equals("join")) {
                return tabCompleteList(args[1], getGroupNames());
            }
        }

        return Collections.emptyList();
    }

    private static List<String> getSubcommands(CommandSender commandSender) {
        List<String> subcommands = new ArrayList<>();
        subcommands.add("help");
        if (isAdmin(commandSender)) {
            subcommands.add("test");
        }
        if (canReconnect(commandSender)) {
            subcommands.add("reconnect");
        }
        if (commandSender instanceof Player) {
            subcommands.add("invite");
            subcommands.add("join");
            subcommands.add("leave");
        }
        return subcommands;
    }

    private static List<String> getVisiblePlayerNames(CommandSender commandSender) {
        return commandSender.getServer().getOnlinePlayers().stream()
                .filter(player -> !(commandSender instanceof Player) || Voicechat.compatibility.canSee((Player) commandSender, player))
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    private static List<String> getGroupNames() {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            return Collections.emptyList();
        }
        return server.getGroupManager().getGroups().values().stream()
                .map(Group::getName)
                .distinct()
                .map(name -> name.contains(" ") ? String.format("\"%s\"", name) : name)
                .collect(Collectors.toList());
    }

    private static List<String> tabCompleteList(String arg, List<String> list) {
        String lowerArg = arg.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lowerArg)).collect(Collectors.toList());
    }

    private boolean helpCommand(CommandSender commandSender, String label) {
        commandSender.sendMessage(ChatColor.GOLD + BuildConstants.PLUGIN_NAME);
        for (String subcommand : getSubcommands(commandSender)) {
            commandSender.sendMessage(getUsage(commandSender, label, subcommand));
        }
        return true;
    }

    private static String getUsage(CommandSender commandSender, String label, String subcommand) {
        String usage;
        switch (subcommand) {
            case "test":
            case "invite":
                usage = subcommand + " <target>";
                break;
            case "reconnect":
                if (!isAdmin(commandSender)) {
                    usage = subcommand;
                } else if (commandSender instanceof Player) {
                    usage = subcommand + " [<target>]";
                } else {
                    usage = subcommand + " <target>";
                }
                break;
            case "join":
                usage = subcommand + " <group> [<password>]";
                break;
            default:
                usage = subcommand;
                break;
        }
        return String.format("/%s %s", label, usage);
    }

    private boolean testCommand(CommandSender commandSender, String label, String[] args) {
        if (args.length < 2) {
            commandSender.sendMessage(getUsage(commandSender, label, "test"));
            return true;
        }

        Server server = getServer(commandSender);
        if (server == null) {
            return true;
        }

        Player player = parsePlayer(commandSender, args[1]);
        if (player == null) {
            sendMessage(commandSender, "argument.entity.notfound.player");
            return true;
        }

        if (!checkTargetHasVoicechat(commandSender, player)) {
            return true;
        }

        ClientConnection clientConnection = server.getConnections().get(player.getUniqueId());

        if (clientConnection == null) {
            sendMessage(commandSender, "message.voicechat.client_not_connected");
            return true;
        }

        try {
            sendMessage(commandSender, "message.voicechat.sending_ping");
            server.getPingManager().sendPing(clientConnection, 500, 10, new PingManager.PingListener() {

                @Override
                public void onPong(int attempts, long pingMilliseconds) {
                    if (attempts <= 1) {
                        sendMessage(commandSender, "message.voicechat.ping_received", String.valueOf(pingMilliseconds));
                    } else {
                        sendMessage(commandSender, "message.voicechat.ping_received_attempt", String.valueOf(pingMilliseconds), String.valueOf(attempts));
                    }
                }

                @Override
                public void onFailedAttempt(int attempts) {
                    sendMessage(commandSender, "message.voicechat.ping_retry");
                }

                @Override
                public void onTimeout(int attempts) {
                    sendMessage(commandSender, "message.voicechat.ping_timed_out", String.valueOf(attempts));
                }
            });
            sendMessage(commandSender, "message.voicechat.ping_sent_waiting");
        } catch (Exception e) {
            sendMessage(commandSender, "message.voicechat.failed_to_send_ping", e.getMessage());
            Voicechat.LOGGER.warn("Failed to send ping", e);
        }
        return true;
    }

    private boolean reconnectCommand(CommandSender commandSender, String label, String[] args) {
        Player player;
        if (args.length >= 2 && isAdmin(commandSender)) {
            player = parsePlayer(commandSender, args[1]);
            if (player == null) {
                sendMessage(commandSender, "argument.entity.notfound.player");
                return true;
            }
        } else if (commandSender instanceof Player) {
            if (checkNoVoicechat(commandSender)) {
                return true;
            }
            player = (Player) commandSender;
        } else {
            commandSender.sendMessage(getUsage(commandSender, label, "reconnect"));
            return true;
        }

        Server server = getServer(commandSender);
        if (server == null || !checkTargetHasVoicechat(commandSender, player)) {
            return true;
        }

        if (commandSender instanceof Player && !isAdmin(commandSender)) {
            long remaining = getRemainingReconnectCooldown(((Player) commandSender).getUniqueId());
            if (remaining > 0L) {
                commandSender.sendMessage(FallbackCompatibility.fallbackTranslation("message.voicechat.reconnect_cooldown", String.valueOf(remaining)));
                return true;
            }
        }

        server.reconnectClient(player);
        // Stock clients don't know these translation keys
        if (player.equals(commandSender)) {
            commandSender.sendMessage(FallbackCompatibility.fallbackTranslation("message.voicechat.reconnect_self"));
        } else {
            commandSender.sendMessage(FallbackCompatibility.fallbackTranslation("message.voicechat.reconnect_other", player.getName()));
        }
        return true;
    }

    /**
     * Starts the reconnect cooldown of a player if it isn't running already.
     *
     * @return the remaining cooldown in seconds, <code>0</code> if the player can reconnect
     */
    private static long getRemainingReconnectCooldown(UUID playerUUID) {
        long now = System.currentTimeMillis();
        RECONNECT_TIMES.values().removeIf(time -> now - time >= RECONNECT_COOLDOWN);
        Long lastReconnect = RECONNECT_TIMES.putIfAbsent(playerUUID, now);
        if (lastReconnect == null) {
            return 0L;
        }
        return Math.max(1L, (RECONNECT_COOLDOWN - (now - lastReconnect) + 999L) / 1000L);
    }

    private boolean inviteCommand(CommandSender commandSender, String label, String[] args) {
        Player sender = getVoicechatPlayer(commandSender);
        if (sender == null) {
            return true;
        }

        if (args.length < 2) {
            commandSender.sendMessage(getUsage(commandSender, label, "invite"));
            return true;
        }

        Server server = getServer(commandSender);
        if (server == null) {
            return true;
        }

        Player player = parsePlayer(commandSender, args[1]);

        if (player == null || !Voicechat.compatibility.canSee(sender, player)) {
            sendMessage(commandSender, "argument.entity.notfound.player");
            return true;
        }

        PlayerState state = server.getPlayerStateManager().getState(sender.getUniqueId());
        Group group = state == null || !state.hasGroup() ? null : server.getGroupManager().getGroup(state.getGroup());
        if (group == null) {
            sendMessage(commandSender, "message.voicechat.not_in_group");
            return true;
        }

        if (!checkTargetHasVoicechat(commandSender, player)) {
            return true;
        }

        String passwordSuffix = group.getPassword() == null ? "" : String.format(" \"%s\"", group.getPassword().replace("\\", "\\\\").replace("\"", "\\\""));
        String cmd = "/" + VOICECHAT_COMMAND + " join " + group.getId().toString() + passwordSuffix;
        Voicechat.compatibility.sendInviteMessage(player, sender, group.getName(), cmd);

        sendMessage(commandSender, "message.voicechat.invite_successful", player.getName());

        return true;
    }

    private boolean joinCommand(CommandSender commandSender, String label, String[] args) {
        Player sender = getVoicechatPlayer(commandSender);
        if (sender == null) {
            return true;
        }

        String input = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (input.isEmpty()) {
            commandSender.sendMessage(getUsage(commandSender, label, "join"));
            return true;
        }

        Server server = getServer(commandSender);
        if (server == null) {
            return true;
        }

        UUID groupUUID;
        int groupEnd;
        if (input.startsWith("\"")) {
            StringBuilder groupName = new StringBuilder();
            groupEnd = readQuoted(input, groupName);
            groupUUID = getGroupUUID(sender, server, groupName.toString());
        } else {
            groupEnd = input.indexOf(' ');
            if (groupEnd < 0) {
                groupEnd = input.length();
            }
            String group = input.substring(0, groupEnd);
            try {
                groupUUID = UUID.fromString(group);
            } catch (Exception e) {
                groupUUID = getGroupUUID(sender, server, group);
            }
        }

        if (groupUUID == null) {
            return true;
        }

        String rest = input.substring(groupEnd).trim();
        String password = null;
        if (rest.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            readQuoted(rest, sb);
            password = sb.toString();
        } else if (!rest.isEmpty()) {
            password = rest;
        }

        joinGroup(sender, server, groupUUID, password);
        return true;
    }

    private static int readQuoted(String input, StringBuilder value) {
        int i = 1;
        for (; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\' && i + 1 < input.length()) {
                i++;
                c = input.charAt(i);
            }
            value.append(c);
        }
        return i;
    }

    @Nullable
    private UUID getGroupUUID(Player commandSender, Server server, String groupName) {
        List<Group> groups = server.getGroupManager().getGroups().values().stream().filter(group -> group.getName().equals(groupName)).collect(Collectors.toList());

        if (groups.isEmpty()) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_does_not_exist");
            return null;
        }

        if (groups.size() > 1) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_name_not_unique");
            return null;
        }

        return groups.get(0).getId();
    }

    private static void joinGroup(Player commandSender, Server server, UUID groupID, @Nullable String password) {
        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            NetManager.sendMessage(commandSender, "message.voicechat.groups_disabled");
            return;
        }

        if (!commandSender.hasPermission(PermissionManager.GROUPS_PERMISSION)) {
            NetManager.sendMessage(commandSender, "message.voicechat.no_group_permission");
            return;
        }

        Group group = server.getGroupManager().getGroup(groupID);

        if (group == null) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_does_not_exist");
            return;
        }

        server.getGroupManager().joinGroup(group, commandSender, password);
        NetManager.sendMessage(commandSender, "message.voicechat.join_successful", group.getName()); //TODO Make group name green
    }

    private boolean leaveCommand(CommandSender commandSender) {
        Player sender = getVoicechatPlayer(commandSender);
        if (sender == null) {
            return true;
        }

        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            NetManager.sendMessage(sender, "message.voicechat.groups_disabled");
            return true;
        }

        Server server = getServer(commandSender);
        if (server == null) {
            return true;
        }

        PlayerState state = server.getPlayerStateManager().getState(sender.getUniqueId());
        if (state == null || !state.hasGroup()) {
            NetManager.sendMessage(sender, "message.voicechat.not_in_group");
            return true;
        }

        server.getGroupManager().leaveGroup(sender);
        NetManager.sendMessage(sender, "message.voicechat.leave_successful");
        return true;
    }

    private static boolean isAdmin(CommandSender commandSender) {
        return commandSender.hasPermission(PermissionManager.ADMIN_PERMISSION);
    }

    private static boolean canReconnect(CommandSender commandSender) {
        return isAdmin(commandSender) || (commandSender instanceof Player && commandSender.hasPermission(PermissionManager.RECONNECT_PERMISSION));
    }

    /**
     * Sends a translated message. The console doesn't know the translations of the client, so it gets the fallback translation.
     */
    private static void sendMessage(CommandSender commandSender, String key, String... args) {
        if (commandSender instanceof Player) {
            NetManager.sendMessage((Player) commandSender, key, args);
        } else {
            commandSender.sendMessage(FallbackCompatibility.fallbackTranslation(key, args));
        }
    }

    @Nullable
    private static Server getServer(CommandSender commandSender) {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            sendMessage(commandSender, "message.voicechat.voice_chat_unavailable");
        }
        return server;
    }

    private static boolean checkTargetHasVoicechat(CommandSender commandSender, Player target) {
        if (Voicechat.SERVER.isCompatible(target)) {
            return true;
        }
        sendMessage(commandSender, "message.voicechat.player_no_voicechat", target.getDisplayName(), BuildConstants.PLUGIN_NAME);
        return false;
    }

    /**
     * Gets the player that executed the command and checks if they have voice chat installed.
     *
     * @return the player or <code>null</code> if the sender is not a player or has no voice chat installed
     */
    @Nullable
    private static Player getVoicechatPlayer(CommandSender commandSender) {
        if (checkNoVoicechat(commandSender)) {
            return null;
        }
        return (Player) commandSender;
    }

    private static boolean checkNoVoicechat(CommandSender commandSender) {
        if (commandSender instanceof Player) {
            if (Voicechat.SERVER.isCompatible((Player) commandSender)) {
                return false;
            }
            commandSender.sendMessage(String.format(Voicechat.TRANSLATIONS.voicechatNeededForCommandMessage.get(), BuildConstants.PLUGIN_NAME));
        } else {
            commandSender.sendMessage(Voicechat.TRANSLATIONS.playerCommandMessage.get());
        }
        return true;
    }

    @Nullable
    public static Player parsePlayer(CommandSender commandSender, String playerArg) {
        Player player = commandSender.getServer().getPlayer(playerArg);
        if (player != null) {
            return player;
        }
        try {
            UUID uuid = UUID.fromString(playerArg);
            return commandSender.getServer().getPlayer(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
