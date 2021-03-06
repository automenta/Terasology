/*
 * Copyright 2015 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.primitives;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3i;
import org.terasology.math.geom.Vector4f;
import org.terasology.rendering.assets.mesh.Mesh;
import org.terasology.world.ChunkView;
import org.terasology.world.biomes.Biome;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockAppearance;
import org.terasology.world.block.BlockPart;
import org.terasology.world.block.shapes.BlockMeshPart;

public class BlockMeshGeneratorSingleShape implements BlockMeshGenerator {
    private static final Logger logger = LoggerFactory.getLogger(BlockMeshGeneratorSingleShape.class);

    private Block block;
    private Mesh mesh;

    final Block[] adjacent = new Block[6];

    public BlockMeshGeneratorSingleShape(Block block) {
        this.block = block;
    }

    @Override
    public void generateChunkMesh(ChunkView view, ChunkMesh chunkMesh, int x, int y, int z) {
        Biome selfBiome = view.getBiome(x, y, z);
        Block selfBlock = view.getBlock(x, y, z);

        // Gather adjacent blocks
        //Map<Side, Block> adjacentBlocks = Maps.newEnumMap(Side.class);

        Block[] adjacent = this.adjacent;
        int n = 0;
        for (Side side : Side.values()) {
            Vector3i offset = side.getVector3i();
            adjacent[n++] = view.getBlock(x + offset.x, y + offset.y, z + offset.z);
        }

        // TODO: Needs review - too much hardcoded special cases and corner cases resulting from this.
        ChunkVertexFlag vertexFlag = ChunkVertexFlag.NORMAL;
        if (selfBlock.isWater()) {
            vertexFlag = adjacent[0 /*TOP */].isWater() ?
                    ChunkVertexFlag.WATER : ChunkVertexFlag.WATER_SURFACE;
        } else if (selfBlock.isLava()) {
            vertexFlag = ChunkVertexFlag.LAVA;
        } else if (selfBlock.isWaving() && selfBlock.isDoubleSided()) {
            vertexFlag = ChunkVertexFlag.WAVING;
        } else if (selfBlock.isWaving()) {
            vertexFlag = ChunkVertexFlag.WAVING_BLOCK;
        }



        BlockAppearance blockAppearance = selfBlock.getAppearance(adjacent);

        /*
         * Determine the render process.
         */
        ChunkMesh.RenderType renderType = ChunkMesh.RenderType.TRANSLUCENT;

        if (!selfBlock.isTranslucent()) {
            renderType = ChunkMesh.RenderType.OPAQUE;
        }
        // TODO: Review special case, or alternatively compare uris.
        if (selfBlock.isWater() || selfBlock.isIce()) {
            renderType = ChunkMesh.RenderType.WATER_AND_ICE;
        }
        if (selfBlock.isDoubleSided()) {
            renderType = ChunkMesh.RenderType.BILLBOARD;
        }

        BlockMeshPart centerPart = blockAppearance.getPart(BlockPart.CENTER);
        if (centerPart != null) {
            Vector4f colorOffset = selfBlock.calcColorOffsetFor(BlockPart.CENTER, selfBiome);
            centerPart.appendTo(chunkMesh, x, y, z, colorOffset, renderType, vertexFlag);
        }

        boolean[] drawDir = new boolean[6];

        for (Side side : Side.values()) {
            int sideOrd = side.ordinal();
            drawDir[sideOrd] = blockAppearance.getPart(BlockPart.fromSide(side)) != null &&
                    isSideVisibleForBlockTypes(adjacent[sideOrd], selfBlock, side);
        }

        // If the selfBlock is lowered, some more faces may have to be drawn
        if (selfBlock.isLiquid()) {
            Block bottomBlock = adjacent[1 /* BOTTOM */];
            // Draw horizontal sides if visible from below
            for (Side side : Side.horizontalSides()) {
                Vector3i offset = side.getVector3i();
                Block adjacentBelow = view.getBlock(x + offset.x, y - 1, z + offset.z);
                int sideOrd = side.ordinal();
                Block adj = adjacent[sideOrd];

                boolean visible = (blockAppearance.getPart(BlockPart.fromSide(side)) != null
                        && isSideVisibleForBlockTypes(adjacentBelow, selfBlock, side) && !isSideVisibleForBlockTypes(bottomBlock, adj, side.reverse()));
                drawDir[sideOrd] |= visible;
            }

            // Draw the top if below a non-lowered selfBlock
            // TODO: Don't need to render the top if each side and the selfBlock above each side are either liquid or opaque solids.
            Block blockToCheck = adjacent[0 /* TOP */];
            drawDir[0 /* TOP */] |= !blockToCheck.isLiquid();

            if (bottomBlock.isLiquid() || bottomBlock.getMeshGenerator() == null) {
                for (Side dir : Side.values()) {
                    if (drawDir[dir.ordinal()]) {
                        Vector4f colorOffset = selfBlock.calcColorOffsetFor(BlockPart.fromSide(dir), selfBiome);
                        selfBlock.getLoweredLiquidMesh(dir).appendTo(chunkMesh, x, y, z, colorOffset, renderType, vertexFlag);
                    }
                }
                return;
            }
        }

        for (Side dir : Side.values()) {
            if (drawDir[dir.ordinal()]) {
                Vector4f colorOffset = selfBlock.calcColorOffsetFor(BlockPart.fromSide(dir), selfBiome);
                // TODO: Needs review since the new per-vertex flags introduce a lot of special scenarios - probably a per-side setting?
                if (selfBlock.isGrass() && dir != Side.TOP && dir != Side.BOTTOM) {
                    blockAppearance.getPart(BlockPart.fromSide(dir)).appendTo(chunkMesh, x, y, z, colorOffset, renderType, ChunkVertexFlag.COLOR_MASK);
                } else {
                    //if(dir == Side.TOP) logger.info("Generating: " + (new Vector3i(x, y, z)).toString() + " " + view.getChunkRegion().toString() + " " + dir.toString());

                    if (blockAppearance.getPart(BlockPart.fromSide(dir)) == null) {
                        // TODO: This would catch something like water blocks attempting to render with a "fixed" trimmedLoweredCube shape
                        // That shape has its top trimmed down a bit to let water sit slightly lower than land, however, underwater this shouldn't show
                        // Normally we would configure that shape with CENTER instead of TOP, that way the trimmed part wouldn't occlude in a stack
                        // But with that handling you don't get water blocks occluding tops underwater... and there's no TOP to retrieve below -> NPE
                        logger.debug("Cannot render side '{}' for a block - no stored block appearance for it. renderType {}, vertexFlag {}", dir, renderType, vertexFlag);
                    } else {
                        blockAppearance.getPart(BlockPart.fromSide(dir)).appendTo(chunkMesh, x, y, z, colorOffset, renderType, vertexFlag);
                    }
                }
            }
        }
    }

    /**
     * Returns true if the side should be rendered adjacent to the second side provided.
     *
     * @param blockToCheck The block to check
     * @param currentBlock The current block
     * @return True if the side is visible for the given block types
     */
    private boolean isSideVisibleForBlockTypes(Block blockToCheck, Block currentBlock, Side side) {
        // Liquids can be transparent but there should be no visible adjacent faces
        if (currentBlock.isLiquid() && blockToCheck.isLiquid()) {
            return false;
        }

        return currentBlock.isWaving() != blockToCheck.isWaving() || blockToCheck.getMeshGenerator() == null
                || !blockToCheck.isFullSide(side.reverse()) || (!currentBlock.isTranslucent() && blockToCheck.isTranslucent());

    }

    @Override
    public Mesh getStandaloneMesh() {
        Mesh m = this.mesh;
        if (m == null || m.isDisposed()) {
            return generateMesh();
        }
        return m;
    }

    private Mesh generateMesh() {
        Tessellator tessellator = new Tessellator();
        Block block = this.block;
        for (BlockPart dir : BlockPart.values()) {
            BlockMeshPart part = block.getPrimaryAppearance().getPart(dir);
            if (part != null) {
                if (block.isDoubleSided()) {
                    tessellator.addMeshPartDoubleSided(part);
                } else {
                    tessellator.addMeshPart(part);
                }
            }
        }
        return this.mesh = tessellator.generateMesh(new ResourceUrn("engine", "blockmesh", block.getURI().toString()));
    }
}
