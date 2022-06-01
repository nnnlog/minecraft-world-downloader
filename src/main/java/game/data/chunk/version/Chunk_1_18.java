package game.data.chunk.version;

import config.Version;
import game.data.chunk.BlockEntityRegistry;
import game.data.chunk.palette.GlobalPalette;
import game.data.registries.RegistryManager;
import game.data.chunk.ChunkSection;
import game.data.chunk.palette.BlockState;
import game.data.chunk.palette.GlobalPaletteProvider;
import game.data.chunk.palette.Palette;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDim2D;
import packets.DataTypeProvider;
import packets.builder.PacketBuilder;
import se.llbit.nbt.*;

public class Chunk_1_18 extends Chunk_1_17 {
    public static final Version VERSION = Version.V1_18;

    public Chunk_1_18(CoordinateDim2D location) {
        super(location);
    }

    @Override
    public int getDataVersion() { return VERSION.dataVersion; }

    @Override
    public ChunkSection createNewChunkSection(byte y, Palette palette) {
        return new ChunkSection_1_18(y, palette, this);
    }

    @Override
    protected ChunkSection parseSection(int sectionY, SpecificTag section) {
        return new ChunkSection_1_18(sectionY, section);
    }

    /**
     * Parse the chunk data.
     *
     * @param dataProvider network input
     */
    @Override
    protected void parse(DataTypeProvider dataProvider) {
        raiseEvent("parse from packet");

        parseHeightMaps(dataProvider);

        int size = dataProvider.readVarInt();

        try {
            readChunkColumn(dataProvider.ofLength(size));

            parseBlockEntities(dataProvider);

            updateLight(dataProvider);
        } catch (Exception ex) {
            // seems to happen when there's blocks above 192 under some conditions
            System.out.println("Can't (fully?) parse chunk at " + location + ". Cause: " + ex.getMessage());
        }
        
        afterParse();
    }


    /**
     * Read a chunk column for 1.18
     */
    public void readChunkColumn(DataTypeProvider dataProvider) {
        // Loop through section Y values, starting from the lowest section that has blocks inside it.
        for (int sectionY = getMinBlockSection(); sectionY <= getMaxSection() + 1 && dataProvider.hasNext(); sectionY++) {
            ChunkSection_1_18 section = (ChunkSection_1_18) getChunkSection(sectionY);

            int blockCount = dataProvider.readShort();
            Palette blockPalette = Palette.readPalette(dataProvider);

            if (section == null) {
                section = (ChunkSection_1_18) createNewChunkSection((byte) (sectionY & 0xFF), blockPalette);
            }

            section.setBlocks(dataProvider.readLongArray(dataProvider.readVarInt()));

            section.setBiomePalette(Palette.readPalette(dataProvider));
            section.setBiomes(dataProvider.readLongArray(dataProvider.readVarInt()));

            // May replace an existing section or a null one
            setChunkSection(sectionY, section);

            // servers don't (always?) include containers in the list of block_entities. We need to know that these block
            // entities exist, otherwise we'll end up not writing block entity data for them
            if (containsBlockEntities(blockPalette)) {
                findBlockEntities(section, sectionY);
            }
        }
    }

    private void findBlockEntities(ChunkSection section, int sectionY) {
        BlockEntityRegistry blockEntities = RegistryManager.getInstance().getBlockEntityRegistry();
        GlobalPalette globalPalette = GlobalPaletteProvider.getGlobalPalette(getDataVersion());

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = globalPalette.getState(section.getNumericBlockStateAt(x, y, z));

                    if (blockEntities.isBlockEntity(state.getName())) {
                        Coordinate3D coords = new Coordinate3D(x, y, z).sectionLocalToGlobal(sectionY, this.location);
                        this.addBlockEntity(coords, this.generateBlockEntity(state.getName(), coords));
                    }
                }
            }
        }
    }

    private boolean containsBlockEntities(Palette p) {
        BlockEntityRegistry blockEntities = RegistryManager.getInstance().getBlockEntityRegistry();
        for (SpecificTag tag : p.toNbt()) {
            if (blockEntities.isBlockEntity(tag.get("Name").stringValue())) {
                return true;
            }
        }
        return false;
    }




    @Override
    public PacketBuilder toLightPacket() {
        return null;
    }

    @Override
    protected void parseBlockEntities(DataTypeProvider dataProvider) {
        int blockEntityCount = dataProvider.readVarInt();
        for (int i = 0; i < blockEntityCount; i++) {
            byte xz = dataProvider.readNext();
            int x = xz >> 4;
            int z = xz & 0b1111;
            int y = dataProvider.readShort();
            int type = dataProvider.readVarInt();

            // Get the exact coordinates in the world
            x = (this.getLocation().getX() * 16) + x;
            z = (this.getLocation().getZ() * 16) + z;

            SpecificTag tag = dataProvider.readNbtTag();
            if (tag instanceof CompoundTag entity) {
                String blockEntityID = RegistryManager.getInstance().getBlockEntityRegistry().getBlockEntityName(type);

                entity.add("id", new StringTag(blockEntityID));
                addBlockEntity(new Coordinate3D(x, y, z), entity);
            }
        }
    }

    /**
     * Convert this chunk to NBT tags.
     *
     * @return the nbt root tag
     */
    public NamedTag toNbt() {
        if (!hasSections()) {
            return null;
        }

        CompoundTag root = new CompoundTag();

        addLevelNbtTags(root);
        root.add("DataVersion", new IntTag(getDataVersion()));

        return new NamedTag("", root);
    }

    @Override
    protected void addLevelNbtTags(CompoundTag map) {
        addGeneralLevelTags(map);
        map.add("yPos", new IntTag(getMinBlockSection()));

        map.add("Heightmaps", heightMap);
        map.add("Status", new StringTag("full"));

        CompoundTag structures = new CompoundTag();
        structures.add("References", new CompoundTag());
        structures.add("Starts", new CompoundTag());
        map.add("Structures", structures);

        map.add("sections", new ListTag(Tag.TAG_COMPOUND, getSectionList()));

        addBlockEntities(map);
    }


}
