package net.playcdu.dp2patch;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;

import org.slf4j.Logger;

/**
 * Server-side performance patch for Dimensional Pockets II.
 *
 * <p>The mod registers nothing: no blocks, no items, no network channels. It only replaces two hot
 * paths, so a client that does not have it installed can still join a server that does.
 */
@Mod(DP2Patch.MOD_ID)
public final class DP2Patch {

    public static final String MOD_ID = "dp2patch";
    public static final String DP2_MOD_ID = "dimensionalpocketsii";

    public static final Logger LOGGER = LogUtils.getLogger();

    public DP2Patch() {
        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PatchConfig.SPEC, "dp2patch-common.toml");
        modBus.addListener(PatchConfig::onLoad);
        modBus.addListener(PatchConfig::onReload);
        modBus.addListener(this::onCommonSetup);

        // Server-side only. Tell Forge never to hold this mod against a connecting client: the
        // clients on this network run an unmodifiable pack and will never have it installed.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, isFromServer) -> true));

        MinecraftForge.EVENT_BUS.register(PatchCommand.class);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (!ModList.get().isLoaded(DP2_MOD_ID)) {
                LOGGER.info("[dp2patch] Dimensional Pockets II is not installed; pocket patches stay off. "
                        + "Idle-dimension throttling is unaffected and still active.");
                return;
            }
            try {
                // Loads net.playcdu.dp2patch.dp2.PocketPatch, and with it the DP2 classes it
                // references. Reaching this line means DP2 is present, so that resolution is safe.
                net.playcdu.dp2patch.dp2.PocketPatch.install();
            } catch (Throwable t) {
                // A performance patch must never be the reason a server fails to boot.
                LOGGER.error("[dp2patch] Failed to install the pocket-room patch; Dimensional Pockets II "
                        + "keeps its own behaviour. Idle-dimension throttling is unaffected.", t);
            }
        });
    }
}
