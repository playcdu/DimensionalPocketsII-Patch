package net.playcdu.dp2patch;

/**
 * Cross-cutting runtime state.
 *
 * <p>Deliberately free of any Dimensional Pockets II reference so that the mixins and the command
 * can consult it without dragging DP2 classes onto the classpath when DP2 is not installed.
 */
public final class PatchState {

    /** True once the pocket-room tick handler has been swapped for ours. */
    public static volatile boolean pocketPatchActive = false;
    /** True if DP2's own handler was successfully taken off the event bus. */
    public static volatile boolean dp2HandlerUnregistered = false;
    /** True once the ServerLevel mixin has actually run, i.e. it applied. */
    public static boolean idleMixinApplied = false;
    /** True once the DP2 fallback mixin has actually run. */
    public static boolean dp2MixinApplied = false;

    /** Level ticks skipped by the idle-dimension throttle. */
    public static long idleTicksSkipped = 0L;
    /** Force-load state changes made on pocket rooms. */
    public static long forceLoadTransitions = 0L;

    private PatchState() {
    }
}
