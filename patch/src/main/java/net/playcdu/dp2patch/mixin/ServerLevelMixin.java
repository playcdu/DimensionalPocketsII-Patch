package net.playcdu.dp2patch.mixin;

import java.util.function.BooleanSupplier;

import net.minecraft.server.level.ServerLevel;

import net.playcdu.dp2patch.PatchState;
import net.playcdu.dp2patch.idle.IdleLevels;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void dp2patch$throttleIdleLevel(final BooleanSupplier haveTime, final CallbackInfo ci) {
        if (!PatchState.idleMixinApplied) {
            PatchState.idleMixinApplied = true;
        }

        final ServerLevel self = (ServerLevel) (Object) this;
        if (!IdleLevels.shouldThrottle(self)) {
            return;
        }

        // Keep the clock and any scheduled events moving; only the simulation is skipped.
        ((ServerLevelAccess) this).dp2patch$tickTime();
        PatchState.idleTicksSkipped++;
        ci.cancel();
    }
}
