package com.razorclient.feature.module.impl;

import com.razorclient.feature.module.Category;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import org.lwjgl.input.Keyboard;

/** Shared friendly-player policy used by combat and rendering consumers. */
public final class TeamsModule extends Module {
    private static volatile TeamsModule instance;

    private final BooleanSetting scoreboardTeam = new BooleanSetting("Scoreboard Team", true);
    private final BooleanSetting matchOwnNametag = new BooleanSetting("Match Own Nametag", true);
    private final BooleanSetting black = new BooleanSetting("Color Black", false);
    private final BooleanSetting darkBlue = new BooleanSetting("Color Dark Blue", false);
    private final BooleanSetting darkGreen = new BooleanSetting("Color Dark Green", false);
    private final BooleanSetting darkAqua = new BooleanSetting("Color Dark Aqua", false);
    private final BooleanSetting darkRed = new BooleanSetting("Color Dark Red", false);
    private final BooleanSetting darkPurple = new BooleanSetting("Color Dark Purple", false);
    private final BooleanSetting gold = new BooleanSetting("Color Gold", false);
    private final BooleanSetting gray = new BooleanSetting("Color Gray", false);
    private final BooleanSetting darkGray = new BooleanSetting("Color Dark Gray", false);
    private final BooleanSetting blue = new BooleanSetting("Color Blue", false);
    private final BooleanSetting green = new BooleanSetting("Color Green", false);
    private final BooleanSetting aqua = new BooleanSetting("Color Aqua", false);
    private final BooleanSetting red = new BooleanSetting("Color Red", false);
    private final BooleanSetting lightPurple = new BooleanSetting("Color Light Purple", false);
    private final BooleanSetting yellow = new BooleanSetting("Color Yellow", false);
    private final BooleanSetting white = new BooleanSetting("Color White", false);

    public TeamsModule() {
        super("Teams", "Prevents combat modules from targeting friendly players.", Category.COMBAT, Keyboard.KEY_NONE);
        instance = this;
        addSetting(scoreboardTeam);
        addSetting(matchOwnNametag);
        addSetting(black);
        addSetting(darkBlue);
        addSetting(darkGreen);
        addSetting(darkAqua);
        addSetting(darkRed);
        addSetting(darkPurple);
        addSetting(gold);
        addSetting(gray);
        addSetting(darkGray);
        addSetting(blue);
        addSetting(green);
        addSetting(aqua);
        addSetting(red);
        addSetting(lightPurple);
        addSetting(yellow);
        addSetting(white);
    }

    public static boolean isTeammate(EntityPlayer candidate) {
        TeamsModule module = instance;
        if (module == null || !module.isEnabled() || candidate == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.thePlayer == null || candidate == minecraft.thePlayer) {
            return candidate == minecraft.thePlayer;
        }

        Team ownTeam = minecraft.thePlayer.getTeam();
        Team candidateTeam = candidate.getTeam();
        if (module.scoreboardTeam.isEnabled() && ownTeam != null && ownTeam.isSameTeam(candidateTeam)) {
            return true;
        }

        char candidateColor = colorCode(candidate);
        if (module.matchOwnNametag.isEnabled()) {
            char ownColor = colorCode(minecraft.thePlayer);
            if (ownColor != 0 && ownColor == candidateColor) {
                return true;
            }
        }
        return module.isManualColor(candidateColor);
    }

    @Override
    public String getHudInfo() {
        return matchOwnNametag.isEnabled() ? "Nametag" : scoreboardTeam.isEnabled() ? "Scoreboard" : "Colors";
    }

    private boolean isManualColor(char color) {
        switch (color) {
            case '0': return black.isEnabled();
            case '1': return darkBlue.isEnabled();
            case '2': return darkGreen.isEnabled();
            case '3': return darkAqua.isEnabled();
            case '4': return darkRed.isEnabled();
            case '5': return darkPurple.isEnabled();
            case '6': return gold.isEnabled();
            case '7': return gray.isEnabled();
            case '8': return darkGray.isEnabled();
            case '9': return blue.isEnabled();
            case 'a': return green.isEnabled();
            case 'b': return aqua.isEnabled();
            case 'c': return red.isEnabled();
            case 'd': return lightPurple.isEnabled();
            case 'e': return yellow.isEnabled();
            case 'f': return white.isEnabled();
            default: return false;
        }
    }

    private static char colorCode(EntityPlayer player) {
        Team team = player.getTeam();
        String formatted = team == null
            ? player.getDisplayName().getFormattedText()
            : ScorePlayerTeam.formatPlayerName(team, player.getName());
        char active = 0;
        for (int index = 0; index + 1 < formatted.length(); index++) {
            if (formatted.charAt(index) != '\u00A7') {
                continue;
            }
            char code = Character.toLowerCase(formatted.charAt(++index));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                active = code;
            } else if (code == 'r') {
                active = 0;
            }
        }
        return active;
    }
}
