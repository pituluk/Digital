/*
 * Copyright (c) 2026.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components;

import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.shapes.ImageShape;

import javax.swing.Timer;

/**
 * Repaints a circuit component while it contains animated image decorations.
 */
final class ImageAnimationTimer {
    private final CircuitComponent component;
    private final Timer timer;

    ImageAnimationTimer(CircuitComponent component) {
        this.component = component;
        timer = new Timer(40, e -> {
            if (component.isShowing())
                component.graphicHasChanged();
            else
                ((Timer) e.getSource()).stop();
        });
    }

    void update() {
        boolean hasAnimatedImage = false;
        for (VisualElement element : component.getCircuitOrShallowCopy().getElements()) {
            if (element.getShape() instanceof ImageShape && ((ImageShape) element.getShape()).isAnimated()) {
                hasAnimatedImage = true;
                break;
            }
        }
        if (hasAnimatedImage && component.isShowing()) {
            if (!timer.isRunning())
                timer.start();
        } else {
            timer.stop();
        }
    }
}
