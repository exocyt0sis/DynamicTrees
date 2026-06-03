package com.dtteam.dynamictrees.tree.family;

import com.dtteam.dynamictrees.DynamicTrees;
import com.dtteam.dynamictrees.api.registry.RegistryHandler;
import com.dtteam.dynamictrees.api.registry.TypedRegistry;
import com.dtteam.dynamictrees.block.branch.BasicBranchBlock;
import com.dtteam.dynamictrees.block.branch.BranchBlock;
import com.dtteam.dynamictrees.block.branch.CreakingHeartBranchBlock;
import com.dtteam.dynamictrees.block.branch.ResinBranchBlock;
import com.dtteam.dynamictrees.block.branch.ThickBranchBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.utility.Optionals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.tags.IntrinsicHolderTagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.dtteam.dynamictrees.utility.ResourceLocationUtils.suffix;

public class CreakingHeartFamily extends AltBranchFamily {

    public static final TypedRegistry.EntryType<Family> TYPE = TypedRegistry.newType(CreakingHeartFamily::new);

    protected float treeHeartHardnessMultiplier = 1;
    protected float treeBaseHardnessMultiplier = 0.125f;
    protected float hiddenHeartHardnessMultiplier = 0.4f;
    protected Item resinItem = Items.SLIME_BALL;
    protected Block resinBlock = Blocks.SLIME_BLOCK;
    protected Supplier<BranchBlock> heartBranch;
    protected Block primitiveHeartLog = Blocks.AIR;

    public CreakingHeartFamily(ResourceLocation name) {
        super(name);
    }

    @Override
    public void setupBlocks() {
        super.setupBlocks();
        this.heartBranch = this.setupBranch(this.createHeartBranch(this.getRegistryName()), true);
    }

    protected Supplier<BranchBlock> createHeartBranch(final ResourceLocation name) {
        return RegistryHandler.addBlock(suffix(name, "_creaking_heart" + BranchBlock.NAME_SUFFIX), () -> createHeartBranchBlock(name));
    }

    protected BranchBlock createHeartBranchBlock(ResourceLocation name) {
        return new CreakingHeartBranchBlock(name, this.getProperties());
    }

    public Family setPrimitiveHeartLog(Block primitiveLog) {
        this.primitiveHeartLog = primitiveLog;
        if (this.heartBranch != null) {
            this.heartBranch.get().setPrimitiveLogDrops(new net.minecraft.world.item.ItemStack(primitiveLog));
        }
        return this;
    }

    public Optional<BranchBlock> getHeartBranch() {
        return Optionals.ofBlock(this.heartBranch);
    }

    public Optional<Block> getPrimitiveHeartLog() {
        return Optionals.ofBlock(this.primitiveHeartLog);
    }

    public ResourceLocation getHeartBranchLoader() {
        return DynamicTrees.location("creaking_heart");
    }

    public void addHeartTextures(BiConsumer<String, ResourceLocation> textureConsumer, ResourceLocation primitiveLogLocation, Block sourceBlock, String state) {
        Optional<Block> primHeart = getPrimitiveHeartLog();
        if (primHeart.isPresent() && primHeart.get() == sourceBlock) {
            String u = state.isEmpty() ? "" : "_";
            ResourceLocation barkAwake = primitiveLogLocation.withSuffix(u + state);
            ResourceLocation ringsAwake = primitiveLogLocation.withSuffix("_top" + u + state);
            String textureName = state + u + "heart_branch";
            if (this.textureOverrides.containsKey(textureName)) barkAwake = this.textureOverrides.get(textureName);
            if (this.textureOverrides.containsKey(textureName + "_top")) ringsAwake = this.textureOverrides.get(textureName + "_top");
            textureConsumer.accept("heart_bark", barkAwake);
            textureConsumer.accept("heart_rings", ringsAwake);
        } else {
            DynamicTrees.LOG.error("Attempted to load heart branch textures for family {} but provided block {} was not its heart branch.", getRegistryName(), primHeart);
        }
    }

    @Override
    public void addGeneratedBlockTags(Function<TagKey<Block>, IntrinsicHolderTagsProvider.IntrinsicTagAppender<Block>> tagAppender) {
        super.addGeneratedBlockTags(tagAppender);
        getHeartBranch().ifPresent(branch -> {
            tierTag(getDefaultBranchHarvestTier(), tagAppender).ifPresent(tagBuilder -> tagBuilder.add(branch));
            defaultBranchTags().forEach(tag -> {
                if (!isOnlyIfLoaded()) {
                    tagAppender.apply(tag).add(branch);
                } else {
                    tagAppender.apply(tag).addOptional(BuiltInRegistries.BLOCK.getKey(branch));
                }
            });
        });
    }

