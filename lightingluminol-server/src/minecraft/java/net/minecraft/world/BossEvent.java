package net.minecraft.world;

import com.mojang.serialization.Codec;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public abstract class BossEvent {
    private final UUID id;
    public Component name;
    protected float progress;
    public BossEvent.BossBarColor color;
    public BossEvent.BossBarOverlay overlay;
    protected boolean darkenScreen;
    protected boolean playBossMusic;
    protected boolean createWorldFog;
    public net.kyori.adventure.bossbar.BossBar adventure; // Paper

    public BossEvent(final UUID id, final Component name, final BossEvent.BossBarColor color, final BossEvent.BossBarOverlay overlay) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.overlay = overlay;
        this.progress = 1.0F;
    }

    public UUID getId() {
        return this.id;
    }

    public Component getName() {
        if (this.adventure != null) return io.papermc.paper.adventure.PaperAdventure.asVanilla(this.adventure.name()); // Paper
        return this.name;
    }

    public void setName(final Component name) {
        if (this.adventure != null) this.adventure.name(io.papermc.paper.adventure.PaperAdventure.asAdventure(name)); // Paper
        this.name = name;
    }

    public float getProgress() {
        if (this.adventure != null) return this.adventure.progress(); // Paper
        return this.progress;
    }

    public void setProgress(final float progress) {
        if (this.adventure != null) this.adventure.progress(progress); // Paper
        this.progress = progress;
    }

    public BossEvent.BossBarColor getColor() {
        if (this.adventure != null) return io.papermc.paper.adventure.PaperAdventure.asVanilla(this.adventure.color()); // Paper
        return this.color;
    }

    public void setColor(final BossEvent.BossBarColor color) {
        if (this.adventure != null) this.adventure.color(io.papermc.paper.adventure.PaperAdventure.asAdventure(color)); // Paper
        this.color = color;
    }

    public BossEvent.BossBarOverlay getOverlay() {
        if (this.adventure != null) return io.papermc.paper.adventure.PaperAdventure.asVanilla(this.adventure.overlay()); // Paper
        return this.overlay;
    }

    public void setOverlay(final BossEvent.BossBarOverlay overlay) {
        if (this.adventure != null) this.adventure.overlay(io.papermc.paper.adventure.PaperAdventure.asAdventure(overlay)); // Paper
        this.overlay = overlay;
    }

    public boolean shouldDarkenScreen() {
        if (this.adventure != null) return this.adventure.hasFlag(net.kyori.adventure.bossbar.BossBar.Flag.DARKEN_SCREEN); // Paper
        return this.darkenScreen;
    }

    public BossEvent setDarkenScreen(final boolean darkenScreen) {
        if (this.adventure != null) io.papermc.paper.adventure.PaperAdventure.setFlag(this.adventure, net.kyori.adventure.bossbar.BossBar.Flag.DARKEN_SCREEN, darkenScreen); // Paper
        this.darkenScreen = darkenScreen;
        return this;
    }

    public boolean shouldPlayBossMusic() {
        if (this.adventure != null) return this.adventure.hasFlag(net.kyori.adventure.bossbar.BossBar.Flag.PLAY_BOSS_MUSIC); // Paper
        return this.playBossMusic;
    }

    public BossEvent setPlayBossMusic(final boolean playBossMusic) {
        if (this.adventure != null) io.papermc.paper.adventure.PaperAdventure.setFlag(this.adventure, net.kyori.adventure.bossbar.BossBar.Flag.PLAY_BOSS_MUSIC, playBossMusic); // Paper
        this.playBossMusic = playBossMusic;
        return this;
    }

    public BossEvent setCreateWorldFog(final boolean createWorldFog) {
        if (this.adventure != null) io.papermc.paper.adventure.PaperAdventure.setFlag(this.adventure, net.kyori.adventure.bossbar.BossBar.Flag.CREATE_WORLD_FOG, createWorldFog); // Paper
        this.createWorldFog = createWorldFog;
        return this;
    }

    public boolean shouldCreateWorldFog() {
        if (this.adventure != null) return this.adventure.hasFlag(net.kyori.adventure.bossbar.BossBar.Flag.CREATE_WORLD_FOG); // Paper
        return this.createWorldFog;
    }

    public enum BossBarColor implements StringRepresentable {
        PINK("pink", ChatFormatting.RED),
        BLUE("blue", ChatFormatting.BLUE),
        RED("red", ChatFormatting.DARK_RED),
        GREEN("green", ChatFormatting.GREEN),
        YELLOW("yellow", ChatFormatting.YELLOW),
        PURPLE("purple", ChatFormatting.DARK_BLUE),
        WHITE("white", ChatFormatting.WHITE);

        public static final Codec<BossEvent.BossBarColor> CODEC = StringRepresentable.fromEnum(BossEvent.BossBarColor::values);
        private final String name;
        private final ChatFormatting formatting;

        BossBarColor(final String name, final ChatFormatting formatting) {
            this.name = name;
            this.formatting = formatting;
        }

        public ChatFormatting getFormatting() {
            return this.formatting;
        }

        public String getName() {
            return this.name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public enum BossBarOverlay implements StringRepresentable {
        PROGRESS("progress"),
        NOTCHED_6("notched_6"),
        NOTCHED_10("notched_10"),
        NOTCHED_12("notched_12"),
        NOTCHED_20("notched_20");

        public static final Codec<BossEvent.BossBarOverlay> CODEC = StringRepresentable.fromEnum(BossEvent.BossBarOverlay::values);
        private final String name;

        BossBarOverlay(final String name) {
            this.name = name;
        }

        public String getName() {
            return this.name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
