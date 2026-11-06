/*
 * Copyright (c) 2026.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.graphics;

import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.draw.elements.Circuit;
import org.junit.Test;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImageSequenceTest {

    @Test
    public void decodesEmbeddedStillImage() throws Exception {
        ImageSequence sequence = ImageSequence.load("embedded:"
                + Base64.getEncoder().encodeToString(png(Color.RED)));

        assertFalse(sequence.isAnimated());
        assertEquals(2, sequence.getWidth());
        assertEquals(1, sequence.getHeight());
        assertEquals(Color.RED.getRGB(), sequence.getCurrentFrame().getRGB(0, 0));
    }

    @Test
    public void decodesAnimatedGifFrames() throws Exception {
        ImageSequence sequence = ImageSequence.load("embedded:"
                + Base64.getEncoder().encodeToString(gif()));

        assertTrue(sequence.isAnimated());
        assertEquals(Color.RED.getRGB(), sequence.getFrameAt(0).getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), sequence.getFrameAt(100_000_000L).getRGB(0, 0));
    }

    @Test
    public void decodesAnimatedPngFrames() throws Exception {
        ImageSequence sequence = ImageSequence.load("embedded:"
                + Base64.getEncoder().encodeToString(apng()));

        assertTrue(sequence.isAnimated());
        assertEquals(Color.RED.getRGB(), sequence.getFrameAt(0).getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), sequence.getFrameAt(100_000_000L).getRGB(0, 0));
    }

    @Test
    public void svgExportEmbedsImages() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        image.setRGB(1, 0, Color.GREEN.getRGB());
        try (GraphicSVG graphic = new GraphicSVG(output, null, 1)) {
            graphic.setBoundingBox(new Vector(0, 0), new Vector(20, 10));
            graphic.drawImage(new Vector(0, 0), new Vector(20, 0), new Vector(0, 10), image);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8.name()).contains("data:image/png;base64,"));
    }

    @Test
    public void embeddedImageSourceSurvivesCircuitSerialization() {
        String source = "embedded:" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        ElementAttributes attributes = new ElementAttributes().set(Keys.IMAGE_SOURCE, source);
        String xml = Circuit.getxStream().toXML(attributes);
        ElementAttributes restored = (ElementAttributes) Circuit.getxStream().fromXML(xml);

        assertEquals(source, restored.get(Keys.IMAGE_SOURCE));
    }

    private static byte[] png(Color color) throws IOException {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color.getRGB());
        image.setRGB(1, 0, color.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] gif() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            writeGifFrame(writer, Color.RED);
            writeGifFrame(writer, Color.BLUE);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void writeGifFrame(ImageWriter writer, Color color) throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color.getRGB());
        image.setRGB(1, 0, color.getRGB());
        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), writer.getDefaultWriteParam());
        String format = metadata.getNativeMetadataFormatName();
        Node root = metadata.getAsTree(format);
        IIOMetadataNode control = (IIOMetadataNode) findNode(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", "10");
        control.setAttribute("transparentColorIndex", "0");
        metadata.setFromTree(format, root);
        writer.writeToSequence(new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
    }

    private static Node findNode(Node node, String name) {
        if (name.equals(node.getNodeName()))
            return node;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node match = findNode(child, name);
            if (match != null)
                return match;
        }
        return null;
    }

    private static byte[] apng() throws IOException {
        List<byte[]> firstFrame = pngChunks(png(Color.RED));
        List<byte[]> secondFrame = pngChunks(png(Color.BLUE));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(output);
        stream.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        writeChunk(output, "IHDR", firstFrame.get(0));
        writeChunk(output, "acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array());
        writeChunk(output, "fcTL", frameControl(0));
        writeChunk(output, "IDAT", firstFrame.get(1));
        writeChunk(output, "fcTL", frameControl(1));
        ByteArrayOutputStream frameData = new ByteArrayOutputStream();
        DataOutputStream frameStream = new DataOutputStream(frameData);
        frameStream.writeInt(2);
        frameStream.write(secondFrame.get(1));
        writeChunk(output, "fdAT", frameData.toByteArray());
        writeChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static byte[] frameControl(int sequence) {
        return ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
                .putInt(sequence).putInt(2).putInt(1).putInt(0).putInt(0)
                .putShort((short) 1).putShort((short) 10).put((byte) 0).put((byte) 0).array();
    }

    private static List<byte[]> pngChunks(byte[] png) {
        ArrayList<byte[]> chunks = new ArrayList<>();
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = ByteBuffer.wrap(png, offset, 4).getInt();
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if ("IHDR".equals(type) || "IDAT".equals(type)) {
                byte[] data = new byte[length];
                System.arraycopy(png, offset + 8, data, 0, length);
                chunks.add(data);
            }
            offset += length + 12;
        }
        return chunks;
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(output);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        stream.writeInt(data.length);
        stream.write(typeBytes);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
    }
}
