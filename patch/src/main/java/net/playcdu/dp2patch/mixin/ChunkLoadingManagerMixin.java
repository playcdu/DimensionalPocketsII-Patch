package net.playcdu.dp2patch.mixin;

import com.tcn.dimensionalpocketsii.pocket.core.registry.ChunkLoadingManager;

import net.minecraftforge.event.TickEvent;

import net.playcdu.dp2patch.PatchState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fallback that disables Dimensional Pockets II's own level-tick handler.
 *
 * <p>The primary mechanism is taking the handler off the Forge event bus, which costs nothing at
 * runtime. This is the belt to that pair of braces: if the unregister ever stops working - a Forge
 * change, a DP2 change - the stock handler still returns immediately instead of running alongside
 * ours and reintroducing the stalls.
 *
 * <p>{@code remap = false} throughout: these are DP2's own names, which Forge does not obfuscate.
 */
@Mixin(value = ChunkLoadingManager.class, remap = false)
public class ChunkLoadingManagerMixin {

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dp2patch$disableStockRoomTicking(final TickEvent.LevelTickEvent event,
            final CallbackInfo ci) {
        if (!PatchState.dp2MixinApplied) {
            PatchState.dp2MixinApplied = true;
        }
        if (PatchState.pocketPatchActive) {
            ci.cancel();
        }
    }
}
