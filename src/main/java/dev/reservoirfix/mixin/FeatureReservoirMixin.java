package dev.reservoirfix.mixin;

import dev.reservoirfix.ScopedContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Targets the exact named bytecode in the 1.21.1 / 4.5.0-39 release. */
@Mixin(targets = "flaxbeard.immersivepetroleum.common.world.FeatureReservoir", remap = false)
public abstract class FeatureReservoirMixin {
    @Unique
    private static final ScopedContext<WorldGenLevel> ipfix$generation = new ScopedContext<>();

    @Shadow
    public static void scanChunkForNewReservoirs(ServerLevel world, ChunkPos chunk, RandomSource random) {
        throw new AssertionError("Mixin shadow was not transformed");
    }

    @Redirect(
        method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
        at = @At(value = "INVOKE", target = "Lflaxbeard/immersivepetroleum/common/world/FeatureReservoir;scanChunkForNewReservoirs(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/util/RandomSource;)V"),
        require = 1, expect = 1, allow = 1
    )
    private void ipfix$scanInGenerationContext(ServerLevel world, ChunkPos chunk, RandomSource random,
            FeaturePlaceContext<NoneFeatureConfiguration> context) {
        // Keep the upstream monitor, duplicate checks, random stream and storage writes.
        ipfix$generation.run(context.level(), () -> scanChunkForNewReservoirs(world, chunk, random));
    }

    @Redirect(
        method = "scanChunkForNewReservoirs(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/util/RandomSource;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
        require = 1, expect = 1, allow = 1
    )
    private static Holder<Biome> ipfix$biomeFromGenerationRegion(ServerLevel world, BlockPos position) {
        WorldGenLevel generation = ipfix$generation.current();
        // Calls to the public scan API outside Feature.place keep their original semantics.
        // Do not catch lookup failures and fall back to ServerLevel during worldgen:
        // that would silently reintroduce the blocking call this patch eliminates.
        return generation == null ? world.getBiome(position) : generation.getBiome(position);
    }
}
