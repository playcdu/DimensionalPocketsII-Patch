package net.playcdu.dp2patch.mixin;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes {@code ServerLevel#tickTime}, which is private, so a throttled level's clock still runs. */
@Mixin(ServerLevel.class)
public interface ServerLevelAccess {

    @Invoker("tickTime")
    void dp2patch$tickTime();
}
