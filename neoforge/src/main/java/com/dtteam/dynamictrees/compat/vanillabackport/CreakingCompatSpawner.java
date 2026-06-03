package com.dtteam.dynamictrees.compat.vanillabackport;

import com.dtteam.dynamictrees.block.branch.CreakingHeartBranchBlock;
import com.dtteam.dynamictrees.block.branch.ResinBranchBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Compatibility spawner for VanillaBackport Creakings from Dynamic Trees
 * creaking heart branches.
 */
public final class CreakingCompatSpawner {

    private static final ResourceLocation[] CREAKING_ENTITY_IDS = new ResourceLocation[]{
        ResourceLocation.parse("minecraft:creaking"),
        ResourceLocation.parse("vanillabackport:creaking")
    };
    private static final ResourceLocation[] CREAKING_HEART_BLOCK_IDS = new ResourceLocation[]{
        ResourceLocation.parse("minecraft:creaking_heart"),
        ResourceLocation.parse("vanillabackport:creaking_heart")
    };
    private static final int PLAYER_SCAN_RADIUS_H = 32;
    private static final int PLAYER_SCAN_RADIUS_V = 24;
    private static final int HEART_TRIGGER_PLAYER_RADIUS = 32;
    private static final int HEART_MAX_EXISTING_CREAKING_RADIUS = 32;
    private static final int SPAWN_ATTEMPTS = 8;
    private static final int MAX_SPAWNS_PER_TICK = 2;
    private static final int TICK_INTERVAL = 20;

    private static final int RESIN_CLUMP_BASE_COUNT = 2;
    private static final int RESIN_SEARCH_RADIUS = 2;
    private static final long RESIN_GENERATION_COOLDOWN_TICKS = 100L;
    private static final Map<GlobalPos, Long> resinCooldownByHeart = new HashMap<>();

    private static final String CREAKING_CLASS = "com.blackgear.vanillabackport.common.level.entities.creaking.Creaking";
    private static final String CREAKING_HEART_BE_CLASS = "com.blackgear.vanillabackport.common.level.blockentities.CreakingHeartBlockEntity";

