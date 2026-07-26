package meow.bacteriawa.lightingluminol.core;

public class RateThrottler {
    private int count = 0;
    private boolean active = false;

    public void begin() {
        this.count = 0;
        this.active = true;
    }

    public void done() {
        this.active = false;
    }

    public void increase() {
        if (this.active) {
            this.count++;
        }
    }

    public boolean isOutOfRate(int maxRate) {
        return this.active && this.count >= maxRate;
    }

    public void mergeWith(RateThrottler other) {
        this.count += other.count;
    }

    public void splitInto(RateThrottler other) {
        // Simple split: give half to the other
        other.count = this.count / 2;
        this.count -= other.count;
    }

    public void destroy() {
        this.active = false;
        this.count = 0;
    }
}
