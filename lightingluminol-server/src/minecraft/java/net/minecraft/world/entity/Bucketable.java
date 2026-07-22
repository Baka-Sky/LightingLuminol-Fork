package net.minecraft.world.entity;

import java.util.Optional;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public interface Bucketable {
    boolean fromBucket();

    void setFromBucket(final boolean fromBucket);

    void saveToBucketTag(final ItemStack bucket);

    void loadFromBucketTag(final CompoundTag tag);

    ItemStack getBucketItemStack();

    SoundEvent getPickupSound();

    @Deprecated
    static void saveDefaultDataToBucketTag(final Mob entity, final ItemStack bucket) {
        bucket.copyFrom(DataComponents.CUSTOM_NAME, entity);
        CustomData.update(DataComponents.BUCKET_ENTITY_DATA, bucket, tag -> {
            if (entity.isNoAi()) {
                tag.putBoolean("NoAI", entity.isNoAi());
            }

            if (entity.isSilent()) {
                tag.putBoolean("Silent", entity.isSilent());
            }

            if (entity.isNoGravity()) {
                tag.putBoolean("NoGravity", entity.isNoGravity());
            }

            if (entity.hasGlowingTag()) {
                tag.putBoolean("Glowing", entity.hasGlowingTag());
            }

            if (entity.isInvulnerable()) {
                tag.putBoolean("Invulnerable", entity.isInvulnerable());
            }

            if (entity.isPersistenceRequired()) {
                tag.putBoolean("PersistenceRequired", entity.isPersistenceRequired());
            }

            tag.putFloat("Health", entity.getHealth());
        });
    }

    @Deprecated
    static void loadDefaultDataFromBucketTag(final Mob entity, final CompoundTag tag) {
        tag.getBoolean("NoAI").ifPresent(entity::setNoAi);
        tag.getBoolean("Silent").ifPresent(entity::setSilent);
        tag.getBoolean("NoGravity").ifPresent(entity::setNoGravity);
        tag.getBoolean("Glowing").ifPresent(entity::setGlowingTag);
        tag.getBoolean("Invulnerable").ifPresent(entity::setInvulnerable);
        tag.getBoolean("PersistenceRequired").ifPresent(required -> {
            if (required) {
                entity.setPersistenceRequired();
            }
        });
        tag.getFloat("Health").ifPresent(entity::setHealth);
    }

    static <T extends LivingEntity & Bucketable> Optional<InteractionResult> bucketMobPickup(
        final Player player, final InteractionHand hand, final T pickupEntity
    ) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (pickupEntity.canBePickedUpWithBucket(itemStack) && pickupEntity.isAlive()) {
            // CraftBukkit start
            // pickupEntity.playSound(pickupEntity.getPickupSound(), 1.0F, 1.0F); // CraftBukkit - moved down
            ItemStack bucket = pickupEntity.getBucketItemStack();
            pickupEntity.saveToBucketTag(bucket);
            org.bukkit.event.player.PlayerBucketEntityEvent playerBucketFishEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerFishBucketEvent(pickupEntity, player, itemStack, bucket, hand);
            bucket = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(playerBucketFishEvent.getEntityBucket());
            if (playerBucketFishEvent.isCancelled()) {
                // Paper start - Fix inventory desync
                if (player.hasInfiniteMaterials() || itemStack.getCount() > 1) {
                    player.containerMenu.sendAllDataToRemote();
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }
                // Paper end - Fix inventory desync
                pickupEntity.resendPossiblyDesyncedEntityData((ServerPlayer) player); // Paper
                return Optional.of(InteractionResult.FAIL);
            }
            pickupEntity.playSound(pickupEntity.getPickupSound(), 1.0F, 1.0F);
            // CraftBukkit end
            ItemStack result = ItemUtils.createFilledResult(itemStack, player, bucket, false);
            player.setItemInHand(hand, result);
            Level level = pickupEntity.level();
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                CriteriaTriggers.FILLED_BUCKET.trigger(serverPlayer, bucket);
            }

            if (pickupEntity instanceof Leashable leashable) {
                leashable.dropLeash();
            }

            pickupEntity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
            return Optional.of(InteractionResult.SUCCESS);
        } else {
            return Optional.empty();
        }
    }

    default boolean canBePickedUpWithBucket(final ItemStack itemStack) {
        return itemStack.getItem() == Items.WATER_BUCKET;
    }
}
