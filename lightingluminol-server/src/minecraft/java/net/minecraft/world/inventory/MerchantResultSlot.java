package net.minecraft.world.inventory;

import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;

public class MerchantResultSlot extends Slot {
    private final MerchantContainer slots;
    private final Player player;
    private int removeCount;
    private final Merchant merchant;

    public MerchantResultSlot(final Player player, final Merchant merchant, final MerchantContainer slots, final int id, final int x, final int y) {
        super(slots, id, x, y);
        this.player = player;
        this.merchant = merchant;
        this.slots = slots;
    }

    @Override
    public boolean mayPlace(final ItemStack itemStack) {
        return false;
    }

    @Override
    public ItemStack remove(final int amount) {
        if (this.hasItem()) {
            this.removeCount = this.removeCount + Math.min(amount, this.getItem().getCount());
        }

        return super.remove(amount);
    }

    @Override
    protected void onQuickCraft(final ItemStack picked, final int count) {
        this.removeCount += count;
        this.checkTakeAchievements(picked);
    }

    @Override
    protected void checkTakeAchievements(final ItemStack carried) {
        carried.onCraftedBy(this.player, this.removeCount);
        this.removeCount = 0;
    }

    @Override
    public void onTake(final Player player, final ItemStack carried) {
        // this.checkTakeAchievements(carried); // Paper - Add PlayerTradeEvent and PlayerPurchaseEvent; move to after event is called and not cancelled
        MerchantOffer offer = this.slots.getActiveOffer();
        // Paper start - Add PlayerTradeEvent and PlayerPurchaseEvent
        io.papermc.paper.event.player.PlayerPurchaseEvent event = null;
        if (offer != null && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            if (this.merchant instanceof net.minecraft.world.entity.npc.villager.AbstractVillager abstractVillager) {
                event = new io.papermc.paper.event.player.PlayerTradeEvent(serverPlayer.getBukkitEntity(), (org.bukkit.entity.AbstractVillager) abstractVillager.getBukkitEntity(), offer.asBukkit(), true, true);
            } else if (this.merchant instanceof org.bukkit.craftbukkit.inventory.CraftMerchantCustom.MinecraftMerchant minecraftMerchant) {
                event = new io.papermc.paper.event.player.PlayerPurchaseEvent(serverPlayer.getBukkitEntity(), minecraftMerchant.getCraftMerchant(), offer.asBukkit(), false, true);
            }
            if (event != null) {
                if (!event.callEvent()) {
                    carried.setCount(0);
                    player.containerMenu.sendAllDataToRemote();
                    int level = merchant instanceof net.minecraft.world.entity.npc.villager.Villager villager ? villager.getVillagerData().level() : 1;
                    serverPlayer.sendMerchantOffers(player.containerMenu.containerId, merchant.getOffers(), level, merchant.getVillagerXp(), merchant.showProgressBar(), merchant.canRestock());
                    return;
                }
                offer = org.bukkit.craftbukkit.inventory.CraftMerchantRecipe.fromBukkit(event.getTrade()).toMinecraft();
            }
        }
        this.checkTakeAchievements(carried);
        // Paper end - Add PlayerTradeEvent and PlayerPurchaseEvent
        if (offer != null) {
            ItemStack buyA = this.slots.getItem(0);
            ItemStack buyB = this.slots.getItem(1);
            if (offer.take(buyA, buyB) || offer.take(buyB, buyA)) {
                this.merchant.processTrade(offer, event); // Paper - Add PlayerTradeEvent and PlayerPurchaseEvent
                player.awardStat(Stats.TRADED_WITH_VILLAGER);
                this.slots.setItem(0, buyA);
                this.slots.setItem(1, buyB);
            }

            this.merchant.overrideXp(this.merchant.getVillagerXp() + offer.getXp());
        }
    }
}
