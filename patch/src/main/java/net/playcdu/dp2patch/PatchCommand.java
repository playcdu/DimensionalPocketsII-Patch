package net.playcdu.dp2patch;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

/**
 * {@code /dp2patch} - status readout plus manual overrides for the pocket force-load state.
 *
 * <p>Holds no reference to any Dimensional Pockets II type; the DP2-only branches go through
 * {@link net.playcdu.dp2patch.dp2.PocketPatch} and are reached only once the mod is known present.
 */
public final class PatchCommand {

    private PatchCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dp2patch")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(PatchCommand::status))
                .then(Commands.literal("release").executes(PatchCommand::release))
                .then(Commands.literal("restore").executes(PatchCommand::restore));

        event.getDispatcher().register(root);
    }

    private static boolean dp2Available() {
        return PatchState.pocketPatchActive && ModList.get().isLoaded(DP2Patch.DP2_MOD_ID);
    }

    private static int status(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final MinecraftServer server = source.getServer();

        int levels = 0;
        int idleLevels = 0;
        int loadedChunks = 0;
        for (final ServerLevel level : server.getAllLevels()) {
            levels++;
            final int loaded = level.getChunkSource().getLoadedChunksCount();
            loadedChunks += loaded;
            if (loaded == 0 && level.players().isEmpty()) {
                idleLevels++;
            }
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("Dimensional Pockets II Patch\n");
        sb.append("  dimensions: ").append(levels).append(" loaded, ").append(idleLevels)
                .append(" idle, ").append(loadedChunks).append(" chunks total\n");
        sb.append("  idle throttle: ")
                .append(PatchConfig.idleLevelTickInterval <= 1 ? "off"
                        : "1 tick in " + PatchConfig.idleLevelTickInterval)
                .append(PatchState.idleMixinApplied ? " (active)" : " (MIXIN NOT APPLIED)")
                .append(", ").append(PatchState.idleTicksSkipped).append(" level ticks skipped\n");

        if (!ModList.get().isLoaded(DP2Patch.DP2_MOD_ID)) {
            sb.append("  pockets: Dimensional Pockets II not installed");
        } else if (!PatchState.pocketPatchActive) {
            sb.append("  pockets: patch disabled in config (fixRoomTicking = false)");
        } else {
            sb.append("  pockets: ").append(net.playcdu.dp2patch.dp2.PocketPatch.trackedRooms())
                    .append(" rooms tracked, ").append(net.playcdu.dp2patch.dp2.PocketPatch.forcedRooms())
                    .append(" force-loaded, ").append(PatchState.forceLoadTransitions)
                    .append(" transitions\n");
            sb.append("  idle unload: ")
                    .append(PatchConfig.unloadIdlePockets
                            ? "on, after " + (PatchConfig.idleGraceTicks / 20) + "s"
                            : "off")
                    .append("\n  stock handler removed: ").append(PatchState.dp2HandlerUnregistered)
                    .append(PatchState.dp2MixinApplied ? " (mixin fallback present)" : "");
        }

        final String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int release(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        if (!dp2Available()) {
            ctx.getSource().sendFailure(Component.literal("Dimensional Pockets II patch is not active."));
            return 0;
        }
        final int changed = net.playcdu.dp2patch.dp2.PocketPatch.releaseAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Released the force-load on " + changed + " pocket chunk(s)."), true);
        return changed;
    }

    private static int restore(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        if (!dp2Available()) {
            ctx.getSource().sendFailure(Component.literal("Dimensional Pockets II patch is not active."));
            return 0;
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("Force-loading every registered pocket room; this blocks the server "
                        + "until the chunks are in memory."), true);
        final int changed = net.playcdu.dp2patch.dp2.PocketPatch.restoreAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("Force-loaded " + changed + " pocket chunk(s)."), true);
        return changed;
    }
}
