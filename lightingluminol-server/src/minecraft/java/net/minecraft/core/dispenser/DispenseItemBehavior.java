package net.minecraft.core.dispenser;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PostSpawnProcessor;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

public interface DispenseItemBehavior {
    Logger LOGGER = LogUtils.getLogger();
    DispenseItemBehavior NOOP = (source, dispensed) -> dispensed;

    ItemStack dispense(BlockSource source, ItemStack dispensed);

    static void bootStrap() {
        DispenserBlock.registerProjectileBehavior(Items.ARROW);
        DispenserBlock.registerProjectileBehavior(Items.TIPPED_ARROW);
        DispenserBlock.registerProjectileBehavior(Items.SPECTRAL_ARROW);
        DispenserBlock.registerProjectileBehavior(Items.EGG);
        DispenserBlock.registerProjectileBehavior(Items.BLUE_EGG);
        DispenserBlock.registerProjectileBehavior(Items.BROWN_EGG);
        DispenserBlock.registerProjectileBehavior(Items.SNOWBALL);
        DispenserBlock.registerProjectileBehavior(Items.EXPERIENCE_BOTTLE);
        DispenserBlock.registerProjectileBehavior(Items.SPLASH_POTION);
        DispenserBlock.registerProjectileBehavior(Items.LINGERING_POTION);
        DispenserBlock.registerProjectileBehavior(Items.FIREWORK_ROCKET);
        DispenserBlock.registerProjectileBehavior(Items.FIRE_CHARGE);
        DispenserBlock.registerProjectileBehavior(Items.WIND_CHARGE);
        DispenserBlock.registerBehavior(
            Items.ARMOR_STAND,
            new DefaultDispenseItemBehavior() {
                @Override
                public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                    Direction direction = source.state().getValue(DispenserBlock.FACING);
                    BlockPos pos = source.pos().relative(direction);
                    ServerLevel serverLevel = source.level();
                    // CraftBukkit start
                    ItemStack singleItemStack = dispensed.copyWithCount(1);
                    org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(serverLevel, source.pos());
                    org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(singleItemStack);

                    org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), new org.bukkit.util.Vector(0, 0, 0));
                    serverLevel.getCraftServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return dispensed;
                    }

