package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.IncongruousStructures;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In an {@code incongruous structures} Age, a structure may stand in any biome, and is told where it is being tried
 * so the height drawn for it holds for the whole start ({@link IncongruousStructures}).
 *
 * <p><b>Why a mixin, and what was checked first.</b> {@code tryGenerateStructure} is private and builds the
 * structure's biome test from {@code Structure.biomes()} inline; neither loader has a structure-start event that
 * can change it, and our generator's {@code createStructures} override sees only the starts that already passed.
 * Answered off a thread-local set around our own starts, so every other level's structures are vanilla's.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "tryGenerateStructure", at = @At("HEAD"))
    private void agesandtheart$beginning(
        StructureSet.StructureSelectionEntry selected,
        StructureManager structureManager,
        RegistryAccess registryAccess,
        RandomState randomState,
        StructureTemplateManager structureTemplateManager,
        long seed,
        ChunkAccess centerChunk,
        ChunkPos sourceChunkPos,
        ResourceKey<Level> level,
        Climate.Sampler climateSampler,
        CallbackInfoReturnable<Boolean> callback
    ) {
        IncongruousStructures.beginning(sourceChunkPos);
    }

    @Redirect(
        method = "tryGenerateStructure",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/Structure;biomes()Lnet/minecraft/core/HolderSet;"
        )
    )
    private HolderSet<Biome> agesandtheart$anyBiome(Structure structure) {
        return IncongruousStructures.biomesFor(structure);
    }
}