    private static volatile boolean reflectionResolved = false;
    private static Class<?> vbCreakingClass;
    private static Class<?> vbHeartBeClass;
    private static Constructor<?> vbHeartBeCtor;
    private static Method vbSetTransient;
    private static Method vbGetHomePos;
    private static Method vbPlayerStuck;
    private static Method vbSetCreakingInfo;
    private static Method vbRemoveProtector;
    private static Method vbTearDown;
    private static Method beSetLevel;
    private static Method beClearRemoved;
    private static Method beSetBlockState;
    // Optional — emitter tick (orange+gray particle fidelity); null if unavailable
    private static Field vbHeartEmitter;
    private static Method vbHeartEmitParticles;
    private static EntityType<?> cachedCreakingType;
    private static net.minecraft.world.level.block.Block cachedProxyHeartBlock;
    private static BlockState cachedProxyHeartAwakeState;
    private static BlockState cachedProxyHeartDormantState;
    private static BlockState cachedProxyHeartUprootedState;
    private CreakingCompatSpawner() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % TICK_INTERVAL != 0) {
            return;
        }

        pruneResinCooldowns(level);

        if (!level.dimensionType().natural()) {
            return;
        }
        final boolean reflectionOk = resolveReflection();

        if (reflectionOk) {
            cleanupOrphanedCreakings(level);
        }

        final boolean naturalNight = isNaturalNight(level);
        final boolean spawnAllowed = naturalNight
                && level.getDifficulty() != Difficulty.PEACEFUL
                && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);

        final EntityType<?> creakingType = spawnAllowed && reflectionOk ? resolveCreakingType() : null;
        if (spawnAllowed && creakingType == null) {
            return;
        }

        final Set<BlockPos> testedHearts = new HashSet<>();
        final RandomSource random = level.getRandom();
        int spawned = 0;

        for (Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }

            final BlockPos center = player.blockPosition();
            for (int dx = -PLAYER_SCAN_RADIUS_H; dx <= PLAYER_SCAN_RADIUS_H; dx++) {
                for (int dz = -PLAYER_SCAN_RADIUS_H; dz <= PLAYER_SCAN_RADIUS_H; dz++) {
                    for (int dy = -PLAYER_SCAN_RADIUS_V; dy <= PLAYER_SCAN_RADIUS_V; dy++) {
                        final BlockPos heartPos = center.offset(dx, dy, dz);
                        if (!testedHearts.add(heartPos)) {
                            continue;
                        }

                        final BlockState state = level.getBlockState(heartPos);
                        if (!(state.getBlock() instanceof CreakingHeartBranchBlock)) {
                            continue;
                        }
                        final boolean hasSupports = hasActivationSupports(level, heartPos);

                        Entity linked = reflectionOk ? findLinkedCreaking(level, heartPos) : null;
                        syncHeartVisualState(level, heartPos, state, hasSupports, linked != null, naturalNight);

                        BlockEntity proxyHeart = null;
                        if (reflectionOk && (hasSupports || linked != null)) {
                            final String proxyStateName = desiredProxyStateName(hasSupports, linked != null, naturalNight);
                            final BlockState proxyHeartState = getProxyHeartState(proxyStateName);
                            if (proxyHeartState != null) {
                                proxyHeart = getOrCreateProxyHeart(level, heartPos, proxyHeartState);
                            }
                            if (proxyHeart != null) {
                                syncProxyHeartState(proxyHeart, proxyStateName);
                            }
                        }

                        if (linked != null) {
                            if (proxyHeart != null) {
                                // This is the 1.21.1 backport hook that keeps the linked heart and the live creaking in sync.
                                // Keep proxy heart state synced even for pre-existing linked protectors.
                                bindCreaking(proxyHeart, linked, heartPos);
                                // Drive the emitter so orange+gray particles appear and subsequent hits
                                // can re-trigger creakingHurt() (its guard is emitter <= 0).
                                tickProxyHeartEmitter(level, proxyHeart, heartPos, state);
                                if (shouldRemoveProtector(level, heartPos, linked)) {
                                    removeProtector(proxyHeart);
                                }
                            }
                            continue;
                        }

                        if (!hasSupports) {
                            continue;
                        }
                        if (!reflectionOk || !spawnAllowed || proxyHeart == null) {
                            continue;
                        }

                        if (!hasNearbyPlayer(level, heartPos, HEART_TRIGGER_PLAYER_RADIUS)) {
                            continue;
                        }
                        if (hasNearbyCreaking(level, creakingType, heartPos, HEART_MAX_EXISTING_CREAKING_RADIUS)) {
                            continue;
                        }

                        final Entity spawnedCreaking = trySpawnNearHeart(level, creakingType, heartPos, random);
                        if (spawnedCreaking == null) {
                            continue;
                        }
                        bindCreaking(proxyHeart, spawnedCreaking, heartPos);
                        spawned++;
                        if (spawned >= MAX_SPAWNS_PER_TICK) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static boolean hasActivationSupports(ServerLevel level, BlockPos heartPos) {
        return hasOppositeBranches(level, heartPos, Direction.UP, Direction.DOWN)
                || hasOppositeBranches(level, heartPos, Direction.NORTH, Direction.SOUTH)
                || hasOppositeBranches(level, heartPos, Direction.EAST, Direction.WEST);
    }

    private static boolean hasOppositeBranches(ServerLevel level, BlockPos pos, Direction a, Direction b) {
        return TreeHelper.isBranch(level.getBlockState(pos.relative(a)))
                && TreeHelper.isBranch(level.getBlockState(pos.relative(b)));
    }

    private static boolean hasNearbyPlayer(ServerLevel level, BlockPos pos, int radius) {
        return level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, radius, false) != null;
    }

    private static boolean hasNearbyCreaking(ServerLevel level, EntityType<?> creakingType, BlockPos pos, int radius) {
        return !level.getEntities((Entity) null,
                        new AABB(pos).inflate(radius, radius, radius),
                        entity -> entity.getType() == creakingType)
                .isEmpty();
    }

    private static Entity trySpawnNearHeart(ServerLevel level, EntityType<?> creakingType, BlockPos heartPos, RandomSource random) {
        for (int i = 0; i < SPAWN_ATTEMPTS; i++) {
            final int x = heartPos.getX() + random.nextInt(33) - 16;
            final int z = heartPos.getZ() + random.nextInt(33) - 16;
            final int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            final BlockPos spawnPos = new BlockPos(x, y, z);
            if (Math.abs(spawnPos.getY() - heartPos.getY()) > 8) {
                continue;
            }
            final Entity entity = creakingType.spawn(level, spawnPos, MobSpawnType.SPAWNER);
            if (entity != null && vbCreakingClass.isInstance(entity)) {
                return entity;
            }
        }
        return null;
    }

    private static Entity findLinkedCreaking(ServerLevel level, BlockPos heartPos) {
        final AABB box = new AABB(heartPos).inflate(HEART_MAX_EXISTING_CREAKING_RADIUS);
        for (Entity entity : level.getEntities((Entity) null, box, e -> vbCreakingClass.isInstance(e))) {
            try {
                final Object homePos = vbGetHomePos.invoke(entity);
                if (heartPos.equals(homePos)) {
                    return entity;
                }
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean shouldRemoveProtector(ServerLevel level, BlockPos heartPos, Entity creaking) {
        final boolean persistent = creaking instanceof Mob mob && mob.isPersistenceRequired();
        if (!isNaturalNight(level) && !persistent) {
            return true;
        }
        if (creaking.distanceToSqr(heartPos.getX() + 0.5, heartPos.getY() + 0.5, heartPos.getZ() + 0.5) > (34.0 * 34.0)) {
            return true;
        }
        try {
            return (boolean) vbPlayerStuck.invoke(creaking);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static void bindCreaking(BlockEntity proxyHeart, Entity creaking, BlockPos heartPos) {
        try {
            // Vanilla Backport stores the heart link on its own creaking entity; this reflection bridge keeps
            // the 26.1.2 behavior alive on 1.21.1 without requiring direct compile-time access to those classes.
            vbSetTransient.invoke(creaking, heartPos);
            vbSetCreakingInfo.invoke(proxyHeart, creaking);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void removeProtector(BlockEntity proxyHeart) {
        try {
            vbRemoveProtector.invoke(proxyHeart, new Object[]{null});
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static BlockEntity getOrCreateProxyHeart(ServerLevel level, BlockPos pos, BlockState proxyHeartState) {
        final BlockEntity existing = level.getBlockEntity(pos);
        if (existing != null && vbHeartBeClass.isInstance(existing)) {
            return existing;
        }
        try {
            final LevelChunk chunk = level.getChunkAt(pos);
            final Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
            final BlockEntity injected = (BlockEntity) vbHeartBeCtor.newInstance(pos, proxyHeartState);
            beSetLevel.invoke(injected, level);
            beClearRemoved.invoke(injected);
            blockEntities.put(pos.immutable(), injected);
            return injected;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String desiredProxyStateName(boolean hasSupports, boolean hasLinkedCreaking, boolean naturalNight) {
        if (!hasSupports && !hasLinkedCreaking) {
            return "uprooted";
        }
        return naturalNight ? "awake" : "dormant";
    }

    private static CreakingHeartBranchBlock.HeartState desiredHeartState(boolean hasSupports, boolean hasLinkedCreaking, boolean naturalNight) {
        if (!hasSupports && !hasLinkedCreaking) {
            return CreakingHeartBranchBlock.HeartState.UPROOTED;
        }
        return naturalNight ? CreakingHeartBranchBlock.HeartState.AWAKE : CreakingHeartBranchBlock.HeartState.DORMANT;
    }

    private static void syncHeartVisualState(ServerLevel level, BlockPos pos, BlockState currentState, boolean hasSupports, boolean hasLinkedCreaking, boolean naturalNight) {
        final CreakingHeartBranchBlock.HeartState desired = desiredHeartState(hasSupports, hasLinkedCreaking, naturalNight);
        final boolean shouldRevealHeart = desired == CreakingHeartBranchBlock.HeartState.AWAKE || hasLinkedCreaking;
        final boolean hiddenNow = currentState.getValue(CreakingHeartBranchBlock.HIDDEN);
        final boolean nextHidden = shouldRevealHeart ? false : hiddenNow;

        if (currentState.getValue(CreakingHeartBranchBlock.STATE) != desired || hiddenNow != nextHidden) {
            level.setBlock(pos, currentState
                    .setValue(CreakingHeartBranchBlock.STATE, desired)
                    .setValue(CreakingHeartBranchBlock.HIDDEN, nextHidden), 3);
        }
    }

    private static net.minecraft.world.level.block.Block resolveProxyHeartBlock() {
        if (cachedProxyHeartBlock != null) {
            return cachedProxyHeartBlock;
        }

        net.minecraft.world.level.block.Block block = null;
        for (ResourceLocation id : CREAKING_HEART_BLOCK_IDS) {
            final net.minecraft.world.level.block.Block candidate = BuiltInRegistries.BLOCK.get(id);
            if (candidate != net.minecraft.world.level.block.Blocks.AIR) {
                block = candidate;
                break;
            }
        }
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            return null;
        }

        cachedProxyHeartBlock = block;
        return cachedProxyHeartBlock;
    }

    private static BlockState getProxyHeartState(String stateName) {
        if ("awake".equals(stateName) && cachedProxyHeartAwakeState != null) {
            return cachedProxyHeartAwakeState;
        }
        if ("dormant".equals(stateName) && cachedProxyHeartDormantState != null) {
            return cachedProxyHeartDormantState;
        }
        if ("uprooted".equals(stateName) && cachedProxyHeartUprootedState != null) {
            return cachedProxyHeartUprootedState;
        }

        final net.minecraft.world.level.block.Block block = resolveProxyHeartBlock();
        if (block == null) {
            return null;
        }

        BlockState state = block.defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            final String name = property.getName();
            if ("state".equals(name)) {
                state = setEnumBySerializedName(state, property, stateName);
            } else if ("natural".equals(name)) {
                @SuppressWarnings("unchecked")
                Property<Boolean> boolProp = (Property<Boolean>) property;
                state = state.setValue(boolProp, true);
            }
        }

        if ("awake".equals(stateName)) {
            cachedProxyHeartAwakeState = state;
            return cachedProxyHeartAwakeState;
        }
        if ("dormant".equals(stateName)) {
            cachedProxyHeartDormantState = state;
            return cachedProxyHeartDormantState;
        }
        cachedProxyHeartUprootedState = state;
        return cachedProxyHeartUprootedState;
    }

    private static void syncProxyHeartState(BlockEntity proxyHeart, String stateName) {
        if (beSetBlockState == null) {
            return;
        }
        final BlockState proxyState = getProxyHeartState(stateName);
        if (proxyState == null) {
            return;
        }
        try {
            beSetBlockState.invoke(proxyHeart, proxyState);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static EntityType<?> resolveCreakingType() {
        if (cachedCreakingType != null) {
            return cachedCreakingType;
        }
        for (ResourceLocation id : CREAKING_ENTITY_IDS) {
            final EntityType<?> candidate = EntityType.byString(id.toString()).orElse(null);
            if (candidate != null) {
                cachedCreakingType = candidate;
                return cachedCreakingType;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setEnumBySerializedName(BlockState state, Property property, String serialized) {
        for (Object possible : property.getPossibleValues()) {
            if (possible instanceof net.minecraft.util.StringRepresentable representable
                    && serialized.equals(representable.getSerializedName().toLowerCase(Locale.ROOT))) {
                return setPropertyUnchecked(state, property, possible);
            }
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setPropertyUnchecked(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }

    private static boolean resolveReflection() {
        if (reflectionResolved) {
            return true;
        }
        try {
            // Reflection is the compatibility layer that makes the 26.1.2 creaking-heart logic usable on 1.21.1.
            vbCreakingClass = Class.forName(CREAKING_CLASS);
            vbHeartBeClass = Class.forName(CREAKING_HEART_BE_CLASS);

            vbHeartBeCtor = vbHeartBeClass.getConstructor(BlockPos.class, BlockState.class);
            vbSetTransient = vbCreakingClass.getMethod("setTransient", BlockPos.class);
            vbGetHomePos = vbCreakingClass.getMethod("getHomePos");
            vbPlayerStuck = vbCreakingClass.getMethod("playerIsStuckInYou");
            vbTearDown = vbCreakingClass.getMethod("tearDown");

            vbSetCreakingInfo = vbHeartBeClass.getMethod("setCreakingInfo", vbCreakingClass);
            vbRemoveProtector = vbHeartBeClass.getMethod("removeProtector", net.minecraft.world.damagesource.DamageSource.class);
            beSetLevel = BlockEntity.class.getMethod("setLevel", net.minecraft.world.level.Level.class);
            beClearRemoved = BlockEntity.class.getMethod("clearRemoved");

            reflectionResolved = true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }

        // Optional: emitter tick support for orange+gray particle trail fidelity.
        // Failure here is non-critical; spawn logic still works without it.
        try {
            beSetBlockState = BlockEntity.class.getMethod("setBlockState", BlockState.class);
        } catch (ReflectiveOperationException ignored) {
            // Non-critical; visuals are still kept in sync on the DT heart block itself.
        }

        try {
            vbHeartEmitter = vbHeartBeClass.getDeclaredField("emitter");
            vbHeartEmitter.setAccessible(true);
            vbHeartEmitParticles = vbHeartBeClass.getDeclaredMethod("emitParticles", ServerLevel.class, int.class, boolean.class);
            vbHeartEmitParticles.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
            // Non-critical; particles degrade gracefully
        }

        return true;
    }

    /**
     * Manually drives the proxy heart's emitter field so that the vanilla-style particle trail between the heart
     * and the creaking still appears, and the damage gate eventually reopens on the backported runtime.
     */
    private static void tickProxyHeartEmitter(ServerLevel level, BlockEntity proxyHeart, BlockPos heartPos, BlockState heartState) {
        if (vbHeartEmitter == null || vbHeartEmitParticles == null) {
            return;
        }
        try {
            final int emitter = (int) vbHeartEmitter.get(proxyHeart);
            if (emitter <= 0) {
                return;
            }
            
            if (emitter > 50) {
                // Resin clumps are spawned from the heart-linked branch side effect that Vanilla Backport performs
                // when the emitter is freshly triggered by a hit.
                final GlobalPos heartKey = GlobalPos.of(level.dimension(), heartPos.immutable());
                final long gameTime = level.getGameTime();
                final Long lastGen = resinCooldownByHeart.get(heartKey);
                if (lastGen == null || gameTime - lastGen >= RESIN_GENERATION_COOLDOWN_TICKS) {
                    if (heartState.getBlock() instanceof CreakingHeartBranchBlock heartBlock) {
                        generateResinClumps(level, heartPos, heartBlock);
                        resinCooldownByHeart.put(heartKey, gameTime);
                    }
                }
            }

            // Compensate for lower tick frequency: emit a handful of particles per
            // spawner tick so the visual density is close to the vanilla 1-per-game-tick rate.
            if (emitter > 50) {
                for (int i = 0; i < 5; i++) {
                    vbHeartEmitParticles.invoke(proxyHeart, level, 1, true);  // orange
                    vbHeartEmitParticles.invoke(proxyHeart, level, 1, false); // gray
                }
            }
            // Drain emitter at TICK_INTERVAL per spawner call ≈ 1 per game tick.
            vbHeartEmitter.set(proxyHeart, Math.max(0, emitter - TICK_INTERVAL));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean isNaturalNight(ServerLevel level) {
        final int ticks = (int) (level.getDayTime() % 24000L);
        return ticks >= 12600 && ticks <= 23400;
    }

    public static void onCreakingDamaged(Entity entity, DamageSource source) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!resolveReflection()) {
            return;
        }
        if (!vbCreakingClass.isInstance(entity)) {
            return;
        }
        if (!isNaturalNight(level) || !isValidResinDamageSource(source)) {
            return;
        }

        try {
            final Object homePos = vbGetHomePos.invoke(entity);
            if (!(homePos instanceof BlockPos heartPos)) {
                return;
            }

            final BlockState heartState = level.getBlockState(heartPos);
            if (!(heartState.getBlock() instanceof CreakingHeartBranchBlock heartBlock)) {
                return;
            }

            final GlobalPos heartKey = GlobalPos.of(level.dimension(), heartPos.immutable());
            final long gameTime = level.getGameTime();
            final Long lastGenerationTick = resinCooldownByHeart.get(heartKey);
            if (lastGenerationTick != null && gameTime - lastGenerationTick < RESIN_GENERATION_COOLDOWN_TICKS) {
                return;
            }

            generateResinClumps(level, heartPos, heartBlock);
            resinCooldownByHeart.put(heartKey, gameTime);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean isValidResinDamageSource(DamageSource source) {
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return true;
        }

        final Entity attacker = source.getEntity();
        if (attacker instanceof Player) {
            return true;
        }

        if (attacker instanceof TamableAnimal tamable && tamable.isTame() && tamable.getOwner() instanceof Player) {
            return true;
        }

        final Entity directSource = source.getDirectEntity();
        return directSource instanceof Projectile projectile && projectile.getOwner() instanceof Player;
    }

    private static void generateResinClumps(ServerLevel level, BlockPos heartPos, CreakingHeartBranchBlock heartBlock) {
        final List<BlockPos> candidates = collectTaxicabBranchCandidates(level, heartPos);
        if (candidates.isEmpty()) {
            return;
        }

        Collections.shuffle(candidates, new java.util.Random(level.getRandom().nextLong()));
        int resinToPlace = RESIN_CLUMP_BASE_COUNT + level.getRandom().nextInt(2);

        for (BlockPos candidate : candidates) {
            if (resinToPlace <= 0) {
                break;
            }
            if (tryResinizeBranch(level, candidate, heartPos, heartBlock)) {
                resinToPlace--;
            }
        }
    }

    private static List<BlockPos> collectTaxicabBranchCandidates(ServerLevel level, BlockPos heartPos) {
        final Deque<PosDepth> queue = new ArrayDeque<>();
        final Set<BlockPos> visited = new HashSet<>();
        final List<BlockPos> candidates = new ArrayList<>();

        queue.addLast(new PosDepth(heartPos, 0));
        visited.add(heartPos.immutable());

        while (!queue.isEmpty()) {
            final PosDepth current = queue.removeFirst();
            if (current.depth >= RESIN_SEARCH_RADIUS) {
                continue;
            }

            for (Direction direction : Direction.values()) {
                final BlockPos nextPos = current.pos.relative(direction);
                if (!visited.add(nextPos.immutable())) {
                    continue;
                }

                final int nextDepth = current.depth + 1;
                if (nextDepth > RESIN_SEARCH_RADIUS) {
                    continue;
                }

                final BlockState nextState = level.getBlockState(nextPos);
                if (TreeHelper.isBranch(nextState)
                        && !(nextState.getBlock() instanceof CreakingHeartBranchBlock)
                        && !(nextState.getBlock() instanceof ResinBranchBlock)) {
                    candidates.add(nextPos.immutable());
                }

                queue.addLast(new PosDepth(nextPos.immutable(), nextDepth));
            }
        }

        return candidates;
    }

    private static boolean tryResinizeBranch(ServerLevel level, BlockPos branchPos, BlockPos heartPos, CreakingHeartBranchBlock heartBlock) {
        if (branchPos.equals(heartPos)) {
            return false;
        }

        final BlockState before = level.getBlockState(branchPos);
        if (!TreeHelper.isBranch(before)
                || before.getBlock() instanceof CreakingHeartBranchBlock
                || before.getBlock() instanceof ResinBranchBlock) {
            return false;
        }

        heartBlock.addResinToBranch(before, level, branchPos);

        final BlockState after = level.getBlockState(branchPos);
        return after.getBlock() instanceof ResinBranchBlock;
    }

    private static void cleanupOrphanedCreakings(ServerLevel level) {
        final EntityType<?> creakingType = resolveCreakingType();
        if (creakingType == null) {
            return;
        }

        final List<Entity> entities = level.getEntities((Entity) null,
            new AABB(-30_000_000, level.getMinBuildHeight(), -30_000_000,
                30_000_000, level.getMaxBuildHeight(), 30_000_000),
                entity -> entity.getType() == creakingType && vbCreakingClass.isInstance(entity));

        for (Entity entity : entities) {
            try {
                final Object home = vbGetHomePos.invoke(entity);
                if (!(home instanceof BlockPos homePos)) {
                    continue;
                }

                final BlockState stateAtHome = level.getBlockState(homePos);
                if (stateAtHome.getBlock() instanceof CreakingHeartBranchBlock) {
                    continue;
                }

                final BlockEntity be = level.getBlockEntity(homePos);
                if (be != null && vbHeartBeClass.isInstance(be)) {
                    removeProtector(be);
                    final LevelChunk chunk = level.getChunkAt(homePos);
                    chunk.getBlockEntities().remove(homePos);
                } else if (vbTearDown != null) {
                    vbTearDown.invoke(entity);
                } else {
                    entity.discard();
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static void pruneResinCooldowns(ServerLevel level) {
        if (resinCooldownByHeart.isEmpty()) {
            return;
        }

        final long now = level.getGameTime();
        resinCooldownByHeart.entrySet().removeIf(entry -> {
            final GlobalPos key = entry.getKey();
            if (!key.dimension().equals(level.dimension())) {
                return false;
            }
            return now - entry.getValue() >= RESIN_GENERATION_COOLDOWN_TICKS;
        });
    }

    private record PosDepth(BlockPos pos, int depth) {
    }
}
