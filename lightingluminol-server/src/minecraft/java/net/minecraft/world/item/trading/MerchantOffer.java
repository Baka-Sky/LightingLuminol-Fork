package net.minecraft.world.item.trading;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

public class MerchantOffer {
    public static final Codec<MerchantOffer> CODEC = RecordCodecBuilder.create(
        i -> i.group(
                ItemCost.CODEC.fieldOf("buy").forGetter(o -> o.baseCostA),
                ItemCost.CODEC.lenientOptionalFieldOf("buyB").forGetter(o -> o.costB),
                ItemStack.CODEC.fieldOf("sell").forGetter(o -> o.result),
                Codec.INT.lenientOptionalFieldOf("uses", 0).forGetter(o -> o.uses),
                Codec.INT.lenientOptionalFieldOf("maxUses", 4).forGetter(o -> o.maxUses),
                Codec.BOOL.lenientOptionalFieldOf("rewardExp", true).forGetter(o -> o.rewardExp),
                Codec.INT.lenientOptionalFieldOf("specialPrice", 0).forGetter(o -> o.specialPriceDiff),
                Codec.INT.lenientOptionalFieldOf("demand", 0).forGetter(o -> o.demand),
                Codec.FLOAT.lenientOptionalFieldOf("priceMultiplier", 0.0F).forGetter(o -> o.priceMultiplier),
                Codec.INT.lenientOptionalFieldOf("xp", 1).forGetter(o -> o.xp)
                , Codec.BOOL.lenientOptionalFieldOf("Paper.IgnoreDiscounts", false).forGetter(offer -> offer.ignoreDiscounts) // Paper
            )
            .apply(i, MerchantOffer::new)
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, MerchantOffer> STREAM_CODEC = StreamCodec.of(
        MerchantOffer::writeToStream, MerchantOffer::createFromStream
    );
    public ItemCost baseCostA;
    public Optional<ItemCost> costB;
    private final ItemStack result;
    public int uses;
    public int maxUses;
    public boolean rewardExp;
    private int specialPriceDiff;
    public int demand;
    public float priceMultiplier;
    public int xp;
    public boolean ignoreDiscounts; // Paper - Add ignore discounts API

    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.@org.jspecify.annotations.Nullable CraftMerchantRecipe bukkitHandle;

    public org.bukkit.craftbukkit.inventory.CraftMerchantRecipe asBukkit() {
        return (this.bukkitHandle == null) ? this.bukkitHandle = new org.bukkit.craftbukkit.inventory.CraftMerchantRecipe(this) : this.bukkitHandle;
    }

    public MerchantOffer(ItemCost baseCostA, Optional<ItemCost> costB, ItemStack result, int uses, int maxUses, int experience, float priceMultiplier, int demand, final boolean ignoreDiscounts, org.bukkit.craftbukkit.inventory.CraftMerchantRecipe bukkit) { // Paper
        this(baseCostA, costB, result, uses, maxUses, experience, priceMultiplier, demand);
        this.ignoreDiscounts = ignoreDiscounts; // Paper
        this.bukkitHandle = bukkit;
    }
    // CraftBukkit end

    private MerchantOffer(
        final ItemCost baseCostA,
        final Optional<ItemCost> costB,
        final ItemStack result,
        final int uses,
        final int maxUses,
        final boolean rewardExp,
        final int specialPriceDiff,
        final int demand,
        final float priceMultiplier,
        final int xp
        , final boolean ignoreDiscounts // Paper
    ) {
        this.baseCostA = baseCostA;
        this.costB = costB;
        this.result = result;
        this.uses = uses;
        this.maxUses = maxUses;
        this.rewardExp = rewardExp;
        this.specialPriceDiff = specialPriceDiff;
        this.demand = demand;
        this.priceMultiplier = priceMultiplier;
        this.xp = xp;
        this.ignoreDiscounts = ignoreDiscounts; // Paper
    }

    public MerchantOffer(final ItemCost buy, final ItemStack result, final int maxUses, final int xp, final float priceMultiplier) {
        this(buy, Optional.empty(), result, maxUses, xp, priceMultiplier);
    }

    public MerchantOffer(
        final ItemCost baseCostA, final Optional<ItemCost> costB, final ItemStack result, final int maxUses, final int xp, final float priceMultiplier
    ) {
        this(baseCostA, costB, result, 0, maxUses, xp, priceMultiplier);
    }

    public MerchantOffer(
        final ItemCost baseCostA,
        final Optional<ItemCost> costB,
        final ItemStack result,
        final int uses,
        final int maxUses,
        final int xp,
        final float priceMultiplier
    ) {
        this(baseCostA, costB, result, uses, maxUses, xp, priceMultiplier, 0);
    }

    public MerchantOffer(
        final ItemCost baseCostA,
        final Optional<ItemCost> costB,
        final ItemStack result,
        final int uses,
        final int maxUses,
        final int xp,
        final float priceMultiplier,
        final int demand
    ) {
        this(baseCostA, costB, result, uses, maxUses, true, 0, demand, priceMultiplier, xp, false); // Paper
    }

    private MerchantOffer(final MerchantOffer offer) {
        this(
            offer.baseCostA,
            offer.costB,
            offer.result.copy(),
            offer.uses,
            offer.maxUses,
            offer.rewardExp,
            offer.specialPriceDiff,
            offer.demand,
            offer.priceMultiplier,
            offer.xp
            , offer.ignoreDiscounts // Paper
        );
    }

    public ItemStack getBaseCostA() {
        return this.baseCostA.itemStack();
    }

    public ItemStack getCostA() {
        return this.baseCostA.itemStack().copyWithCount(this.getModifiedCostCount(this.baseCostA));
    }

    private int getModifiedCostCount(final ItemCost cost) {
        int basePrice = cost.count();
        int demandDiff = Math.max(0, Mth.floor(basePrice * this.demand * this.priceMultiplier));
        return Mth.clamp(basePrice + demandDiff + this.specialPriceDiff, 1, cost.itemStack().getMaxStackSize());
    }

    public ItemStack getCostB() {
        return this.costB.map(ItemCost::itemStack).orElse(ItemStack.EMPTY);
    }

    public ItemCost getItemCostA() {
        return this.baseCostA;
    }

    public Optional<ItemCost> getItemCostB() {
        return this.costB;
    }

    public ItemStack getResult() {
        return this.result;
    }

    public void updateDemand() {
        this.demand = this.demand + this.uses - (this.maxUses - this.uses);
        if (io.papermc.paper.configuration.GlobalConfiguration.get().misc.preventNegativeVillagerDemand) this.demand = Math.max(0, this.demand); // Paper - Fix MC-163962
    }

    public ItemStack assemble() {
        return this.result.copy();
    }

    public int getUses() {
        return this.uses;
    }

    public void resetUses() {
        this.uses = 0;
    }

    public int getMaxUses() {
        return this.maxUses;
    }

    public void increaseUses() {
        this.uses++;
    }

    public int getDemand() {
        return this.demand;
    }

    public void addToSpecialPriceDiff(final int add) {
        this.specialPriceDiff += add;
    }

    public void resetSpecialPriceDiff() {
        this.specialPriceDiff = 0;
    }

    public int getSpecialPriceDiff() {
        return this.specialPriceDiff;
    }

    public void setSpecialPriceDiff(final int value) {
        this.specialPriceDiff = value;
    }

    public float getPriceMultiplier() {
        return this.priceMultiplier;
    }

    public int getXp() {
        return this.xp;
    }

    public boolean isOutOfStock() {
        return this.uses >= this.maxUses;
    }

    public void setToOutOfStock() {
        this.uses = this.maxUses;
    }

    public boolean needsRestock() {
        return this.uses > 0;
    }

    public boolean shouldRewardExp() {
        return this.rewardExp;
    }

    public boolean satisfiedBy(final ItemStack buyA, final ItemStack buyB) {
        if (!this.baseCostA.test(buyA) || buyA.getCount() < this.getModifiedCostCount(this.baseCostA)) {
            return false;
        } else {
            return !this.costB.isPresent() ? buyB.isEmpty() : this.costB.get().test(buyB) && buyB.getCount() >= this.costB.get().count();
        }
    }

    public boolean take(final ItemStack buyA, final ItemStack buyB) {
        if (!this.satisfiedBy(buyA, buyB)) {
            return false;
        }

        if (!this.getCostA().isEmpty()) { // CraftBukkit
        buyA.shrink(this.getCostA().getCount());
        } // CraftBukkit
        if (!this.getCostB().isEmpty()) {
            buyB.shrink(this.getCostB().getCount());
        }

        return true;
    }

    public MerchantOffer copy() {
        return new MerchantOffer(this);
    }

    private static void writeToStream(final RegistryFriendlyByteBuf output, final MerchantOffer offer) {
        ItemCost.STREAM_CODEC.encode(output, offer.getItemCostA());
        ItemStack.STREAM_CODEC.encode(output, offer.getResult());
        ItemCost.OPTIONAL_STREAM_CODEC.encode(output, offer.getItemCostB());
        output.writeBoolean(offer.isOutOfStock());
        output.writeInt(offer.getUses());
        output.writeInt(offer.getMaxUses());
        output.writeInt(offer.getXp());
        output.writeInt(offer.getSpecialPriceDiff());
        output.writeFloat(offer.getPriceMultiplier());
        output.writeInt(offer.getDemand());
    }

    public static MerchantOffer createFromStream(final RegistryFriendlyByteBuf input) {
        ItemCost buy = ItemCost.STREAM_CODEC.decode(input);
        ItemStack sell = ItemStack.STREAM_CODEC.decode(input);
        Optional<ItemCost> buyB = ItemCost.OPTIONAL_STREAM_CODEC.decode(input);
        boolean isExhausted = input.readBoolean();
        int uses = input.readInt();
        int maxUses = input.readInt();
        int xp = input.readInt();
        int specialPriceDiff = input.readInt();
        float priceMultiplier = input.readFloat();
        int demand = input.readInt();
        MerchantOffer offer = new MerchantOffer(buy, buyB, sell, uses, maxUses, xp, priceMultiplier, demand);
        if (isExhausted) {
            offer.setToOutOfStock();
        }

        offer.setSpecialPriceDiff(specialPriceDiff);
        return offer;
    }
}
