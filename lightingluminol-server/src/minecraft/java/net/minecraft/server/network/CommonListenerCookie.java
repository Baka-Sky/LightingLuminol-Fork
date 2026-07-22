package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;

public record CommonListenerCookie(GameProfile gameProfile, int latency, ClientInformation clientInformation, boolean transferred, @org.jspecify.annotations.Nullable String clientBrand, java.util.Set<String> channels, io.papermc.paper.util.KeepAlive keepAlive) { // Paper // Paper
    public static CommonListenerCookie createInitial(final GameProfile gameProfile, final boolean transferred) {
        return new CommonListenerCookie(gameProfile, 0, ClientInformation.createDefault(), transferred, null, new java.util.HashSet<>(), new io.papermc.paper.util.KeepAlive()); // Paper // Paper
    }
}
