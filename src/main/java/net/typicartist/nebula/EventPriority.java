package net.typicartist.nebula;

public enum EventPriority {
    HIGHEST(100),
    HIGH(75),
    NORMAL(50),
    LOW(25),
    LOWEST(0);

    private final int value;

    EventPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }
}