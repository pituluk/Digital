/*
 * Copyright (c) 2026.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.shapes;

import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.element.PinDescriptions;
import de.neemann.digital.draw.elements.IOState;
import de.neemann.digital.draw.elements.Pins;
import de.neemann.digital.draw.graphics.*;
import de.neemann.digital.lang.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static de.neemann.digital.draw.shapes.GenericShape.SIZE;

/**
 * Non-functional image decoration.
 */
public class ImageShape implements Shape {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageShape.class);
    private final String source;
    private final int width;
    private final int height;
    private final ImageSequence imageSequence;
    private final boolean failed;

    /**
     * Creates an image decoration.
     *
     * @param attr    the attributes
     * @param inputs  the inputs
     * @param outputs the outputs
     */
    public ImageShape(ElementAttributes attr, PinDescriptions inputs, PinDescriptions outputs) {
        source = attr.get(Keys.IMAGE_SOURCE);
        width = attr.get(Keys.IMAGE_WIDTH) * SIZE;
        height = attr.get(Keys.IMAGE_HEIGHT) * SIZE;
        ImageSequence sequence = null;
        boolean loadFailed = false;
        if (!source.isEmpty()) {
            try {
                sequence = ImageSequence.load(source);
            } catch (IOException e) {
                LOGGER.error("Unable to load image decoration", e);
                loadFailed = true;
            }
        }
        imageSequence = sequence;
        failed = loadFailed;
    }

    /**
     * @return true if this image has multiple animation frames
     */
    public boolean isAnimated() {
        return imageSequence != null && imageSequence.isAnimated();
    }

    @Override
    public Pins getPins() {
        return new Pins();
    }

    @Override
    public Interactor applyStateMonitor(IOState ioState) {
        return null;
    }

    @Override
    public void drawTo(Graphic graphic, Style highLight) {
        if (imageSequence == null) {
            drawPlaceholder(graphic);
            return;
        }
        BufferedImage frame = imageSequence.getFrameAtTime(graphic.getAnimationTimeNanos());
        graphic.drawImage(new Vector(0, 0), new Vector(width, 0), new Vector(0, height), frame);
    }

    private void drawPlaceholder(Graphic graphic) {
        Vector topLeft = new Vector(0, 0);
        Vector topRight = new Vector(width, 0);
        Vector bottomRight = new Vector(width, height);
        Vector bottomLeft = new Vector(0, height);
        graphic.drawPolygon(new Polygon(true)
                .add(topLeft)
                .add(topRight)
                .add(bottomRight)
                .add(bottomLeft), Style.DASH);
        if (failed) {
            graphic.drawLine(topLeft, bottomRight, Style.DASH);
            graphic.drawLine(topRight, bottomLeft, Style.DASH);
            graphic.drawText(new Vector(SIZE / 2, SIZE / 2), Lang.get("msg_imageLoadFailed"),
                    Orientation.LEFTTOP, Style.NORMAL.deriveFontStyle(10, true));
        } else {
            graphic.drawText(new Vector(SIZE / 2, SIZE / 2), Lang.get("elem_Image"),
                    Orientation.LEFTTOP, Style.NORMAL.deriveFontStyle(10, true));
        }
    }
}
