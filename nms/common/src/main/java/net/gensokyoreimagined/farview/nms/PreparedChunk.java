package net.gensokyoreimagined.farview.nms;

public record PreparedChunk(byte[] data, int[] pieces, short[] faces) {
    public int weight() {
        return data.length + pieces.length * 4 + (faces == null ? 0 : faces.length * 2);
    }
}
