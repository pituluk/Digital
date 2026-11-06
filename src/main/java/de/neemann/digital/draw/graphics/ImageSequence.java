/*
 * Copyright (c) 2026.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.graphics;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Decodes still images, animated GIFs, and APNGs for workspace decorations.
 */
public final class ImageSequence {
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };

    private final List<Frame> frames;
    private final long totalDurationNanos;
    private final long startTime = System.nanoTime();

    private ImageSequence(List<Frame> frames) {
        this.frames = frames;
        long duration = 0;
        for (Frame frame : frames)
            duration += frame.delayNanos;
        totalDurationNanos = duration;
    }

    /**
     * Loads an image source stored as embedded Base64 data or a file path.
     *
     * @param source the image source
     * @return the decoded frames
     * @throws IOException if the source cannot be read or decoded
     */
    public static ImageSequence load(String source) throws IOException {
        if (source.startsWith("embedded:")) {
            try {
                return load(Base64.getDecoder().decode(source.substring("embedded:".length())));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid embedded image data", e);
            }
        }
        if (source.startsWith("file:")) {
            return load(Files.readAllBytes(new File(source.substring("file:".length())).toPath()));
        }
        throw new IOException("Unknown image source");
    }

    private static ImageSequence load(byte[] bytes) throws IOException {
        if (isApng(bytes))
            return readApng(bytes);
        if (isGif(bytes))
            return readGif(bytes);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null)
            throw new IOException("Unsupported or invalid image");
        return new ImageSequence(Collections.singletonList(new Frame(image, 0)));
    }

    /**
     * @return true if this source contains more than one animation frame
     */
    public boolean isAnimated() {
        return frames.size() > 1;
    }

    /**
     * @return the image width in pixels
     */
    public int getWidth() {
        return frames.get(0).image.getWidth();
    }

    /**
     * @return the image height in pixels
     */
    public int getHeight() {
        return frames.get(0).image.getHeight();
    }

    /**
     * Returns the frame visible at the current animation time.
     *
     * @return the current image frame
     */
    public BufferedImage getCurrentFrame() {
        return getFrameAt(System.nanoTime() - startTime);
    }

    /**
     * Returns the frame visible at the given monotonic time.
     *
     * @param timeNanos the animation time
     * @return the image frame
     */
    public BufferedImage getFrameAtTime(long timeNanos) {
        return getFrameAt(timeNanos - startTime);
    }

    BufferedImage getFrameAt(long elapsedNanos) {
        if (frames.size() == 1 || totalDurationNanos <= 0)
            return frames.get(0).image;

        long elapsed = elapsedNanos % totalDurationNanos;
        if (elapsed < 0)
            elapsed += totalDurationNanos;
        for (Frame frame : frames) {
            if (elapsed < frame.delayNanos)
                return frame.image;
            elapsed -= frame.delayNanos;
        }
        return frames.get(frames.size() - 1).image;
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length >= 6
                && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
    }

    private static boolean isApng(byte[] bytes) throws IOException {
        if (bytes.length < PNG_SIGNATURE.length
                || !matches(bytes, 0, PNG_SIGNATURE))
            return false;
        int offset = PNG_SIGNATURE.length;
        while (offset + 12 <= bytes.length) {
            int length = readInt(bytes, offset);
            if (length < 0 || (long) offset + 12 + length > bytes.length)
                throw new IOException("Invalid PNG chunk length");
            if (matches(bytes, offset + 4, new byte[]{'a', 'c', 'T', 'L'}))
                return true;
            offset += 12 + length;
            if (matches(bytes, offset - length - 8, new byte[]{'I', 'E', 'N', 'D'}))
                break;
        }
        return false;
    }

    private static ImageSequence readGif(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
                throw new IOException("No GIF image reader available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count = reader.getNumImages(true);
                int canvasWidth = 0;
                int canvasHeight = 0;
                IIOMetadata streamMetadata = reader.getStreamMetadata();
                if (streamMetadata != null) {
                    org.w3c.dom.Node root = streamMetadata.getAsTree("javax_imageio_gif_stream_1.0");
                    org.w3c.dom.Node screen = findNode(root, "LogicalScreenDescriptor");
                    if (screen != null) {
                        canvasWidth = intAttribute(screen, "logicalScreenWidth", 0);
                        canvasHeight = intAttribute(screen, "logicalScreenHeight", 0);
                    }
                }

                ArrayList<Frame> frames = new ArrayList<>();
                BufferedImage canvas = null;
                GifControl previous = null;
                BufferedImage previousCanvas = null;
                for (int i = 0; i < count; i++) {
                    BufferedImage frameImage = reader.read(i);
                    IIOMetadata metadata = reader.getImageMetadata(i);
                    org.w3c.dom.Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
                    org.w3c.dom.Node descriptor = findNode(root, "ImageDescriptor");
                    org.w3c.dom.Node control = findNode(root, "GraphicControlExtension");
                    int x = intAttribute(descriptor, "imageLeftPosition", 0);
                    int y = intAttribute(descriptor, "imageTopPosition", 0);
                    if (canvasWidth == 0)
                        canvasWidth = Math.max(x + frameImage.getWidth(), frameImage.getWidth());
                    if (canvasHeight == 0)
                        canvasHeight = Math.max(y + frameImage.getHeight(), frameImage.getHeight());
                    if (canvas == null)
                        canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);

                    if (previous != null) {
                        if ("restoreToBackgroundColor".equals(previous.disposal))
                            clear(canvas, previous.x, previous.y, previous.width, previous.height);
                        else if ("restoreToPrevious".equals(previous.disposal) && previousCanvas != null)
                            canvas = copy(previousCanvas);
                    }

                    String disposal = stringAttribute(control, "disposalMethod", "none");
                    int width = intAttribute(descriptor, "imageWidth", frameImage.getWidth());
                    int height = intAttribute(descriptor, "imageHeight", frameImage.getHeight());
                    GifControl current = new GifControl(x, y, width, height, disposal);
                    previousCanvas = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;

                    Graphics2D graphics = canvas.createGraphics();
                    graphics.setComposite(AlphaComposite.SrcOver);
                    if (frameImage.getWidth() == canvasWidth && frameImage.getHeight() == canvasHeight)
                        graphics.drawImage(frameImage, 0, 0, null);
                    else
                        graphics.drawImage(frameImage, x, y, null);
                    graphics.dispose();

                    int delayMillis = Math.max(10, intAttribute(control, "delayTime", 10) * 10);
                    frames.add(new Frame(copy(canvas), delayMillis * 1_000_000L));
                    previous = current;
                }
                if (frames.isEmpty())
                    throw new IOException("GIF contains no frames");
                return new ImageSequence(frames);
            } finally {
                reader.dispose();
            }
        }
    }

    private static ImageSequence readApng(byte[] bytes) throws IOException {
        if (!matches(bytes, 0, PNG_SIGNATURE))
            throw new IOException("Invalid PNG signature");

        byte[] header = null;
        ArrayList<byte[]> commonChunks = new ArrayList<>();
        ArrayList<ApngFrameData> frameData = new ArrayList<>();
        ApngFrameData current = null;
        int canvasWidth = 0;
        int canvasHeight = 0;
        boolean imageDataStarted = false;
        int offset = PNG_SIGNATURE.length;
        while (offset + 12 <= bytes.length) {
            int length = readInt(bytes, offset);
            if (length < 0 || (long) offset + 12 + length > bytes.length)
                throw new IOException("Invalid PNG chunk length");
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int dataOffset = offset + 8;
            byte[] data = new byte[length];
            System.arraycopy(bytes, dataOffset, data, 0, length);

            if ("IHDR".equals(type)) {
                header = data;
                canvasWidth = readInt(data, 0);
                canvasHeight = readInt(data, 4);
            } else if ("fcTL".equals(type)) {
                if (current != null)
                    frameData.add(current);
                if (length != 26)
                    throw new IOException("Invalid APNG frame control");
                current = new ApngFrameData(data);
            } else if ("IDAT".equals(type)) {
                imageDataStarted = true;
                if (current != null)
                    current.compressedData.add(data);
            } else if ("fdAT".equals(type)) {
                if (current == null || length < 4)
                    throw new IOException("Invalid APNG frame data");
                byte[] compressed = new byte[length - 4];
                System.arraycopy(data, 4, compressed, 0, compressed.length);
                current.compressedData.add(compressed);
            } else if ("IEND".equals(type)) {
                if (current != null)
                    frameData.add(current);
                break;
            } else if (!imageDataStarted && !"acTL".equals(type)) {
                commonChunks.add(makeChunk(type, data));
            }
            offset += length + 12;
        }
        if (header == null || frameData.isEmpty())
            throw new IOException("APNG contains no animation frames");

        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        ArrayList<Frame> frames = new ArrayList<>();
        ApngFrameData previous = null;
        BufferedImage previousCanvas = null;
        for (ApngFrameData frame : frameData) {
            if (frame.width <= 0 || frame.height <= 0
                    || frame.x < 0 || frame.y < 0
                    || frame.x + frame.width > canvasWidth || frame.y + frame.height > canvasHeight)
                throw new IOException("APNG frame is outside the image bounds");
            if (previous != null) {
                if (previous.dispose == 1)
                    clear(canvas, previous.x, previous.y, previous.width, previous.height);
                else if (previous.dispose == 2 && previousCanvas != null)
                    canvas = copy(previousCanvas);
            }

            BufferedImage image = decodeApngFrame(header, commonChunks, frame);
            previousCanvas = frame.dispose == 2 ? copy(canvas) : null;
            Graphics2D graphics = canvas.createGraphics();
            graphics.setComposite(frame.blend == 0 ? AlphaComposite.Src : AlphaComposite.SrcOver);
            graphics.drawImage(image, frame.x, frame.y, null);
            graphics.dispose();

            int denominator = frame.delayDenominator == 0 ? 100 : frame.delayDenominator;
            long delayNanos = Math.max(10_000_000L,
                    (long) frame.delayNumerator * 1_000_000_000L / denominator);
            frames.add(new Frame(copy(canvas), delayNanos));
            previous = frame;
        }
        return new ImageSequence(frames);
    }

    private static BufferedImage decodeApngFrame(byte[] header, List<byte[]> commonChunks, ApngFrameData frame) throws IOException {
        byte[] frameHeader = header.clone();
        writeInt(frameHeader, 0, frame.width);
        writeInt(frameHeader, 4, frame.height);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(PNG_SIGNATURE);
        writeChunk(output, "IHDR", frameHeader);
        for (byte[] chunk : commonChunks)
            output.write(chunk);
        for (byte[] compressed : frame.compressedData)
            writeChunk(output, "IDAT", compressed);
        writeChunk(output, "IEND", new byte[0]);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(output.toByteArray()));
        if (image == null)
            throw new IOException("Could not decode APNG frame");
        return image;
    }

    private static byte[] makeChunk(String type, byte[] data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeChunk(output, type, data);
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(data.length);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        stream.write(typeBytes);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
    }

    private static int readInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static boolean matches(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > bytes.length)
            return false;
        for (int i = 0; i < expected.length; i++)
            if (bytes[offset + i] != expected[i])
                return false;
        return true;
    }

    private static org.w3c.dom.Node findNode(org.w3c.dom.Node node, String name) {
        if (node == null)
            return null;
        if (name.equals(node.getNodeName()))
            return node;
        for (org.w3c.dom.Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            org.w3c.dom.Node result = findNode(child, name);
            if (result != null)
                return result;
        }
        return null;
    }

    private static int intAttribute(org.w3c.dom.Node node, String name, int defaultValue) {
        if (node == null || node.getAttributes() == null || node.getAttributes().getNamedItem(name) == null)
            return defaultValue;
        return Integer.parseInt(node.getAttributes().getNamedItem(name).getNodeValue());
    }

    private static String stringAttribute(org.w3c.dom.Node node, String name, String defaultValue) {
        if (node == null || node.getAttributes() == null || node.getAttributes().getNamedItem(name) == null)
            return defaultValue;
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }

    private static void clear(BufferedImage image, int x, int y, int width, int height) {
        Graphics2D graphics = image.createGraphics();
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(x, y, width, height);
        graphics.dispose();
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static final class Frame {
        private final BufferedImage image;
        private final long delayNanos;

        private Frame(BufferedImage image, long delayNanos) {
            this.image = image;
            this.delayNanos = delayNanos;
        }
    }

    private static final class GifControl {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String disposal;

        private GifControl(int x, int y, int width, int height, String disposal) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.disposal = disposal;
        }
    }

    private static final class ApngFrameData {
        private final int width;
        private final int height;
        private final int x;
        private final int y;
        private final int delayNumerator;
        private final int delayDenominator;
        private final int dispose;
        private final int blend;
        private final ArrayList<byte[]> compressedData = new ArrayList<>();

        private ApngFrameData(byte[] data) {
            width = readInt(data, 4);
            height = readInt(data, 8);
            x = readInt(data, 12);
            y = readInt(data, 16);
            delayNumerator = unsignedShort(data, 20);
            delayDenominator = unsignedShort(data, 22);
            dispose = data[24] & 0xff;
            blend = data[25] & 0xff;
        }
    }
}
