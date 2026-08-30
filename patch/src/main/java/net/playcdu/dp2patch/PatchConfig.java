package net.playcdu.dp2patch;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Configuration for the patch.
 *
 * <p>Every value is mirrored into a plain static field when the config loads. The tick hooks read
 * those fields thousands of times per second, and going through {@link ForgeConfigSpec.ConfigValue}
 * on a hot path costs more than the work we are trying to save.
 */
public final class PatchConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue FIX_ROOM_TICKING;
    private static final ForgeConfigSpec.BooleanValue UNLOAD_IDLE_POCKETS;
    private static final ForgeConfigSpec.IntValue IDLE_GRACE_SECONDS;
    private static final ForgeConfigSpec.IntValue IDLE_LEVEL_TICK_INTERVAL;
    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;

    /** Replace Dimensional Pockets II's level-tick handler with the non-blocking version. */
    public static boolean fixRoomTicking = true;
    /** Release force-loaded pocket rooms once nobody has been inside for {@link #idleGraceTicks}. */
    public static boolean unloadIdlePockets = true;
    /** Grace period before an unoccupied pocket room is released, in ticks. */
    public static int idleGraceTicks = 300 * 20;
    /** Idle levels are ticked 1 tick in N. 1 disables the throttle entirely. */
    public static int idleLevelTickInterval = 4;
    public static boolean debugLogging = false;

    /**
     * Invoked after every (re)load. Set by the Dimensional Pockets II side of the patch so it can
     * re-sync the event bus; kept as a plain hook so this class stays free of DP2 references.
     */
    public static volatile Runnable changeListener = null;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Dimensional Pockets II pocket-room ticking.").push("pockets");

        FIX_ROOM_TICKING = b
                .comment(
                        "Replace Dimensional Pockets II's per-level tick handler.",
                        "The stock handler ticks every registered pocket room in EVERY loaded dimension",
                        "(it is missing a dimension check) and fetches each chunk with a blocking call,",
                        "which parks the server thread while the chunk is generated or read from disk.",
                        "The replacement only runs in the pocket dimension, never blocks, and looks pockets",
                        "up through an index instead of scanning the whole registry per room.",
                        "This is a pure bug fix - it does not change gameplay. Leave it on.")
                .define("fixRoomTicking", true);

        UNLOAD_IDLE_POCKETS = b
                .comment(
                        "Release the force-load on a pocket room once no player has been inside it for",
                        "'idleGraceSeconds'. The room's chunks then unload normally and stop ticking their",
                        "block entities; they are force-loaded again the moment a player returns.",
                        "This overrides Dimensional Pockets II's own 'keepChunksLoaded' option for rooms",
                        "nobody is using. Turn it off if machines must keep running inside unattended pockets.")
                .define("unloadIdlePockets", true);

        IDLE_GRACE_SECONDS = b
                .comment("Seconds a pocket room stays loaded after the last player leaves it.")
                .defineInRange("idleGraceSeconds", 300, 5, 86400);

        b.pop();

        b.comment("Idle dimension throttling.").push("dimensions");

        IDLE_LEVEL_TICK_INTERVAL = b
                .comment(
                        "Tick a dimension only 1 tick in N while it has no players, no loaded chunks and",
                        "no force-loaded chunks. Such a dimension has nothing to simulate, but it still",
                        "pays the fixed per-tick cost of a level tick - and a pack that keeps hundreds of",
                        "dimensions alive pays it hundreds of times per tick.",
                        "The in-game time of a throttled dimension is still advanced every tick, so day/night",
                        "and scheduled events do not drift.",
                        "Set to 1 to disable. Higher values save more; 4 is a safe default.")
                .defineInRange("idleLevelTickInterval", 4, 1, 200);

        b.pop();

        DEBUG_LOGGING = b
                .comment("Log every pocket room force-load/release transition.")
                .define("debugLogging", false);

        SPEC = b.build();
    }

    private PatchConfig() {
    }

    public static void onLoad(final ModConfigEvent.Loading event) {
        bake();
    }

    public static void onReload(final ModConfigEvent.Reloading event) {
        bake();
    }

    private static void bake() {
        fixRoomTicking = FIX_ROOM_TICKING.get();
        unloadIdlePockets = UNLOAD_IDLE_POCKETS.get();
        idleGraceTicks = IDLE_GRACE_SECONDS.get() * 20;
        idleLevelTickInterval = IDLE_LEVEL_TICK_INTERVAL.get();
        debugLogging = DEBUG_LOGGING.get();

        final Runnable listener = changeListener;
        if (listener != null) {
            listener.run();
        }
    }
}
