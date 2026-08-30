package net.playcdu.dp2patch.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Keeps the Dimensional Pockets II mixin from being offered a target that is not there.
 *
 * <p>Presence is decided from the mod list rather than by trying to load a DP2 class: mixin configs
 * are read very early, and forcing a class to load before the transformers are in place would rob
 * every other mod of the chance to mix into it.
 */
public class PatchMixinPlugin implements IMixinConfigPlugin {

    private static final String DP2_MOD_ID = "dimensionalpocketsii";

    private boolean dp2Present = false;

    @Override
    public void onLoad(final String mixinPackage) {
        try {
            dp2Present = LoadingModList.get().getModFileById(DP2_MOD_ID) != null;
        } catch (Throwable t) {
            // Cannot tell, so assume absent. The event-bus unregister is the primary mechanism and
            // does not depend on this mixin at all.
            dp2Present = false;
        }
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.endsWith("ChunkLoadingManagerMixin")) {
            return dp2Present;
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
            final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
            final IMixinInfo mixinInfo) {
    }
}
