package com.dtteam.dynamictrees.block.branch;

import com.dtteam.dynamictrees.api.network.MapSignal;
import com.dtteam.dynamictrees.config.DTConfigs;
import com.dtteam.dynamictrees.data.DTLootTableBuilder;
import com.dtteam.dynamictrees.platform.Services;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.tree.family.CreakingHeartFamily;
import net.minecraft.advancements.critereon.EnchantmentPredicate;
import net.minecraft.advancements.critereon.ItemEnchantmentsPredicate;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.ItemSubPredicates;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.ApplyExplosionDecay;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class CreakingHeartBranchBlock extends BasicBranchBlock {

    public enum HeartState implements StringRepresentable {
        AWAKE,
        DORMANT,
        UPROOTED;

        @Override
        public String getSerializedName() {
            return this.name().toLowerCase(Locale.ENGLISH);
        }
    }

    public static final EnumProperty<HeartState> STATE = EnumProperty.create("state", HeartState.class);
    public static final BooleanProperty HIDDEN = BooleanProperty.create("hidden");

    public CreakingHeartBranchBlock(ResourceLocation name, Properties properties) {
        super(name, properties);
    }

    @Override
    public BlockState[] createBranchStates(IntegerProperty radiusProperty, int maxRadius) {
        registerDefaultState(defaultBlockState().setValue(STATE, HeartState.DORMANT).setValue(HIDDEN, true));
        return super.createBranchStates(radiusProperty, maxRadius);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STATE, HIDDEN);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public BlockState getStateForRadius(int radius) {
        return super.getStateForRadius(radius).setValue(STATE, HeartState.DORMANT).setValue(HIDDEN, true);
    }

    @Override
    public int setRadius(LevelAccessor level, BlockPos pos, int radius, @Nullable Direction originDir, int flags) {
        final BlockState previousState = level.getBlockState(pos);

        super.setRadius(level, pos, radius, originDir, flags);

        BlockState updatedState = level.getBlockState(pos);
        if (!(updatedState.getBlock() instanceof CreakingHeartBranchBlock)) {
            return radius;
        }

        HeartState stateToKeep = HeartState.DORMANT;
        boolean hiddenToKeep = DTConfigs.SERVER.hideCreakingHeart.get();
        if (previousState.getBlock() instanceof CreakingHeartBranchBlock) {
            if (previousState.hasProperty(STATE)) {
                stateToKeep = previousState.getValue(STATE);
                if (stateToKeep == HeartState.UPROOTED) {
                    stateToKeep = HeartState.DORMANT;
                }
            }
            if (previousState.hasProperty(HIDDEN)) {
                hiddenToKeep = previousState.getValue(HIDDEN);
            }
        }

        updatedState = updatedState.setValue(STATE, stateToKeep).setValue(HIDDEN, hiddenToKeep);

        final boolean wasWaterSource = previousState.getFluidState() == Fluids.WATER.getSource(false);
        if (wasWaterSource && updatedState.hasProperty(WATERLOGGED)) {
            updatedState = updatedState.setValue(WATERLOGGED, radius <= 7);
        }

        level.setBlock(pos, updatedState, flags);
        return radius;
    }

    @Override
    public void futureBreak(BlockState state, Level level, BlockPos cutPos, LivingEntity entity) {
        super.futureBreak(state, level, cutPos, entity);
    }

    @Override
    public LootTable.Builder createBranchDrops(HolderLookup.Provider registries) {
        final HolderLookup.RegistryLookup<Enchantment> enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        final net.minecraft.world.level.storage.loot.predicates.LootItemCondition.Builder hasSilkTouch = MatchTool.toolMatches(
            ItemPredicate.Builder.item().withSubPredicate(
                ItemSubPredicates.ENCHANTMENTS,
                ItemEnchantmentsPredicate.enchantments(List.of(
                    new EnchantmentPredicate(enchantments.getOrThrow(Enchantments.SILK_TOUCH), MinMaxBounds.Ints.atLeast(1))
                ))
            )
        );

        if (!(getFamily() instanceof CreakingHeartFamily heartFamily)) {
            return DTLootTableBuilder.createBranchDrops(getPrimitiveLog().orElse(net.minecraft.world.level.block.Blocks.OAK_LOG),
                getFamily().getStick(1).getItem(), registries);
        }

        // Creaking heart uses explicit Silk Touch dispatch: heart block with Silk Touch,
        // otherwise resin clumps. This mirrors vanilla heart behavior.
        return LootTable.lootTable().withPool(
            LootPool.lootPool().setRolls(ConstantValue.exactly(1)).add(
                LootItem.lootTableItem(heartFamily.getPrimitiveHeartLog().orElse(net.minecraft.world.level.block.Blocks.OAK_LOG))
                    .when(hasSilkTouch)
                    .otherwise(
                        LootItem.lootTableItem(heartFamily.getResinItem())
                            .apply(SetItemCountFunction.setCount(UniformGenerator.between(1.0F, 3.0F)))
                            .apply(ApplyExplosionDecay.explosionDecay())
                    )
            )
        ).setParamSet(com.dtteam.dynamictrees.loot.DTLootParameterSets.BRANCHES);
    }

    public void addResinToBranch(BlockState state, Level level, BlockPos pos) {
        if (!(getFamily() instanceof CreakingHeartFamily family)) {
            return;
        }
        if (family.getAltBranch().isEmpty() || family.getBranch().isEmpty()) return;
        BranchBlock branchBlock = TreeHelper.getBranch(state);
        if (branchBlock == null || branchBlock != family.getBranch().get()) return;
        // This is the branch-side resin hook used by the Pale Garden backport to mirror the linked heart effect.
        // Read radius from the source branch block instance. Using this heart block
        // can collapse thick branches/trunks to thin geometry when properties differ.
        int radius = branchBlock.getRadius(state);

        // Skip only massive trunk-core radii where shell rendering becomes unstable.
        // Mature pale-oak branches around the heart are often >2 radius.
        if (radius > 8) {
            return;
        }

        family.getAltBranch().get().setRadius(level, pos, radius, null, 3);
    }

    @Override
    public boolean canBeStripped(BlockState state, Level level, BlockPos pos, Player player, ItemStack heldItem) {
        return state.getValue(HIDDEN) && super.canBeStripped(state, level, pos, player, heldItem) && Services.INTERACTION.canToolAxeStrip(heldItem);
    }

    @Override
    public void stripBranch(BlockState state, LevelAccessor level, BlockPos pos, int radius) {
        level.setBlock(pos, state.setValue(HIDDEN, false), 3);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        if (state.getValue(HIDDEN)) {
            if (!level.isClientSide()) {
                level.levelEvent(null, 2001, pos, getId(state));
            }
            level.setBlock(pos, state.setValue(HIDDEN, false), 3);
            return false;
        }

        if (!level.isClientSide()) {
            level.levelEvent(null, 2001, pos, getId(state));

            if (!player.isCreative() && getFamily() instanceof CreakingHeartFamily heartFamily) {
                final ItemStack heldItem = player.getMainHandItem();
                final boolean silkTouch = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), heldItem) > 0;

                if (silkTouch) {
                    popResource(level, pos, new ItemStack(heartFamily.getPrimitiveHeartLog().orElse(net.minecraft.world.level.block.Blocks.AIR)));
                } else {
                    final int resinCount = 1 + level.getRandom().nextInt(3);
                    popResource(level, pos, new ItemStack(heartFamily.getResinItem(), resinCount));
                }

                if (level instanceof ServerLevel serverLevel) {
                    popExperience(serverLevel, pos, 20 + level.getRandom().nextInt(5));
                }
            }
        }

        BlockState replacement = fluid.createLegacyBlock();
        if (level.isClientSide()) {
            level.setBlock(pos, replacement, 11);
        } else {
            // Use ignored set to avoid BranchBlock sloppy-break side effects that can
            // emit unrelated branch drops (e.g. pale_oak_log) when removing the heart.
            this.setBlockStateIgnored(level, pos, replacement, 3);
        }
        return false;
    }

    @Override
    public float getHardness(BlockState state, BlockGetter level, BlockPos pos) {
        float hardness = super.getHardness(state, level, pos);
        if (state.getValue(HIDDEN) && getFamily() instanceof CreakingHeartFamily heartFamily) {
            return hardness * heartFamily.getHiddenHeartHardnessMultiplier();
        }
        return hardness;
    }

    @Nullable
    public static BlockPos findFromBranch(BlockState state, BlockGetter level, BlockPos pos, int stepsLeft, HashSet<BlockPos> explored, @Nullable Direction from) {
        if (!TreeHelper.isBranch(state) && !(state.getBlock() instanceof CreakingHeartBranchBlock)) {
            return null;
        }
        if (explored.size() > 4096) {
            return null;
        }
        if (state.getBlock() instanceof CreakingHeartBranchBlock) {
            if (state.getValue(STATE) == HeartState.UPROOTED) return null;
            return pos;
        }
        if (stepsLeft <= 0) return null;
        explored.add(pos);
        for (Direction dir : Direction.values()) {
            if (dir == from) continue;
            BlockPos sidePos = pos.relative(dir);
            if (explored.contains(sidePos)) continue;
            BlockState sideState = level.getBlockState(sidePos);
            if (TreeHelper.isBranch(sideState)) {
                BlockPos foundPos = findFromBranch(sideState, level, sidePos, stepsLeft - 1, explored, dir.getOpposite());
                if (foundPos != null) return foundPos;
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos findFromBranch(BlockState state, BlockGetter level, BlockPos pos, int stepsLeft) {
        final int clampedSteps = Math.max(1, Math.min(stepsLeft, 256));
        return findFromBranch(state, level, pos, clampedSteps, new HashSet<>(), null);
    }

    @Override
    protected SoundType getSoundType(BlockState state) {
        if (state.getValue(HIDDEN) && getFamily().getBranch().isPresent()) {
            return getFamily().getBranch().map(block -> block.defaultBlockState().getSoundType()).orElseGet(() -> super.getSoundType(state));
        }
        return super.getSoundType(state);
    }

    /**
     * We cannot use BranchBlock.analyse because this path is used from a BlockGetter-only context.
     */
    @Nullable
    public static BlockPos findFromBranch(BlockState state, BlockGetter level, BlockPos pos) {
        return findFromBranch(state, level, pos, 32, new HashSet<>(), null);
    }

}
