package de.maxhenkel.voicechat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.RootCommandNode;
import de.maxhenkel.voicechat.command.VoicechatCommands;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.intercompatibility.PaperCommonCompatibilityManager;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.command.brigadier.ApiMirrorRootNode;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class VoicechatBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        if (!isSupportedMinecraftVersion()) {
            // The plugin disables itself when it gets enabled, which closes its class loader.
            // Commands registered here would outlive that and fail every time the command tree is sent to a player.
            context.getLogger().error("Incompatible Minecraft version - Not registering commands, this plugin requires Minecraft {}", BuildConstants.MINECRAFT_VERSION);
            return;
        }
        LifecycleEventManager<BootstrapContext> lifecycleManager = context.getLifecycleManager();
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS, (event) -> {
            PaperCommonCompatibilityManager manager = (PaperCommonCompatibilityManager) CommonCompatibilityManager.INSTANCE;
            Commands registrar = event.registrar();
            RootCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> root = registrar.getDispatcher().getRoot();
            if (root instanceof ApiMirrorRootNode apiMirrorRootNode) {
                CommandDispatcher<CommandSourceStack> dispatcher = apiMirrorRootNode.getDispatcher();
                manager.onRegisterCommands(dispatcher);
                VoicechatCommands.register(dispatcher);
            } else {
                Voicechat.LOGGER.error("Failed to register commands");
            }

        });
    }

    /**
     * @return <code>true</code> if the server runs the Minecraft version this plugin was built for
     */
    public static boolean isSupportedMinecraftVersion() {
        try {
            return BuildConstants.MINECRAFT_VERSION.equals(ServerBuildInfo.buildInfo().minecraftVersionId());
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        return new VoicechatPaperPlugin();
    }

}
