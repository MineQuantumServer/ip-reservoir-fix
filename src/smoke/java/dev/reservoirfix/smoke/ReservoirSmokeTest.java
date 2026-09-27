package dev.reservoirfix.smoke;

import dev.reservoirfix.ScopedContext;
import flaxbeard.immersivepetroleum.api.reservoir.ReservoirHandler;
import flaxbeard.immersivepetroleum.common.datastorage.reservoir.ReservoirRegionDataStorage;
import flaxbeard.immersivepetroleum.common.world.FeatureReservoir;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Mod("ip_reservoir_fix_smoke")
@GameTestHolder("ip_reservoir_fix_smoke")
@PrefixGameTestTemplate(false)
public final class ReservoirSmokeTest {
    @GameTest(template = "empty", batch = "worldgen", timeoutTicks = 1200)
    public static void generatesFreshNetherChunks(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Nether is unavailable");
        ReservoirHandler.initGenerator(nether);
        // Keep this area separate from the synthetic overworld test; IP's
        // existsAt check itself is shared across dimensions in this release.
        ColumnPos candidate = findUnusedCandidate(nether, helper.absolutePos(BlockPos.ZERO).getX() + 20000);
        ChunkPos center = new ChunkPos(new BlockPos(candidate.x(), 64, candidate.z()));
        var requested = new ArrayList<ChunkPos>();
        var futures = new ArrayList<CompletableFuture<?>>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                var chunk = new ChunkPos(center.x + dx, center.z + dz);
                requested.add(chunk);
                // UNKNOWN tickets from getChunkFuture expire while an asynchronous
                // request is still generating; hold explicit tickets for this test.
                nether.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 0, chunk);
                futures.add(CompletableFuture.supplyAsync(() ->
                    nether.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true))
                    .thenCompose(future -> future).thenApply(result -> {
                        if (result.orElse(null) == null) throw new AssertionError("Chunk generation failed: " + chunk + ": " + result.getError());
                        return result;
                    }));
            }
        }
        var finished = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        // GameTestServer ticks without real-time pacing; a tick-based timeout can
        // elapse in milliseconds while generation is still legitimately working.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
        helper.getLevel().getServer().managedBlock(() -> finished.isDone() || System.nanoTime() >= deadline);
        try {
            helper.assertTrue(finished.isDone(), "Nether generation exceeded 45 seconds");
            finished.join();
            synchronized (FeatureReservoir.generatedReservoirChunks) {
                for (var chunk : requested) {
                    helper.assertTrue(FeatureReservoir.generatedReservoirChunks.containsEntry(Level.NETHER, chunk),
                        "Reservoir feature did not run in " + chunk);
                }
            }
            // The upstream scanner may stop when it reaches a previously
            // generated polygon; it does not promise every noise island exists.
            boolean foundReservoir = false;
            for (var chunk : requested) {
                for (int z = 0; z < 16 && !foundReservoir; z++) {
                    for (int x = 0; x < 16 && !foundReservoir; x++) {
                        foundReservoir = ReservoirHandler.getReservoirNoCache(nether,
                            new ColumnPos(chunk.getMinBlockX() + x, chunk.getMinBlockZ() + z)) != null;
                    }
                }
            }
            helper.assertTrue(foundReservoir, "Real Nether worldgen did not store any reservoir in the requested area");
            System.out.println("[IP Reservoir Fix Smoke] PASS: 9 fresh FULL Nether chunks, real WorldGenRegion, reservoir stored");
            helper.succeed();
        } finally {
            for (var chunk : requested) nether.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 0, chunk);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void transformedFeatureUsesContextAndRecoversAfterFailure(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ReservoirHandler.initGenerator(level);
        ColumnPos candidate = findUnusedCandidate(level, helper.absolutePos(BlockPos.ZERO).getX() + 10000);
        ChunkPos chunk = new ChunkPos(new BlockPos(candidate.x(), 64, candidate.z()));
        // An unregistered in-memory chunk: no real chunk request is needed by the scanner.
        LevelChunk memoryChunk = new LevelChunk(level, chunk);
        var biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        var failLookup = new AtomicBoolean(true);
        var biomeReads = new AtomicInteger();
        var sentinel = new IllegalStateException("intentional biome lookup failure");
        WorldGenLevel region = (WorldGenLevel) Proxy.newProxyInstance(
            WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getLevel" -> level;
                    case "getSeed" -> level.getSeed();
                    case "getChunk" -> memoryChunk;
                    case "getBiome" -> {
                        helper.assertTrue(Thread.holdsLock(FeatureReservoir.generatedReservoirChunks), "Upstream lock was removed");
                        biomeReads.incrementAndGet();
                        if (failLookup.get()) throw sentinel;
                        yield biome;
                    }
                    default -> throw new AssertionError("Unexpected worldgen operation: " + method);
                };
            });
        var feature = new FeatureReservoir();
        long reservoirsBefore = countReservoirs(level);
        var placement = new FeaturePlaceContext<>(Optional.empty(), region, level.getChunkSource().getGenerator(),
            RandomSource.create(12345), chunk.getWorldPosition(), NoneFeatureConfiguration.INSTANCE);
        try {
            feature.place(placement);
            throw new AssertionError("Expected redirected biome lookup to throw");
        } catch (IllegalStateException failure) {
            helper.assertTrue(failure == sentinel, "Wrong exception from feature");
        }
        helper.assertTrue(biomeReads.get() == 1, "Biome lookup was not routed through the generation context");
        helper.assertTrue(!FeatureReservoir.generatedReservoirChunks.containsEntry(level.dimension(), chunk), "Failed scan marked chunk complete");
        helper.assertTrue(countReservoirs(level) == reservoirsBefore, "Failed biome lookup mutated reservoir storage");
        assertNoContextLeak(helper);

        failLookup.set(false);
        helper.assertTrue(feature.place(placement), "Retry did not generate");
        helper.assertTrue(biomeReads.get() > 1, "Retry skipped biome lookup");
        // A positive noise column can lie on a culled polygon edge, so test the
        // storage mutation itself rather than containment of the starting point.
        helper.assertTrue(countReservoirs(level) > reservoirsBefore, "Reservoir was not actually stored");
        helper.assertTrue(FeatureReservoir.generatedReservoirChunks.containsEntry(level.dimension(), chunk), "Successful scan not marked complete");
        assertNoContextLeak(helper);

        int readsAfterGeneration = biomeReads.get();
        helper.assertTrue(!feature.place(placement), "Duplicate placement should return false");
        helper.assertTrue(biomeReads.get() == readsAfterGeneration, "Duplicate placement repeated scanning");
        assertNoContextLeak(helper);
        System.out.println("[IP Reservoir Fix Smoke] PASS: redirected biome lookup, retained monitor, exception cleanup, retry, reservoir storage, duplicate suppression");
        helper.succeed();
    }

    private static ColumnPos findUnusedCandidate(ServerLevel level, int start) {
        var storage = ReservoirRegionDataStorage.get();
        int firstChunk = start >> 4;
        for (int cz = firstChunk; cz < firstChunk + 16; cz++) {
            for (int cx = firstChunk; cx < firstChunk + 16; cx++) {
                ColumnPos candidate = null;
                boolean occupied = false;
                for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                    for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                        var column = new ColumnPos(x, z);
                        occupied |= storage.existsAt(column);
                        if (candidate == null && ReservoirHandler.getValueOf(level, x, z) > -1) candidate = column;
                    }
                }
                // An earlier occupied column makes the original scanner return
                // before reaching later candidates, even if those are unused.
                if (!occupied && candidate != null) return candidate;
            }
        }
        throw new AssertionError("No reservoir candidate found for smoke test seed");
    }

    private static void assertNoContextLeak(GameTestHelper helper) throws Exception {
        for (var field : FeatureReservoir.class.getDeclaredFields()) {
            if (field.getType() == ScopedContext.class) {
                field.setAccessible(true);
                helper.assertTrue(((ScopedContext<?>) field.get(null)).current() == null, "Generation context leaked");
                return;
            }
        }
        throw new AssertionError("Mixin context field is missing");
    }

    private static long countReservoirs(ServerLevel level) throws Exception {
        var regionsField = ReservoirRegionDataStorage.class.getDeclaredField("regions");
        regionsField.setAccessible(true);
        var regions = (java.util.Map<?, ?>) regionsField.get(ReservoirRegionDataStorage.get());
        long count = 0;
        synchronized (regions) {
            for (var region : regions.values()) {
                var listField = region.getClass().getDeclaredField("reservoirlist");
                listField.setAccessible(true);
                var list = (com.google.common.collect.Multimap<?, ?>) listField.get(region);
                synchronized (list) {
                    count += list.entries().stream().filter(entry -> level.dimension().equals(entry.getKey())).count();
                }
            }
        }
        return count;
    }
}
