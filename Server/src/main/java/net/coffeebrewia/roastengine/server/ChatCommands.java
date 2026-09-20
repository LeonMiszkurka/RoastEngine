package net.coffeebrewia.roastengine.server;

import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.net.Protocol.Chat;

import java.util.ArrayList;
import java.util.List;

/**
 * The /commands players type in chat.
 *
 * <pre>
 *   everyone     /help  /list  /msg &lt;name&gt; &lt;text&gt;  /r &lt;text&gt;
 *   moderator    /kick &lt;name&gt; [reason]  /ban &lt;name&gt; [reason]
 *   admin        /unban &lt;name&gt;
 * </pre>
 *
 * Nobody can kick or ban someone of their own rank or above, so moderators cannot remove each
 * other, the admins or the owner. Bans are kept by the account service, so they cover every server, and it
 * checks the ranks again itself. Ranks are never changed from here - only in the DynamoDB console.
 */
final class ChatCommands {

    private final RoastServer server;

    ChatCommands(RoastServer server) {
        this.server = server;
    }

    void run(ClientConnection sender, String line) {
        String[] parts = line.substring(1).split("\\s+", 3);
        String command = parts[0].toLowerCase();
        String first = parts.length > 1 ? parts[1] : "";
        String rest = parts.length > 2 ? parts[2].trim() : "";
        switch (command) {
            case "help", "?" -> help(sender);
            case "list", "who", "online" -> tell(sender, server.playerList());
            case "msg", "w", "tell", "pm", "whisper" -> whisper(sender, first, rest);
            case "r", "reply" -> reply(sender, line);
            case "kick" -> kick(sender, first, rest);
            case "ban" -> ban(sender, first, rest);
            case "unban" -> unban(sender, first);
            default -> tell(sender, "Unknown command /" + command + ". Type /help for the list.");
        }
    }

    private void help(ClientConnection sender) {
        List<String> lines = new ArrayList<>(List.of(
                "/msg <name> <text> - private message  (also /w, /tell, /pm)",
                "/r <text> - reply to the last one   /list - who's on   /help - this"));
        if (sender.rank >= Protocol.RANK_MODERATOR) {
            lines.add("/kick <name> [reason]   /ban <name> [reason] - bans from every server");
        }
        if (sender.rank >= Protocol.RANK_ADMIN) {
            lines.add("/unban <name> - lets a banned player back in");
        }
        lines.forEach(text -> tell(sender, text));
    }

    // --- Private messages -------------------------------------------------------------

    private void whisper(ClientConnection sender, String name, String text) {
        if (name.isEmpty() || text.isEmpty()) {
            tell(sender, "Usage: /msg <name> <message>");
            return;
        }
        ClientConnection target = server.player(name);
        if (target == null) {
            tell(sender, name + " isn't online.");
            return;
        }
        if (target == sender) {
            tell(sender, "Talking to yourself?");
            return;
        }
        deliver(sender, target, text);
    }

    private void reply(ClientConnection sender, String line) {
        int space = line.indexOf(' ');
        String text = space < 0 ? "" : line.substring(space + 1).trim();
        String to = sender.lastWhisperFrom;
        if (to == null) {
            tell(sender, "Nobody has messaged you yet.");
            return;
        }
        if (text.isEmpty()) {
            tell(sender, "Usage: /r <message>");
            return;
        }
        ClientConnection target = server.player(to);
        if (target == null) {
            tell(sender, to + " isn't online any more.");
            return;
        }
        deliver(sender, target, text);
    }

    private void deliver(ClientConnection sender, ClientConnection target, String text) {
        target.send(new Chat(Chat.WHISPER_FROM, sender.name, sender.rank, text));
        sender.send(new Chat(Chat.WHISPER_TO, target.name, target.rank, text));
        target.lastWhisperFrom = sender.name;
        // Not logged: private messages stay private.
    }

    // --- Moderation -------------------------------------------------------------------

    /** The online target, if the sender may moderate them; otherwise tells the sender why not. */
    private ClientConnection moderatable(ClientConnection sender, String name, String usage) {
        if (sender.rank < Protocol.RANK_MODERATOR) {
            tell(sender, "Only moderators and owners can do that.");
            return null;
        }
        if (name.isEmpty()) {
            tell(sender, usage);
            return null;
        }
        ClientConnection target = server.player(name);
        if (target != null && target.rank >= sender.rank) {
            tell(sender, "You can't do that to " + target.name + ".");
            return null;
        }
        return target;
    }

    private void kick(ClientConnection sender, String name, String reason) {
        ClientConnection target = moderatable(sender, name, "Usage: /kick <name> [reason]");
        if (target == null) {
            if (sender.rank >= Protocol.RANK_MODERATOR && !name.isEmpty() && server.player(name) == null) {
                tell(sender, name + " isn't online.");
            }
            return;
        }
        String why = reason.isEmpty() ? "" : ": " + reason;
        target.close("You were kicked by " + sender.name + why);
        server.broadcastSystem(target.name + " was kicked by " + sender.name + why);
        RoastServer.log(sender.name + " kicked " + target.name + why);
    }

    private void ban(ClientConnection sender, String name, String reason) {
        if (sender.rank < Protocol.RANK_MODERATOR) {
            tell(sender, "Only moderators and owners can do that.");
            return;
        }
        if (server.accounts() == null) {
            tell(sender, "This server has no account service, so it can't ban. Use /kick.");
            return;
        }
        if (name.isEmpty()) {
            tell(sender, "Usage: /ban <name> [reason]");
            return;
        }
        ClientConnection online = server.player(name);
        if (online != null && online.rank >= sender.rank) {
            tell(sender, "You can't do that to " + online.name + ".");
            return;
        }
        String why = reason.isEmpty() ? "No reason given" : reason;
        try {
            // Works for players who are offline too; the service checks the ranks again.
            server.accounts().ban(sender.name, name, why);
        } catch (AccountService.Refused e) {
            tell(sender, e.getMessage());
            return;
        }
        ClientConnection target = server.player(name);
        String shown = target != null ? target.name : name;
        if (target != null) {
            target.close("You were banned by " + sender.name + ": " + why);
        }
        server.broadcastSystem(shown + " was banned by " + sender.name + ": " + why);
        RoastServer.log(sender.name + " banned " + shown + ": " + why);
    }

    private void unban(ClientConnection sender, String name) {
        if (sender.rank < Protocol.RANK_ADMIN) {
            tell(sender, "Only admins and owners can unban.");
            return;
        }
        if (server.accounts() == null) {
            tell(sender, "This server has no account service, so there are no bans.");
            return;
        }
        if (name.isEmpty()) {
            tell(sender, "Usage: /unban <name>");
            return;
        }
        try {
            server.accounts().unban(sender.name, name);
        } catch (AccountService.Refused e) {
            tell(sender, e.getMessage());
            return;
        }
        tell(sender, name + " is unbanned.");
        RoastServer.log(sender.name + " unbanned " + name);
    }

    private static void tell(ClientConnection player, String text) {
        player.send(Chat.system(text));
    }
}
