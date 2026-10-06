package com.shizuposed.manager.stealth;

public final class StubProbe {

    private StubProbe() {}

    /**
     * The template method. Never invoked.
     */
    public static void probe0() {
        throw new AssertionError("StubProbe.probe0 must never be invoked");
    }

    /**
     * A second template, used to cross-check the first. If the two
     * disagree on the stub shape, something is wrong and the stub
     * generator falls back to the shipped shape.
     */
    public static int probe1() {
        throw new AssertionError("StubProbe.probe1 must never be invoked");
    }
}