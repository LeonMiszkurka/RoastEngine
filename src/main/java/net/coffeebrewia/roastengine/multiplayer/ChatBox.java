package net.coffeebrewia.roastengine.multiplayer;

import net.coffeebrewia.roastengine.input.Input;
import net.coffeebrewia.roastengine.net.Protocol;
import net.coffeebrewia.roastengine.render.Color;
import net.coffeebrewia.roastengine.render.Renderer2D;
import net.coffeebrewia.roastengine.ui.Theme;

import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The chat in the bottom-left corner. T or Enter opens the input line, Enter sends, Escape
 * cancels. Recent lines show for a few seconds and then fade; opening the input shows them all.
 *
 * <p>While the input is open the sandbox ignores movement and look keys, so typing "w" does not
 * walk the player forward.
 */
public final class ChatBox {

    private static final float VISIBLE_SECONDS = 10f;
    private static final float FADE_SECONDS = 1.5f;
    private static final int SHOWN_LINES = 8;
    private static final float SCALE = 1.5f;
    private static final float LINE_HEIGHT = 18f;
    /** Private messages, so they stand out from public chat. */
    private static final Color WHISPER = Color.rgb(0xC9A2F0);
    /** Moderators' and owners' public lines. */
    private static final Color STAFF = Color.rgb(0xF2C46B);

    private final StringBuilder draft = new StringBuilder();
    private boolean open;
    private float caretBlink;

    public boolean isOpen() {
        return open;
    }

    /**
     * Handles keys for this frame.
     *
     * @param allowOpen false while paused, so the menu keeps its keys
     * @return true while the chat has the keyboard
     */
    public boolean update(Input input, float dt, boolean allowOpen, Consumer<String> send) {
        caretBlink = (caretBlink + dt) % 1f;
        if (!open) {
            if (allowOpen && (input.wasKeyPressed(GLFW_KEY_T) || input.wasKeyPressed(GLFW_KEY_ENTER))) {
                // The key that opened the chat is not typed into it: this frame's text is skipped.
                open = true;
                draft.setLength(0);
                return true;
            }
            if (allowOpen && input.wasKeyPressed(GLFW_KEY_SLASH)) {
                open = true;
                draft.setLength(0);
                draft.append('/');
                return true;
            }
            return false;
        }

        if (input.wasKeyPressed(GLFW_KEY_ESCAPE)) {
            open = false;
            return true;
        }
        if (input.wasKeyPressed(GLFW_KEY_ENTER) || input.wasKeyPressed(GLFW_KEY_KP_ENTER)) {
            send.accept(draft.toString());
            open = false;
            return true;
        }
        if (input.wasKeyPressed(GLFW_KEY_BACKSPACE) && !draft.isEmpty()) {
            draft.setLength(draft.length() - 1);
        }
        String typed = input.isShortcutDown() && input.wasKeyPressed(GLFW_KEY_V)
                ? input.clipboard() : input.typedText();
        for (int i = 0; i < typed.length() && draft.length() < Protocol.MAX_CHAT; i++) {
            char ch = typed.charAt(i);
            if (ch >= 32 && ch < 127) {
                draft.append(ch);
            }
        }
        return true;
    }

    /** How a line reads: a rank tag before staff names, and private messages marked as such. */
    private static String format(MultiplayerSession.ChatLine line) {
        String tag = Protocol.rankTag(line.rank());
        String who = tag.isEmpty() ? line.from() : tag + " " + line.from();
        return switch (line.kind()) {
            case Protocol.Chat.SYSTEM -> line.text();
            case Protocol.Chat.WHISPER_FROM -> "[from " + who + "] " + line.text();
            case Protocol.Chat.WHISPER_TO -> "[to " + who + "] " + line.text();
            default -> who + ": " + line.text();
        };
    }

    public void draw(Renderer2D r, List<MultiplayerSession.ChatLine> lines, float now,
                     float windowWidth, float windowHeight) {
        float x = 12;
        float width = Math.min(620f, windowWidth - 24);
        float inputY = windowHeight - 76;
        float y = inputY - 8;
        Color shadow = Color.rgb(0x000000).withAlpha(0.6f);

        int shown = 0;
        for (int i = lines.size() - 1; i >= 0 && shown < SHOWN_LINES; i--) {
            MultiplayerSession.ChatLine line = lines.get(i);
            float age = now - line.time();
            float alpha = open ? 1f : Math.max(0f, Math.min(1f, (VISIBLE_SECONDS - age) / FADE_SECONDS));
            if (alpha <= 0f) {
                break; // older lines are older still
            }
            String text = format(line);
            y -= LINE_HEIGHT;
            Color color = switch (line.kind()) {
                case Protocol.Chat.SYSTEM -> Theme.ACCENT;
                case Protocol.Chat.WHISPER_FROM, Protocol.Chat.WHISPER_TO -> WHISPER;
                default -> line.rank() > Protocol.RANK_NORMAL ? STAFF : Theme.TEXT;
            };
            r.rect(x - 4, y - 2, width, LINE_HEIGHT, Color.rgb(0x000000).withAlpha(0.35f * alpha));
            String fitted = r.ellipsize(text, width - 8, SCALE);
            r.text(fitted, x + 1, y + 1, SCALE, shadow.withAlpha(0.6f * alpha));
            r.text(fitted, x, y, SCALE, color.withAlpha(alpha));
            shown++;
        }

        if (open) {
            r.rect(x - 4, inputY, width, 26, Theme.BACKGROUND.withAlpha(0.85f));
            r.outline(x - 4, inputY, width, 26, 1f, Theme.ACCENT);
            String shownDraft = draft.toString();
            while (!shownDraft.isEmpty() && r.textWidth(shownDraft, SCALE) > width - 24) {
                shownDraft = shownDraft.substring(1);
            }
            r.text(shownDraft, x + 4, inputY + 6, SCALE, Theme.TEXT);
            if (caretBlink < 0.5f) {
                r.rect(x + 6 + r.textWidth(shownDraft, SCALE), inputY + 5, 2, 16, Theme.TEXT);
            }
        }
    }
}
