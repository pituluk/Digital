/*
 * Copyright (c) 2026
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.graphics;

import junit.framework.TestCase;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

public class GraphicSwingTest extends TestCase {

    public void testIsVisibleUsesGraphicsClip() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setClip(20, 20, 20, 20);
            GraphicSwing graphic = new GraphicSwing(graphics);

            assertTrue(graphic.isVisible(new Vector(25, 25), new Vector(30, 30)));
            assertTrue(graphic.isVisible(new Vector(40, 25), new Vector(45, 30)));
            assertFalse(graphic.isVisible(new Vector(41, 25), new Vector(45, 30)));
        } finally {
            graphics.dispose();
        }
    }

    public void testIsVisibleUsesUserSpaceAfterTransform() {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setTransform(AffineTransform.getScaleInstance(2, 2));
            graphics.setClip(0, 0, 50, 50);
            GraphicSwing graphic = new GraphicSwing(graphics);

            assertTrue(graphic.isVisible(new Vector(40, 40), new Vector(50, 50)));
            assertFalse(graphic.isVisible(new Vector(51, 10), new Vector(60, 20)));
        } finally {
            graphics.dispose();
        }
    }
}
