package net.minecraft.commands.execution;

public record ChainModifiers(byte flags) {
    public static final ChainModifiers DEFAULT = new ChainModifiers((byte)0);
    private static final byte FLAG_FORKED = 1;
    private static final byte FLAG_IS_RETURN = 2;

    private ChainModifiers setFlag(final byte flag) {
        int newFlags = this.flags | flag;
        return newFlags != this.flags ? new ChainModifiers((byte)newFlags) : this;
    }

    public boolean isForked() {
        return (this.flags & FLAG_FORKED) != 0;
    }

    public ChainModifiers setForked() {
        return this.setFlag(FLAG_FORKED);
    }

    public boolean isReturn() {
        return (this.flags & FLAG_IS_RETURN) != 0;
    }

    public ChainModifiers setReturn() {
        return this.setFlag(FLAG_IS_RETURN);
    }
}
