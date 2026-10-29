package de.maxhenkel.voicechat.command;

import java.util.UUID;

public interface CommandSender {

    void sendMessage(String message);

    boolean hasPermission(String permission);

    /**
     * @return the UUID of the player that sent the command, <code>null</code> if the sender is not a player
     */
    UUID getUniqueId();

}