    @Override
    protected BranchBlock createBranchBlock(ResourceLocation name) {
        return this.isThick() ? new ThickBranchBlock(name, this.getProperties()) {
            @Override
            public float getHardness(BlockState state, BlockGetter level, BlockPos pos) {
                return ((CreakingHeartFamily) getFamily()).getTreeHardness(state, level, pos, super.getHardness(state, level, pos));
            }

            @Override
            protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
                super.attack(state, level, pos, player);
            }
        } : new BasicBranchBlock(name, this.getProperties()) {
            @Override
            public float getHardness(BlockState state, BlockGetter level, BlockPos pos) {
                return ((CreakingHeartFamily) getFamily()).getTreeHardness(state, level, pos, super.getHardness(state, level, pos));
            }

            @Override
            protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
                super.attack(state, level, pos, player);
            }
        };
    }

    @Override
    protected BranchBlock createAltBranchBlock(ResourceLocation name) {
        return new ResinBranchBlock(name, this.getProperties());
    }

    @Override
    protected ResourceLocation getAltBranchName() {
        return getBranchName("resin_");
    }

    @Override
    protected ResourceLocation altBranchModelGenerator() {
        return DynamicTrees.location("resin_branch");
    }

    @Override
    public Family setPrimitiveLog(Block primitiveLog) {
        setPrimitiveAltLog(primitiveLog);
        return super.setPrimitiveLog(primitiveLog);
    }

    public void addResinTextures(BiConsumer<String, ResourceLocation> textureConsumer, ResourceLocation primitiveResinLocation) {
        ResourceLocation resin = primitiveResinLocation;
        if (this.textureOverrides.containsKey("resin")) {
            resin = this.textureOverrides.get("resin");
        }

        textureConsumer.accept("bark", resin);
        textureConsumer.accept("rings", resin);
    }

    public boolean registerDefaultBlockEntity() {
        return false;
    }

    public boolean hasHeart(BlockState state, BlockGetter level, BlockPos pos) {
        if (!TreeHelper.isBranch(state)) {
            return false;
        }
        return getHeartPos(state, level, pos) != null;
    }

    private @Nullable BlockPos getHeartPos(BlockState state, BlockGetter level, BlockPos pos) {
        final int maxSearchDepth = Math.max(1, Math.min(this.getMaxSignalDepth(), 256));
        return CreakingHeartBranchBlock.findFromBranch(state, level, pos, maxSearchDepth);
    }

    public float getTreeHardness(BlockState state, BlockGetter level, BlockPos pos, float baseHardness) {
        float hardness = baseHardness * treeBaseHardnessMultiplier;
        if (hasHeart(state, level, pos)) {
            hardness *= treeHeartHardnessMultiplier;
        }
        return hardness;
    }

    public void setTreeHeartHardnessMultiplier(float treeWhenHeartHardnessMultiplier) {
        this.treeHeartHardnessMultiplier = treeWhenHeartHardnessMultiplier;
    }

    public void setTreeBaseHardnessMultiplier(float treeBaseHardnessMultiplier) {
        this.treeBaseHardnessMultiplier = treeBaseHardnessMultiplier;
    }

    public float getHiddenHeartHardnessMultiplier() {
        return hiddenHeartHardnessMultiplier;
    }

    public void setHiddenHeartHardnessMultiplier(float hiddenHeartHardnessMultiplier) {
        this.hiddenHeartHardnessMultiplier = hiddenHeartHardnessMultiplier;
    }

    public void setResinItem(Item resinItem) {
        this.resinItem = resinItem;
    }

    public Item getResinItem() {
        return resinItem;
    }

    public ItemStack createResinDrop(RandomSource random, int radius) {
        final int safeRadius = Math.max(1, radius);
        final int count = Math.max(1, Math.round(random.nextIntBetweenInclusive(2, 3) * (safeRadius / 8f)));
        return new ItemStack(getResinItem(), count);
    }

    public void setResinBlock(Block resinBlock) {
        this.resinBlock = resinBlock;
    }

    public Block getResinBlock() {
        return resinBlock;
    }

}