                    boolean shrink = true;
                    if (!event.getItem().equals(craftItem)) {
                        shrink = false;
                        // Chain to handler for new item
                        ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
                        DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
                        if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                            dispenseBehavior.dispense(source, eventStack);
                            return dispensed;
                        }
                    }
                    // CraftBukkit end

                    final ItemStack newStack = org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(event.getItem()); // Paper - use event itemstack (unwrap is fine here because the stack won't be modified)
                    PostSpawnProcessor<ArmorStand> postSpawnConfig = EntityType.appendDefaultStackConfig(
                        armorStandx -> armorStandx.setYRot(direction.toYRot()), serverLevel, newStack, null // Paper - track changed items in the dispense event
                    );
                    ArmorStand armorStand = EntityTypes.ARMOR_STAND.spawn(serverLevel, postSpawnConfig, pos, EntitySpawnReason.DISPENSER, false, false);
                    if (armorStand != null) {
                        if (shrink) dispensed.shrink(1); // Paper
                    }

                    return dispensed;
                }
            }
        );
        DispenserBlock.registerBehavior(
            Items.CHEST,
            new OptionalDispenseItemBehavior() {
                @Override
                public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                    BlockPos pos = source.pos().relative(source.state().getValue(DispenserBlock.FACING));

                    for (AbstractChestedHorse abstractChestedHorse : source.level()
                        .getEntitiesOfClass(AbstractChestedHorse.class, new AABB(pos), entity -> entity.isAlive() && !entity.hasChest())) {
                        if (abstractChestedHorse.isTamed()) {
                            SlotAccess slot = abstractChestedHorse.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
                            // CraftBukkit start
                            if (slot != null/* && slot.set(dispensed)*/) {
                                ItemStack singleCopy = dispensed.copyWithCount(1);
                                org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(source.level(), source.pos());
                                org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(singleCopy);
                                org.bukkit.event.block.BlockDispenseArmorEvent event = new org.bukkit.event.block.BlockDispenseArmorEvent(block, craftItem.clone(), abstractChestedHorse.getBukkitLivingEntity());

                                if (!event.callEvent()) {
                                    this.setSuccess(false);
                                    return dispensed;
                                }

                                boolean shrink = true;
                                if (!event.getItem().equals(craftItem)) {
                                    shrink = false;
                                    // Chain to handler for new item
                                    ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
                                    DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
                                    if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                                        dispenseBehavior.dispense(source, eventStack);
                                        return dispensed;
                                    }
                                }
                                slot.set(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()));
                                // CraftBukkit end

                                if (shrink) dispensed.shrink(1); // Paper - actually handle here
                                this.setSuccess(true);
                                return dispensed;
                            }
                        }
                    }

                    return super.execute(source, dispensed);
                }
            }
        );
        DispenserBlock.registerBehavior(Items.OAK_BOAT, new BoatDispenseItemBehavior(EntityTypes.OAK_BOAT));
        DispenserBlock.registerBehavior(Items.SPRUCE_BOAT, new BoatDispenseItemBehavior(EntityTypes.SPRUCE_BOAT));
        DispenserBlock.registerBehavior(Items.BIRCH_BOAT, new BoatDispenseItemBehavior(EntityTypes.BIRCH_BOAT));
        DispenserBlock.registerBehavior(Items.JUNGLE_BOAT, new BoatDispenseItemBehavior(EntityTypes.JUNGLE_BOAT));
        DispenserBlock.registerBehavior(Items.DARK_OAK_BOAT, new BoatDispenseItemBehavior(EntityTypes.DARK_OAK_BOAT));
        DispenserBlock.registerBehavior(Items.ACACIA_BOAT, new BoatDispenseItemBehavior(EntityTypes.ACACIA_BOAT));
        DispenserBlock.registerBehavior(Items.CHERRY_BOAT, new BoatDispenseItemBehavior(EntityTypes.CHERRY_BOAT));
        DispenserBlock.registerBehavior(Items.MANGROVE_BOAT, new BoatDispenseItemBehavior(EntityTypes.MANGROVE_BOAT));
        DispenserBlock.registerBehavior(Items.PALE_OAK_BOAT, new BoatDispenseItemBehavior(EntityTypes.PALE_OAK_BOAT));
        DispenserBlock.registerBehavior(Items.BAMBOO_RAFT, new BoatDispenseItemBehavior(EntityTypes.BAMBOO_RAFT));
        DispenserBlock.registerBehavior(Items.OAK_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.OAK_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.SPRUCE_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.SPRUCE_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.BIRCH_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.BIRCH_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.JUNGLE_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.JUNGLE_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.DARK_OAK_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.DARK_OAK_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.ACACIA_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.ACACIA_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.CHERRY_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.CHERRY_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.MANGROVE_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.MANGROVE_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.PALE_OAK_CHEST_BOAT, new BoatDispenseItemBehavior(EntityTypes.PALE_OAK_CHEST_BOAT));
        DispenserBlock.registerBehavior(Items.BAMBOO_CHEST_RAFT, new BoatDispenseItemBehavior(EntityTypes.BAMBOO_CHEST_RAFT));
        DispenseItemBehavior filledBucketBehavior = new DefaultDispenseItemBehavior() {
            private final DefaultDispenseItemBehavior defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();

            @Override
            public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                DispensibleContainerItem bucket = (DispensibleContainerItem)dispensed.getItem();
                BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                Level level = source.level();
                // CraftBukkit start
                BlockState state = level.getBlockState(target);
                ItemStack dispensedItem = dispensed; // Paper - track changed item from the dispense event
                // Paper start - correctly check if the bucket place will succeed
                /* Taken from SolidBucketItem#emptyContents */
                boolean willEmptyContentsSolidBucketItem = bucket instanceof net.minecraft.world.item.SolidBucketItem && level.isInWorldBounds(target) && state.isAir();
                /* Taken from BucketItem#emptyContents */
                boolean willEmptyBucketItem = bucket instanceof final net.minecraft.world.item.BucketItem bucketItem && bucketItem.getContent() instanceof net.minecraft.world.level.material.FlowingFluid && (state.isAir() || state.canBeReplaced(bucketItem.getContent()) || (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer liquidBlockContainer && liquidBlockContainer.canPlaceLiquid(null, level, target, state, bucketItem.getContent())));
                if (willEmptyContentsSolidBucketItem || willEmptyBucketItem) {
                // Paper end - correctly check if the bucket place will succeed
                    org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
                    org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(dispensed.copyWithCount(1)); // Paper - single item in event

                    org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), org.bukkit.craftbukkit.util.CraftVector.toBukkit(target));
                    level.getCraftServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return dispensed;
                    }

                    if (!event.getItem().equals(craftItem)) {
                        // Chain to handler for new item
                        ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
                        DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
                        if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                            dispenseBehavior.dispense(source, eventStack);
                            return dispensed;
                        }
                    }

                    // Paper start - track changed item from dispense event
                    dispensedItem = org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(event.getItem()); // unwrap is safe here as the stack isn't mutated
                    bucket = (DispensibleContainerItem)dispensedItem.getItem();
                    // Paper end - track changed item from dispense event
                }
                // CraftBukkit end

                if (bucket.emptyContents(null, level, target, null)) {
                    bucket.checkExtraContent(null, level, dispensedItem, target); // Paper - track changed item from dispense event
                    return this.consumeWithRemainder(source, dispensed, new ItemStack(Items.BUCKET));
                } else {
                    return this.defaultDispenseItemBehavior.dispense(source, dispensed);
                }
            }
        };
        DispenserBlock.registerBehavior(Items.LAVA_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.WATER_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.POWDER_SNOW_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.SALMON_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.COD_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.PUFFERFISH_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.TROPICAL_FISH_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.AXOLOTL_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.SULFUR_CUBE_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.TADPOLE_BUCKET, filledBucketBehavior);
        DispenserBlock.registerBehavior(Items.BUCKET, new DefaultDispenseItemBehavior() {
            @Override
            public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                LevelAccessor level = source.level();
                BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                BlockState blockState = level.getBlockState(target);
                if (blockState.getBlock() instanceof BucketPickup bucket) {
                    ItemStack pickup = bucket.pickupBlock(null, org.bukkit.craftbukkit.util.DummyLevelAccessor.INSTANCE, target, blockState); // CraftBukkit
                    if (pickup.isEmpty()) {
                        return super.execute(source, dispensed);
                    }

                    level.gameEvent(null, GameEvent.FLUID_PICKUP, target);
                    Item targetType = pickup.getItem();
                    // Paper start - Call BlockDispenseEvent
                    ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, target, dispensed, this);
                    if (result != null) {
                        return result;
                    }
                    // Paper end - Call BlockDispenseEvent
                    pickup = bucket.pickupBlock(null, level, target, blockState); // CraftBukkit - from above
                    return this.consumeWithRemainder(source, dispensed, new ItemStack(targetType));
                } else {
                    return super.execute(source, dispensed);
                }
            }
        });
        DispenserBlock.registerBehavior(Items.FLINT_AND_STEEL, new FlintAndSteelDispenseItemBehavior());
        DispenserBlock.registerBehavior(Items.BONE_MEAL, new OptionalDispenseItemBehavior() {
            @Override
            protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                this.setSuccess(true);
                Level level = source.level();
                BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                // Paper start - Call BlockDispenseEvent
                ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, target, dispensed, this);
                if (result != null) {
                    this.setSuccess(false);
                    return result;
                }
                // Paper end - Call BlockDispenseEvent
                List<org.bukkit.craftbukkit.block.CraftBlockState> capturedBlockStates; // Paper
                org.bukkit.TreeType treeType; // Paper
                io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
                worldData.captureTreeGeneration = true; // CraftBukkit // Folia - region threading
                try { // Paper
                if (!BoneMealItem.growCrop(dispensed, level, target) && !BoneMealItem.growWaterPlant(dispensed, level, target, null)) {
                    this.setSuccess(false);
                } else if (!level.isClientSide()) {
                    level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, target, 15);
                }
                // Paper start
                } finally {
                    worldData.captureTreeGeneration = false; // Folia - region threading

                    capturedBlockStates = new java.util.ArrayList<>(worldData.capturedBlockStates.values()); // Folia - region threading
                    worldData.capturedBlockStates.clear(); // Folia - region threading

                    treeType = net.minecraft.world.level.block.SaplingBlock.treeTypeRT.get();
                    net.minecraft.world.level.block.SaplingBlock.treeTypeRT.set(null); // Folia - region threading
                }
                // Paper end
                // CraftBukkit start
                if (!capturedBlockStates.isEmpty()) {
                    org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(target, level);
                    org.bukkit.event.world.StructureGrowEvent structureEvent = null;
                    if (treeType != null) {
                        structureEvent = new org.bukkit.event.world.StructureGrowEvent(location, treeType, false, null, (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
                        org.bukkit.Bukkit.getPluginManager().callEvent(structureEvent);
                    }

                    org.bukkit.event.block.BlockFertilizeEvent fertilizeEvent = new org.bukkit.event.block.BlockFertilizeEvent(location.getBlock(), null, (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
                    fertilizeEvent.setCancelled(structureEvent != null && structureEvent.isCancelled());
                    org.bukkit.Bukkit.getPluginManager().callEvent(fertilizeEvent);

                    if (!fertilizeEvent.isCancelled()) {
                        for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                            snapshot.place(snapshot.getFlags());
                            source.level().checkCapturedTreeStateForObserverNotify(target, snapshot); // Paper - notify observers even if grow failed
                        }
                    }
                }
                // CraftBukkit end

                return dispensed;
            }
        });
        DispenserBlock.registerBehavior(Blocks.TNT, new OptionalDispenseItemBehavior() {
            @Override
            protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                ServerLevel level = source.level();
                if (!level.getGameRules().get(GameRules.TNT_EXPLODES)) {
                    this.setSuccess(false);
                    return dispensed;
                }

                BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));

                if (SulfurCubeBlockDispenseItemBehavior.dispenseBlock(level, target, dispensed, source, this)) { // Paper
                    return dispensed;
                }

                // CraftBukkit start
                ItemStack singleItemStack = dispensed.copyWithCount(1); // Paper - shrink at end and single item in event
                org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
                org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(singleItemStack);

                org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), new org.bukkit.util.Vector((double) target.getX() + 0.5D, (double) target.getY(), (double) target.getZ() + 0.5D));
                level.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return dispensed;
                }

                boolean shrink = true;
                if (!event.getItem().equals(craftItem)) {
                    shrink = false;
                    // Chain to handler for new item
                    ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
                    DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
                    if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                        dispenseBehavior.dispense(source, eventStack);
                        return dispensed;
                    }
                }
                // CraftBukkit end

                PrimedTnt tnt = new PrimedTnt(level, event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ(), null); // CraftBukkit
                level.addFreshEntity(tnt);
                level.playSound(null, tnt.getX(), tnt.getY(), tnt.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
                level.gameEvent(null, GameEvent.ENTITY_PLACE, org.bukkit.craftbukkit.util.CraftVector.toBlockPos(event.getVelocity())); // Paper - update game event position
                if (shrink) dispensed.shrink(1); // Paper
                this.setSuccess(true);
                return dispensed;
            }
        });
        DispenserBlock.registerBehavior(
            Items.WITHER_SKELETON_SKULL,
            new OptionalDispenseItemBehavior() {
                @Override
                protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                    Level level = source.level();
                    Direction direction = source.state().getValue(DispenserBlock.FACING);
                    BlockPos target = source.pos().relative(direction);
                    // Paper start - Call BlockDispenseEvent
                    ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, target, dispensed, this);
                    if (result != null) {
                        this.setSuccess(false);
                        return result;
                    }
                    // Paper end - Call BlockDispenseEvent
                    if (level.isEmptyBlock(target) && WitherSkullBlock.canSpawnMob(level, target, dispensed)) {
                        level.setBlock(
                            target,
                            Blocks.WITHER_SKELETON_SKULL.defaultBlockState().setValue(SkullBlock.ROTATION, RotationSegment.convertToSegment(direction)),
                            Block.UPDATE_ALL
                        );
                        level.gameEvent(null, GameEvent.BLOCK_PLACE, target);
                        if (level.getBlockEntity(target) instanceof SkullBlockEntity skullBlockEntity) {
                            WitherSkullBlock.checkSpawn(level, target, skullBlockEntity);
                        }

                        dispensed.shrink(1);
                        this.setSuccess(true);
                    } else {
                        this.setSuccess(EquipmentDispenseItemBehavior.dispenseEquipment(source, dispensed, this)); // Paper - fix possible StackOverflowError
                    }

                    return dispensed;
                }
            }
        );
        DispenserBlock.registerBehavior(Blocks.CARVED_PUMPKIN, new OptionalDispenseItemBehavior() {
            @Override
            protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                Level level = source.level();
                BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                CarvedPumpkinBlock pumpkinBlock = (CarvedPumpkinBlock)Blocks.CARVED_PUMPKIN;
                // Paper start - Call BlockDispenseEvent
                ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, target, dispensed, this);
                if (result != null) {
                    this.setSuccess(false);
                    return result;
                }
                // Paper end - Call BlockDispenseEvent
                if (level.isEmptyBlock(target) && pumpkinBlock.canSpawnGolem(level, target)) {
                    if (!level.isClientSide()) {
                        level.setBlock(target, pumpkinBlock.defaultBlockState(), Block.UPDATE_ALL);
                        level.gameEvent(null, GameEvent.BLOCK_PLACE, target);
                    }

                    dispensed.shrink(1);
                    this.setSuccess(true);
                } else {
                    this.setSuccess(EquipmentDispenseItemBehavior.dispenseEquipment(source, dispensed, this)); // Paper - fix possible StackOverflowError
                }

                return dispensed;
            }
        });
        ShulkerBoxDispenseBehavior shulkerBoxDispenseBehavior = new ShulkerBoxDispenseBehavior();
        DispenserBlock.registerBehavior(Items.SHULKER_BOX, shulkerBoxDispenseBehavior);
        Items.DYED_SHULKER_BOX.forEach(item -> DispenserBlock.registerBehavior(item, shulkerBoxDispenseBehavior));
        DispenserBlock.registerBehavior(
            Items.GLASS_BOTTLE,
            new OptionalDispenseItemBehavior() {
                private ItemStack takeLiquid(final BlockSource source, final ItemStack dispensed, final ItemStack filledItemStack) {
                    source.level().gameEvent(null, GameEvent.FLUID_PICKUP, source.pos());
                    return this.consumeWithRemainder(source, dispensed, filledItemStack);
                }

                @Override
                public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                    this.setSuccess(false);
                    ServerLevel level = source.level();
                    BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                    BlockState state = level.getBlockState(target);
                    // Paper start - Call BlockDispenseEvent
                    ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, target, dispensed, this);
                    if (result != null) {
                        return result;
                    }
                    // Paper end - Call BlockDispenseEvent
                    if (state.is(BlockTags.BEEHIVES, s -> s.hasProperty(BeehiveBlock.HONEY_LEVEL) && s.getBlock() instanceof BeehiveBlock)
                        && state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5) {
                        ((BeehiveBlock)state.getBlock())
                            .releaseBeesAndResetHoneyLevel(level, state, target, null, BeehiveBlockEntity.BeeReleaseStatus.BEE_RELEASED);
                        this.setSuccess(true);
                        return this.takeLiquid(source, dispensed, new ItemStack(Items.HONEY_BOTTLE));
                    } else if (level.getFluidState(target).is(FluidTags.WATER)) {
                        this.setSuccess(true);
                        return this.takeLiquid(source, dispensed, PotionContents.createItemStack(Items.POTION, Potions.WATER));
                    } else {
                        return super.execute(source, dispensed);
                    }
                }
            }
        );
        DispenserBlock.registerBehavior(Items.GLOWSTONE, new OptionalDispenseItemBehavior() {
            @Override
            public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                Direction direction = source.state().getValue(DispenserBlock.FACING);
                BlockPos pos = source.pos().relative(direction);
                Level level = source.level();
                BlockState blockState = level.getBlockState(pos);
                this.setSuccess(true);
                if (blockState.is(Blocks.RESPAWN_ANCHOR)) {
                    if (blockState.getValue(RespawnAnchorBlock.CHARGE) != 4) {
                        // Paper start - Call BlockDispenseEvent
                        ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, pos, dispensed, this);
                        if (result != null) {
                            this.setSuccess(false);
                            return result;
                        }
                        // Paper end - Call BlockDispenseEvent
                        RespawnAnchorBlock.charge(null, level, pos, blockState);
                        dispensed.shrink(1);
                    } else {
                        this.setSuccess(false);
                    }

                    return dispensed;
                } else {
                    return super.execute(source, dispensed);
                }
            }
        });
        DispenserBlock.registerBehavior(Items.SHEARS, new ShearsDispenseItemBehavior());
        DispenserBlock.registerBehavior(Items.BRUSH, new OptionalDispenseItemBehavior() {
            @Override
            protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                ServerLevel level = source.level();
                BlockPos pos = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                List<Armadillo> armadillos = level.getEntitiesOfClass(Armadillo.class, new AABB(pos), EntitySelector.NO_SPECTATORS);
                if (armadillos.isEmpty()) {
                    this.setSuccess(false);
                    return dispensed;
                }

                // CraftBukkit start
                org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
                org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(dispensed); // Paper - ignore stack size on damageable items

                org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseArmorEvent(block, craftItem.clone(), armadillos.get(0).getBukkitLivingEntity());
                level.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    this.setSuccess(false);
                    return dispensed;
                }

                if (!event.getItem().equals(craftItem)) {
                    // Chain to handler for new item
                    ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
                    DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
                    if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                        dispenseBehavior.dispense(source, eventStack);
                        return dispensed;
                    }
                }
                // CraftBukkit end
                for (Armadillo armadillo : armadillos) {
                    if (armadillo.brushOffScute(null, dispensed)) {
                        dispensed.hurtAndBreak(16, level, null, item -> {});
                        return dispensed;
                    }
                }

                this.setSuccess(false);
                return dispensed;
            }
        });
        DispenserBlock.registerBehavior(Items.HONEYCOMB, new OptionalDispenseItemBehavior() {
            @Override
            public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                BlockPos pos = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                Level level = source.level();
                BlockState blockState = level.getBlockState(pos);
                Optional<BlockState> maybeWaxed = HoneycombItem.getWaxed(blockState);
                if (maybeWaxed.isPresent()) {
                    // Paper start - Call BlockDispenseEvent
                    ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, pos, dispensed, this);
                    if (result != null) {
                        this.setSuccess(false);
                        return result;
                    }
                    // Paper end - Call BlockDispenseEvent
                    level.setBlockAndUpdate(pos, maybeWaxed.get());
                    level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_WAX_ON, pos, 0);
                    dispensed.shrink(1);
                    this.setSuccess(true);
                    return dispensed;
                } else {
                    return super.execute(source, dispensed);
                }
            }
        });
        DispenserBlock.registerBehavior(
            Items.POTION,
            new DefaultDispenseItemBehavior() {
                private final DefaultDispenseItemBehavior defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();

                @Override
                public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
                    PotionContents potion = dispensed.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
                    if (!potion.is(Potions.WATER)) {
                        return this.defaultDispenseItemBehavior.dispense(source, dispensed);
                    }

                    ServerLevel level = source.level();
                    BlockPos pos = source.pos();
                    BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
                    if (!level.getBlockState(target).is(BlockTags.CONVERTABLE_TO_MUD)) {
                        return this.defaultDispenseItemBehavior.dispense(source, dispensed);
                    }

                    // Paper start - Call BlockDispenseEvent
                    ItemStack result = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockDispenseEvent(source, target, dispensed, this);
                    if (result != null) {
                        return result;
                    }
                    // Paper end - Call BlockDispenseEvent
                    if (!level.isClientSide()) {
                        RandomSource random = level.getRandom();

                        for (int i = 0; i < 5; i++) {
                            level.sendParticles(
                                ParticleTypes.SPLASH, pos.getX() + random.nextDouble(), pos.getY() + 1, pos.getZ() + random.nextDouble(), 1, 0.0, 0.0, 0.0, 1.0
                            );
                        }
                    }

                    level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
                    level.setBlockAndUpdate(target, Blocks.MUD.defaultBlockState());
                    return this.consumeWithRemainder(source, dispensed, new ItemStack(Items.GLASS_BOTTLE));
                }
            }
        );
        DispenserBlock.registerBehavior(Items.MINECART, new MinecartDispenseItemBehavior(EntityTypes.MINECART));
        DispenserBlock.registerBehavior(Items.CHEST_MINECART, new MinecartDispenseItemBehavior(EntityTypes.CHEST_MINECART));
        DispenserBlock.registerBehavior(Items.FURNACE_MINECART, new MinecartDispenseItemBehavior(EntityTypes.FURNACE_MINECART));
        DispenserBlock.registerBehavior(Items.TNT_MINECART, new MinecartDispenseItemBehavior(EntityTypes.TNT_MINECART));
        DispenserBlock.registerBehavior(Items.HOPPER_MINECART, new MinecartDispenseItemBehavior(EntityTypes.HOPPER_MINECART));
        DispenserBlock.registerBehavior(Items.COMMAND_BLOCK_MINECART, new MinecartDispenseItemBehavior(EntityTypes.COMMAND_BLOCK_MINECART));
    }
}
