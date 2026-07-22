package net.minecraft.world.level.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class SculkSensorBlockEntity extends BlockEntity implements GameEventListener.Provider<VibrationSystem.Listener>, VibrationSystem {
    private static final int DEFAULT_LAST_VIBRATION_FREQUENCY = 0;
    private VibrationSystem.Data vibrationData;
    private final VibrationSystem.Listener vibrationListener;
    private final VibrationSystem.User vibrationUser;
    private int lastVibrationFrequency = 0;
    @Nullable public Integer rangeOverride = null; // Paper - Configurable sculk sensor listener range

    protected SculkSensorBlockEntity(final BlockEntityType<?> type, final BlockPos worldPosition, final BlockState blockState) {
        super(type, worldPosition, blockState);
        this.vibrationUser = this.createVibrationUser();
        this.vibrationData = new VibrationSystem.Data();
        this.vibrationListener = new VibrationSystem.Listener(this);
    }

    public SculkSensorBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        this(BlockEntityTypes.SCULK_SENSOR, worldPosition, blockState);
    }

    public VibrationSystem.User createVibrationUser() {
        return new SculkSensorBlockEntity.VibrationUser(this.getBlockPos());
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.lastVibrationFrequency = input.getIntOr("last_vibration_frequency", 0);
        this.vibrationData = input.read("listener", VibrationSystem.Data.CODEC).orElseGet(VibrationSystem.Data::new);
        this.rangeOverride = input.getInt(PAPER_LISTENER_RANGE_NBT_KEY).orElse(null); // Paper start - Configurable sculk sensor listener range
    }

    protected static final String PAPER_LISTENER_RANGE_NBT_KEY = "Paper.ListenerRange"; // Paper - Configurable sculk sensor listener range
    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("last_vibration_frequency", this.lastVibrationFrequency);
        output.store("listener", VibrationSystem.Data.CODEC, this.vibrationData);
        this.saveRangeOverride(output); // Paper - Configurable sculk sensor listener range
    }
    // Paper start - Configurable sculk sensor listener range
    protected void saveRangeOverride(ValueOutput output) {
        if (this.rangeOverride != null && this.rangeOverride != VibrationUser.LISTENER_RANGE) output.putInt(PAPER_LISTENER_RANGE_NBT_KEY, this.rangeOverride); // only save if it's different from the default
    }
    // Paper end - Configurable sculk sensor listener range

    @Override
    public VibrationSystem.Data getVibrationData() {
        return this.vibrationData;
    }

    @Override
    public VibrationSystem.User getVibrationUser() {
        return this.vibrationUser;
    }

    public int getLastVibrationFrequency() {
        return this.lastVibrationFrequency;
    }

    public void setLastVibrationFrequency(final int lastVibrationFrequency) {
        this.lastVibrationFrequency = lastVibrationFrequency;
    }

    @Override
    public VibrationSystem.Listener getListener() {
        return this.vibrationListener;
    }

    protected class VibrationUser implements VibrationSystem.User {
        public static final int LISTENER_RANGE = 8;
        protected final BlockPos blockPos;
        private final PositionSource positionSource;

        public VibrationUser(final BlockPos blockPos) {
            this.blockPos = blockPos;
            this.positionSource = new BlockPositionSource(blockPos);
        }

        @Override
        public int getListenerRadius() {
            if (SculkSensorBlockEntity.this.rangeOverride != null) return SculkSensorBlockEntity.this.rangeOverride; // Paper - Configurable sculk sensor listener range
            return 8;
        }

        @Override
        public PositionSource getPositionSource() {
            return this.positionSource;
        }

        @Override
        public boolean canTriggerAvoidVibration() {
            return true;
        }

        @Override
        public boolean canReceiveVibration(
            final ServerLevel level, final BlockPos pos, final Holder<GameEvent> event, final GameEvent.@Nullable Context context
        ) {
            return (!pos.equals(this.blockPos) || !event.is(GameEvent.BLOCK_DESTROY) && !event.is(GameEvent.BLOCK_PLACE))
                && VibrationSystem.getGameEventFrequency(event) != 0
                && SculkSensorBlock.canActivate(SculkSensorBlockEntity.this.getBlockState());
        }

        @Override
        public void onReceiveVibration(
            final ServerLevel level,
            final BlockPos pos,
            final Holder<GameEvent> event,
            final @Nullable Entity sourceEntity,
            final @Nullable Entity projectileOwner,
            final float receivingDistance
        ) {
            BlockState state = SculkSensorBlockEntity.this.getBlockState();
            if (SculkSensorBlock.canActivate(state)) {
                int eventFrequency = VibrationSystem.getGameEventFrequency(event);
                SculkSensorBlockEntity.this.setLastVibrationFrequency(eventFrequency);
                int calculatedPower = VibrationSystem.getRedstoneStrengthForDistance(receivingDistance, this.getListenerRadius());
                if (state.getBlock() instanceof SculkSensorBlock sculkSensorBlock) {
                    sculkSensorBlock.activate(sourceEntity, level, this.blockPos, state, calculatedPower, eventFrequency);
                }
            }
        }

        @Override
        public void onDataChanged() {
            SculkSensorBlockEntity.this.setChanged();
        }

        @Override
        public boolean requiresAdjacentChunksToBeTicking() {
            return true;
        }
    }
}
