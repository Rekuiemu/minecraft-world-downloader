package game.data.chunk;

import com.flowpowered.nbt.Tag;
import com.flowpowered.nbt.stream.NBTOutputStream;

import game.data.region.McaFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Binary (raw NBT) version of a chunk.
 */
public class ChunkBinary {
    private final static byte COMPRESSION_TYPE = 1;
    private int timestamp;
    private int location;
    private int size;
    private byte[] chunkData;

    private ChunkBinary() {
        this.timestamp = 0;
        this.location = -1;
    }

    public ChunkBinary(int timestamp, int location, int size, byte[] chunkData) {
        this.timestamp = timestamp;
        this.location = location;
        this.size = size;
        this.chunkData = chunkData;
    }

    /**
     * Convert a chunk to a ChunkBinary object.
     * @param chunk the chunk
     * @return the binary version of the chunk
     */
    public static ChunkBinary fromChunk(Chunk chunk) throws IOException {
        Tag nbt = chunk.toNbt();

        if (nbt == null) {
            return null;
        }

        ChunkBinary binary = new ChunkBinary();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NBTOutputStream outputStream = new NBTOutputStream(output, true);

        outputStream.writeTag(nbt);
        outputStream.close();
        byte[] data = output.toByteArray();

        byte[] finalData = new byte[data.length + 5];
        int lengthToWrite = data.length + 1;
        finalData[0] = (byte) (lengthToWrite >>> 24);
        finalData[1] = (byte) (lengthToWrite >>> 16);
        finalData[2] = (byte) (lengthToWrite >>> 8);
        finalData[3] = (byte) (lengthToWrite);
        finalData[4] = COMPRESSION_TYPE;

        System.arraycopy(data, 0, finalData, 5, data.length);

        int fullsize = finalData.length + 5;
        binary.size = fullsize / McaFile.SECTOR_SIZE + (fullsize % McaFile.SECTOR_SIZE == 0 ? 0 : 1);

        binary.chunkData = finalData;

        return binary;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(int timestamp) {
        this.timestamp = timestamp;
    }

    public int getLocation() {
        return location;
    }

    public void setLocation(int location) {
        this.location = location;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public byte[] getChunkData() {
        return chunkData;
    }

    public void setChunkData(byte[] chunkData) {
        this.chunkData = chunkData;
    }
}
