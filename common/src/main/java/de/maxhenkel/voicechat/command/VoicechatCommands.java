package de.maxhenkel.voicechat.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.permission.Permission;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.PingManager;
import de.maxhenkel.voicechat.voice.server.Server;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoicechatCommands {

    public static final String VOICECHAT_COMMAND = "voicechat";

    /**
     * How long players without the admin permission have to wait between reconnects
     */
    private static final long RECONNECT_COOLDOWN = 10_000L;
    private static final Map<UUID, Long> RECONNECT_TIMES = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> literalBuilder = Commands.literal(VOICECHAT_COMMAND);

        literalBuilder.executes(context -> help(dispatcher, context));
        literalBuilder.then(Commands.literal("help").executes(context -> help(dispatcher, context)));

        literalBuilder.then(Commands.literal("test")
                .requires(source -> checkPermission(source, PermissionManager.INSTANCE.ADMIN_PERMISSION))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> test(context.getSource(), EntityArgument.getPlayer(context, "target")))));

        literalBuilder.then(Commands.literal("reconnect")
                .requires(source -> checkPermission(source, PermissionManager.INSTANCE.RECONNECT_PERMISSION) || checkPermission(source, PermissionManager.INSTANCE.ADMIN_PERMISSION))
                .executes(context -> {
                    ServerPlayer player = getVoicechatPlayer(context.getSource());
                    if (player == null) {
                        return 0;
                    }
                    return reconnect(context.getSource(), player);
                })
                .then(Commands.argument("target", EntityArgument.player())
                        .requires(source -> checkPermission(source, PermissionManager.INSTANCE.ADMIN_PERMISSION))
                        .executes(context -> reconnect(context.getSource(), EntityArgument.getPlayer(context, "target")))));

        literalBuilder.then(Commands.literal("invite")
                .requires(CommandSourceStack::isPlayer)
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> invite(context.getSource(), EntityArgument.getPlayer(context, "target")))));

        literalBuilder.then(Commands.literal("join")
                .requires(CommandSourceStack::isPlayer)
                .then(Commands.argument("group_id", UuidArgument.uuid())
                        .executes(context -> joinGroupById(context.getSource(), UuidArgument.getUuid(context, "group_id"), null))
                        .then(Commands.argument("password", StringArgumentType.string())
                                .executes(context -> joinGroupById(context.getSource(), UuidArgument.getUuid(context, "group_id"), getPassword(context)))))
                .then(Commands.argument("group_name", StringArgumentType.string())
                        .suggests(GroupNameSuggestionProvider.INSTANCE)
                        .executes(context -> joinGroupByName(context.getSource(), StringArgumentType.getString(context, "group_name"), null))
                        .then(Commands.argument("password", StringArgumentType.string())
                                .executes(context -> joinGroupByName(context.getSource(), StringArgumentType.getString(context, "group_name"), getPassword(context))))));

        literalBuilder.then(Commands.literal("leave")
                .requires(CommandSourceStack::isPlayer)
                .executes(context -> leave(context.getSource())));

        dispatcher.register(literalBuilder);
    }

    private static int test(CommandSourceStack source, ServerPlayer player) {
        Server server = getServer(source);
        if (server == null || !checkTargetHasVoicechat(source, player)) {
            return 0;
        }

        ClientConnection clientConnection = server.getConnections().get(player.getUUID());
        if (clientConnection == null) {
            source.sendFailure(Component.translatable("message.voicechat.client_not_connected"));
            return 0;
        }
        try {
            source.sendSuccess(() -> Component.translatable("message.voicechat.sending_ping"), false);

            server.getPingManager().sendPing(clientConnection, 500, 10, new PingManager.PingListener() {

                @Override
                public void onPong(int attempts, long pingMilliseconds) {
                    if (attempts <= 1) {
                        source.sendSuccess(() -> Component.translatable("message.voicechat.ping_received", pingMilliseconds), false);
                    } else {
                        source.sendSuccess(() -> Component.translatable("message.voicechat.ping_received_attempt", pingMilliseconds, attempts), false);
                    }
                }

                @Override
                public void onFailedAttempt(int attempts) {
                    source.sendSuccess(() -> Component.translatable("message.voicechat.ping_retry"), false);
                }

                @Override
                public void onTimeout(int attempts) {
                    source.sendFailure(Component.translatable("message.voicechat.ping_timed_out", attempts));
                }
            });
            source.sendSuccess(() -> Component.translatable("message.voicechat.ping_sent_waiting"), false);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("message.voicechat.failed_to_send_ping", e.getMessage()));
            Voicechat.LOGGER.warn("Failed to send ping", e);
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int reconnect(CommandSourceStack source, ServerPlayer player) {
        Server server = getServer(source);
        if (server == null || !checkTargetHasVoicechat(source, player)) {
            return 0;
        }

        ServerPlayer sender = source.getPlayer();
        if (sender != null && !PermissionManager.INSTANCE.ADMIN_PERMISSION.hasPermission(sender)) {
            long remaining = getRemainingReconnectCooldown(sender.getUUID());
            if (remaining > 0L) {
                source.sendFailure(Component.translatableWithFallback("message.voicechat.reconnect_cooldown", "You can reconnect again in %s seconds", remaining));
                return 0;
            }
        }

        server.reconnectClient(player);
        if (player.equals(source.getEntity())) {
            source.sendSuccess(() -> Component.translatableWithFallback("message.voicechat.reconnect_self", "Reconnecting you to voice chat"), false);
        } else {
            source.sendSuccess(() -> Component.translatableWithFallback("message.voicechat.reconnect_other", "Reconnecting %s to voice chat", player.getDisplayName()), true);
        }
        return Command.SINGLE_SUCCESS;
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

    private static int invite(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        ServerPlayer sender = getVoicechatPlayer(source);
        if (sender == null) {
            return 0;
        }
        if (!CommonCompatibilityManager.INSTANCE.canSee(sender, player)) {
            throw EntityArgument.NO_PLAYERS_FOUND.create();
        }
        Server server = getServer(source);
        if (server == null) {
            return 0;
        }

        PlayerState state = server.getPlayerStateManager().getState(sender.getUUID());
        Group group = state == null || !state.hasGroup() ? null : server.getGroupManager().getGroup(state.getGroup());
        if (group == null) {
            source.sendFailure(Component.translatable("message.voicechat.not_in_group"));
            return 0;
        }

        if (!checkTargetHasVoicechat(source, player)) {
            return 0;
        }

        String passwordSuffix = group.getPassword() == null ? "" : " \"" + group.getPassword() + "\"";
        player.sendSystemMessage(Component.translatable("message.voicechat.invite", sender.getDisplayName(), Component.literal(group.getName()).withStyle(ChatFormatting.GRAY), ComponentUtils.wrapInSquareBrackets(Component.translatable("message.voicechat.accept_invite").withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/%s join %s%s".formatted(VOICECHAT_COMMAND, group.getId(), passwordSuffix))).withHoverEvent(new HoverEvent.ShowText(Component.translatable("message.voicechat.accept_invite.hover"))))).withStyle(ChatFormatting.GREEN)));

        source.sendSuccess(() -> Component.translatable("message.voicechat.invite_successful", player.getDisplayName()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int leave(CommandSourceStack source) {
        ServerPlayer player = getVoicechatPlayer(source);
        if (player == null) {
            return 0;
        }
        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            source.sendFailure(Component.translatable("message.voicechat.groups_disabled"));
            return 0;
        }
        Server server = getServer(source);
        if (server == null) {
            return 0;
        }

        PlayerState state = server.getPlayerStateManager().getState(player.getUUID());
        if (state == null || !state.hasGroup()) {
            source.sendFailure(Component.translatable("message.voicechat.not_in_group"));
            return 0;
        }

        server.getGroupManager().leaveGroup(player);
        source.sendSuccess(() -> Component.translatable("message.voicechat.leave_successful"), false);
        return Command.SINGLE_SUCCESS;
    }

    @Nullable
    private static String getPassword(CommandContext<CommandSourceStack> context) {
        String password = StringArgumentType.getString(context, "password");
        return password.isEmpty() ? null : password;
    }

    /**
     * Checks if the source can join groups.
     *
     * @return the voice chat server or <code>null</code> if the source can't join groups
     */
    @Nullable
    private static Server checkCanJoinGroup(CommandSourceStack source, ServerPlayer player) {
        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            source.sendFailure(Component.translatable("message.voicechat.groups_disabled"));
            return null;
        }
        if (!PermissionManager.INSTANCE.GROUPS_PERMISSION.hasPermission(player)) {
            source.sendFailure(Component.translatable("message.voicechat.no_group_permission"));
            return null;
        }
        return getServer(source);
    }

    private static int joinGroupByName(CommandSourceStack source, String groupName, @Nullable String password) {
        ServerPlayer player = getVoicechatPlayer(source);
        if (player == null) {
            return 0;
        }
        Server server = checkCanJoinGroup(source, player);
        if (server == null) {
            return 0;
        }

        List<Group> groups = server.getGroupManager().getGroups().values().stream().filter(group -> group.getName().equals(groupName)).toList();

        if (groups.isEmpty()) {
            source.sendFailure(Component.translatable("message.voicechat.group_does_not_exist"));
            return 0;
        }

        if (groups.size() > 1) {
            source.sendFailure(Component.translatable("message.voicechat.group_name_not_unique"));
            return 0;
        }

        return joinGroup(source, player, server, groups.getFirst().getId(), password);
    }

    private static int joinGroupById(CommandSourceStack source, UUID groupID, @Nullable String password) {
        ServerPlayer player = getVoicechatPlayer(source);
        if (player == null) {
            return 0;
        }
        Server server = checkCanJoinGroup(source, player);
        if (server == null) {
            return 0;
        }
        return joinGroup(source, player, server, groupID, password);
    }

    private static int joinGroup(CommandSourceStack source, ServerPlayer player, Server server, UUID groupID, @Nullable String password) {
        Group group = server.getGroupManager().getGroup(groupID);

        if (group == null) {
            source.sendFailure(Component.translatable("message.voicechat.group_does_not_exist"));
            return 0;
        }

        server.getGroupManager().joinGroup(group, player, password);
        source.sendSuccess(() -> Component.translatable("message.voicechat.join_successful", Component.literal(group.getName()).withStyle(ChatFormatting.GRAY)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int help(CommandDispatcher<CommandSourceStack> dispatcher, CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        // Console and admins can use some commands without voice chat, players without it can't use any
        ServerPlayer player = source.getPlayer();
        if (player != null && !Voicechat.SERVER.isCompatible(player) && !PermissionManager.INSTANCE.ADMIN_PERMISSION.hasPermission(player)) {
            sendVoicechatNeeded(source);
            return 0;
        }
        CommandNode<CommandSourceStack> voicechatCommand = dispatcher.getRoot().getChild(VOICECHAT_COMMAND);
        Map<CommandNode<CommandSourceStack>, String> map = dispatcher.getSmartUsage(voicechatCommand, source);
        source.sendSuccess(() -> Component.literal(CommonCompatibilityManager.INSTANCE.getModName()).withStyle(ChatFormatting.GOLD), false);
        for (String usage : map.values()) {
            String command = "/%s %s".formatted(VOICECHAT_COMMAND, usage);
            source.sendSuccess(() -> Component.literal(command).withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(command))), false);
        }
        return map.size();
    }

    /**
     * Gets the player that executed the command and checks if they have voice chat installed.
     *
     * @return the player or <code>null</code> if the source is not a player or has no voice chat installed
     */
    @Nullable
    private static ServerPlayer getVoicechatPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(Voicechat.TRANSLATIONS.playerCommandMessage.get()));
            return null;
        }
        if (!Voicechat.SERVER.isCompatible(player)) {
            sendVoicechatNeeded(source);
            return null;
        }
        return player;
    }

    private static void sendVoicechatNeeded(CommandSourceStack source) {
        source.sendFailure(Component.literal(Voicechat.TRANSLATIONS.voicechatNeededForCommandMessage.get().formatted(CommonCompatibilityManager.INSTANCE.getModName())));
    }

    private static boolean checkTargetHasVoicechat(CommandSourceStack source, ServerPlayer target) {
        if (Voicechat.SERVER.isCompatible(target)) {
            return true;
        }
        source.sendFailure(Component.translatable("message.voicechat.player_no_voicechat", target.getDisplayName(), CommonCompatibilityManager.INSTANCE.getModName()));
        return false;
    }

    @Nullable
    private static Server getServer(CommandSourceStack source) {
        Server server = Voicechat.SERVER.getServer();
        if (server == null) {
            source.sendFailure(Component.translatable("message.voicechat.voice_chat_unavailable"));
        }
        return server;
    }

    private static boolean checkPermission(CommandSourceStack stack, Permission permission) {
        ServerPlayer player = stack.getPlayer();
        if (player != null) {
            return permission.hasPermission(player);
        }
        return stack.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(PermissionLevel.ADMINS));
    }

}
