package net.minecraft.world.item;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

public class LeadItem extends Item {
    public LeadItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.FENCES)) {
            Player player = context.getPlayer();
            if (!level.isClientSide() && player != null) {
                return bindPlayerMobs(player, level, pos, context.getHand()); // CraftBukkit - Pass hand
            }
        }

        return InteractionResult.PASS;
    }

    public static InteractionResult bindPlayerMobs(final Player player, final Level level, final BlockPos pos, final net.minecraft.world.InteractionHand hand) { // CraftBukkit - Add InteractionHand
        List<Leashable> entitiesToLeash = Leashable.leashableInArea(level, Vec3.atCenterOf(pos), l -> l.getLeashHolder() == player);
        if (entitiesToLeash.isEmpty()) {
            return InteractionResult.PASS;
        }

        Optional<LeashFenceKnotEntity> existingKnot = LeashFenceKnotEntity.getKnot(level, pos);
        LeashFenceKnotEntity activeKnot = existingKnot.orElseGet(() -> LeashFenceKnotEntity.createKnot(level, pos));
        // CraftBukkit start - fire HangingPlaceEvent
        if (existingKnot.isEmpty()) {
            org.bukkit.inventory.EquipmentSlot handSlot = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand);
            org.bukkit.event.hanging.HangingPlaceEvent event = new org.bukkit.event.hanging.HangingPlaceEvent((org.bukkit.entity.Hanging) activeKnot.getBukkitEntity(), player != null ? (org.bukkit.entity.Player) player.getBukkitEntity() : null, org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), org.bukkit.block.BlockFace.SELF, handSlot);
            level.getCraftServer().getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                activeKnot.discard();
                return InteractionResult.PASS;
            }
        }
        // CraftBukkit end
        boolean anyLeashed = false;

        for (Leashable leashable : entitiesToLeash) {
            if (leashable.canHaveALeashAttachedTo(activeKnot) && org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerLeashEntityEvent(leashable, activeKnot, player, hand)) { // Paper - leash event
                leashable.setLeashedTo(activeKnot, true);
                anyLeashed = true;
            }
        }

        if (anyLeashed) {
            activeKnot.playPlacementSound();
            level.gameEvent(GameEvent.BLOCK_ATTACH, pos, GameEvent.Context.of(player));
            return InteractionResult.SUCCESS_SERVER;
        }

        if (existingKnot.isEmpty()) {
            activeKnot.discard();
        }

        return InteractionResult.PASS;
    }

    // CraftBukkit start
    public static InteractionResult bindPlayerMobs(Player player, Level world, BlockPos pos) {
        return LeadItem.bindPlayerMobs(player, world, pos, net.minecraft.world.InteractionHand.MAIN_HAND);
    }
    // CraftBukkit end
}
