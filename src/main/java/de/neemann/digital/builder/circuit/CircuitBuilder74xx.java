/*
 * Copyright (c) 2026 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.builder.circuit;

import de.neemann.digital.analyse.ModelAnalyserInfo;
import de.neemann.digital.analyse.expression.Constant;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.Not;
import de.neemann.digital.analyse.expression.Operation;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.VariableVisitor;
import de.neemann.digital.builder.BuilderException;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.element.Rotation;
import de.neemann.digital.core.flipflops.FlipflopD;
import de.neemann.digital.core.io.DipSwitch;
import de.neemann.digital.core.io.Ground;
import de.neemann.digital.core.io.In;
import de.neemann.digital.core.io.Out;
import de.neemann.digital.core.io.VDD;
import de.neemann.digital.core.wiring.Clock;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Pin;
import de.neemann.digital.draw.elements.Tunnel;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.draw.graphics.GraphicMinMax;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static de.neemann.digital.draw.shapes.GenericShape.SIZE;

/**
 * Circuit builder that maps generated expressions onto 74xx DIL chips.
 * Gate pins are allocated to reduce estimated net length, then orthogonal fixed-spacing side channels
 * route distinctly colored wires. Bridges cross in inter-chip gaps, and unused inputs are tied to Ground.
 */
public class CircuitBuilder74xx {

    private static final int BASE_CHIP_PITCH_Y = 17 * SIZE;
    private static final int ROUTE_MARGIN = 24 * SIZE;
    private static final int ROUTE_BEND_COST = 4;

    private static final Color[] NET_COLORS = new Color[]{
            new Color(30, 144, 255),  // Dodger Blue
            new Color(255, 127, 0),   // Vibrant Orange
            new Color(46, 204, 113),  // Emerald Green
            new Color(155, 89, 182),  // Amethyst Purple
            new Color(231, 76, 60),   // Coral Red
            new Color(241, 196, 15),  // Sunflower Yellow
            new Color(26, 188, 156),  // Turquoise / Cyan
            new Color(233, 30, 99),   // Deep Pink
            new Color(52, 152, 219),  // Sky Blue
            new Color(142, 68, 173),  // Dark Purple
            new Color(230, 126, 34),  // Carrot Orange
            new Color(39, 174, 96),   // Nephritis Green
            new Color(211, 84, 0),    // Pumpkin
            new Color(0, 188, 212),   // Bright Cyan
            new Color(124, 179, 66),  // Light Olive Green
            new Color(171, 71, 188)   // Orchid
    };

    private final ShapeFactory shapeFactory;
    private final Collection<Variable> declaredVars;
    private final Map<String, Expression> combinatorialOutputs;
    private final Map<String, Expression> sequentialOutputs;
    private final boolean useSwitchesAndLeds;
    private final ModelAnalyserInfo mai;

    private final List<ChipInstance> chipInstances = new ArrayList<>();
    private final Map<ChipInstance, ChipGeometry> chipGeometry = new LinkedHashMap<>();
    private final Map<String, Vector> inputPositions = new LinkedHashMap<>();
    private final Set<RoutePoint> fixedRoutePoints = new HashSet<>();
    private final Map<RoutePoint, Map<String, Integer>> occupiedTrackDirections = new HashMap<>();
    private String currentRouteNet;
    private int chipCounter = 0;
    private int netCounter = 0;
    private boolean const1Used = false;
    private boolean const0Used = false;

    private enum PinSide {
        INPUT, CHIP_LEFT, CHIP_RIGHT, CHIP_TOP, CHIP_BOTTOM, OUTPUT
    }

    private static class Endpoint {
        private final PinSide side;
        private final Vector position;
        private final Vector normal;
        private final int y;
        private final ChipInstance chip;
        private final boolean output;

        Endpoint(PinSide side, Vector position, Vector normal) {
            this(side, position, normal, null, false);
        }

        Endpoint(PinSide side, Vector position, Vector normal, ChipInstance chip, boolean output) {
            this.side = side;
            this.position = position;
            this.normal = normal;
            this.y = position.y;
            this.chip = chip;
            this.output = output;
        }
    }

    private static class ChipGeometry {
        private final Vector min;
        private final Vector max;
        private final List<Vector> pinPositions;

        ChipGeometry(Vector min, Vector max, List<Vector> pinPositions) {
            this.min = min;
            this.max = max;
            this.pinPositions = pinPositions;
        }
    }

    private static class RoutePoint {
        private final int x;
        private final int y;

        RoutePoint(int x, int y) {
            this.x = x;
            this.y = y;
        }

        RoutePoint add(int dx, int dy) {
            return new RoutePoint(x + dx, y + dy);
        }

        Vector toVector() {
            return new Vector(x, y);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RoutePoint)) {
                return false;
            }
            RoutePoint point = (RoutePoint) other;
            return x == point.x && y == point.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    private static class RouteSearchNode implements Comparable<RouteSearchNode> {
        private final RoutePoint point;
        private final int direction;
        private final int cost;
        private final int estimate;
        private final RouteSearchNode previous;

        RouteSearchNode(RoutePoint point, int direction, int cost, int estimate,
                        RouteSearchNode previous) {
            this.point = point;
            this.direction = direction;
            this.cost = cost;
            this.estimate = estimate;
            this.previous = previous;
        }

        @Override
        public int compareTo(RouteSearchNode other) {
            return Integer.compare(cost + estimate, other.cost + other.estimate);
        }
    }

    private static class LocalConnection {
        private final String netName;
        private final Endpoint source;
        private final Endpoint target;
        private int track;

        LocalConnection(String netName, Endpoint source, Endpoint target) {
            this.netName = netName;
            this.source = source;
            this.target = target;
        }
    }

    private static class NetRoute {
        private final List<Endpoint> leftEndpoints = new ArrayList<>();
        private final List<Endpoint> rightEndpoints = new ArrayList<>();
    }

    private static class RoutingLayout {
        private final int xInput;
        private final int xChip;
        private final int xChipRight;
        private final int xOutput;
        private final int xLeftTrackStart;
        private final int xRightTrackStart;
        private int trackPitch = 2 * SIZE;
        private int chipPitchY = 24 * SIZE;
        private int numChips;
        private Map<String, Integer> directLeftOutputTracks;
        private Set<String> directChipNets;

        RoutingLayout(int xInput, int xChip, int xChipRight, int xOutput,
                      int xLeftTrackStart, int xRightTrackStart) {
            this.xInput = xInput;
            this.xChip = xChip;
            this.xChipRight = xChipRight;
            this.xOutput = xOutput;
            this.xLeftTrackStart = xLeftTrackStart;
            this.xRightTrackStart = xRightTrackStart;
        }

        RoutingLayout setGeometry(int trackPitch, int chipPitchY, int numChips) {
            this.trackPitch = trackPitch;
            this.chipPitchY = chipPitchY;
            this.numChips = numChips;
            return this;
        }

        RoutingLayout setDirectLeftOutputTracks(Map<String, Integer> directLeftOutputTracks) {
            this.directLeftOutputTracks = directLeftOutputTracks;
            return this;
        }

        RoutingLayout setDirectChipNets(Set<String> directChipNets) {
            this.directChipNets = directChipNets;
            return this;
        }
    }

    private List<Endpoint> getExternallyRoutedEndpoints(List<Endpoint> endpoints, Set<Endpoint> localEndpoints) {
        List<Endpoint> external = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (!localEndpoints.contains(endpoint)) {
                external.add(endpoint);
            }
        }
        return external;
    }

    /**
     * Creates a new 74xx circuit builder.
     *
     * @param shapeFactory         the shape factory
     * @param vars                 the declared variables
     * @param combinatorialOutputs the combinatorial output expressions
     * @param sequentialOutputs   the sequential output expressions
     * @param useSwitchesAndLeds   true if switches and LEDs should be used
     * @param mai                  model analyzer info
     */
    public CircuitBuilder74xx(ShapeFactory shapeFactory,
                              Collection<Variable> vars,
                              Map<String, Expression> combinatorialOutputs,
                              Map<String, Expression> sequentialOutputs,
                              boolean useSwitchesAndLeds,
                              ModelAnalyserInfo mai) {
        this.shapeFactory = shapeFactory;
        this.declaredVars = vars;
        this.combinatorialOutputs = combinatorialOutputs;
        this.sequentialOutputs = sequentialOutputs;
        this.useSwitchesAndLeds = useSwitchesAndLeds;
        this.mai = mai;
    }

    /**
     * Builds the circuit.
     *
     * @return the created circuit
     * @throws BuilderException on builder error
     */
    public Circuit createCircuit() throws BuilderException {
        Circuit circuit = new Circuit();

        LinkedHashMap<String, String> combResultNets = new LinkedHashMap<>();
        for (Map.Entry<String, Expression> entry : combinatorialOutputs.entrySet()) {
            combResultNets.put(entry.getKey(), processExpression(entry.getValue()));
        }

        LinkedHashMap<String, String> seqResultNets = new LinkedHashMap<>();
        for (Map.Entry<String, Expression> entry : sequentialOutputs.entrySet()) {
            seqResultNets.put(entry.getKey(), processExpression(entry.getValue()));
        }

        LinkedHashSet<String> inputs = findInputVariables();
        orderChipsByConnectivity(inputs);
        padUnusedGateInputs();
        optimizePinAllocation(inputs);

        Map<String, NetRoute> inputRoutes = new LinkedHashMap<>();
        inputPositions.clear();
        chipGeometry.clear();
        fixedRoutePoints.clear();
        occupiedTrackDirections.clear();

        int xInput = 0;
        int yInput = placeInputs(circuit, inputs, inputRoutes, xInput);
        Map<String, NetRoute> netRoutes = copyRoutes(inputRoutes);
        List<VisualElement> chipVisualElements = prepareChips(netRoutes);
        Map<String, Integer> outputYMap = calculateOutputPositions(combResultNets, netRoutes);
        int xOutput = alignToGrid(getChipLayoutMaxX() + 12 * SIZE);
        registerOutputEndpoints(combResultNets, netRoutes, outputYMap, xOutput);

        placeChipsInCircuit(circuit, chipVisualElements);
        int yOutput = placeOutputsInCircuit(circuit, combResultNets, outputYMap, xOutput);

        placeConstants(circuit, xInput, yInput);
        routeGridNets(circuit, netRoutes, xOutput);

        if (!seqResultNets.isEmpty()) {
            placeSequential(circuit, seqResultNets, xOutput, yOutput);
        }

        return circuit;
    }

    private Map<String, NetRoute> copyRoutes(Map<String, NetRoute> routes) {
        Map<String, NetRoute> copy = new LinkedHashMap<>();
        for (Map.Entry<String, NetRoute> entry : routes.entrySet()) {
            NetRoute routeCopy = new NetRoute();
            routeCopy.leftEndpoints.addAll(entry.getValue().leftEndpoints);
            routeCopy.rightEndpoints.addAll(entry.getValue().rightEndpoints);
            copy.put(entry.getKey(), routeCopy);
        }
        return copy;
    }

    private int calculateRequiredChipPitch(Set<String> bridgedNets, Map<String, NetRoute> netRoutes,
                                           int chipPitchY) {
        Map<Integer, Integer> gapUsage = new HashMap<>();
        int maxPerGap = 0;
        for (String netName : bridgedNets) {
            int gapIdx = findBestGap(computeAverageY(netRoutes.get(netName)),
                    chipInstances.size(), chipPitchY);
            int count = gapUsage.getOrDefault(gapIdx, 0) + 1;
            gapUsage.put(gapIdx, count);
            maxPerGap = Math.max(maxPerGap, count);
        }
        return BASE_CHIP_PITCH_Y + Math.max(0, maxPerGap - 1) * 2 * SIZE;
    }

    private void orderChipsByConnectivity(LinkedHashSet<String> inputs) {
        if (chipInstances.size() < 2) {
            return;
        }

        boolean improved;
        do {
            improved = false;
            long bestCost = calculateChipPlacementCost(inputs);
            int bestFirst = -1;
            int bestSecond = -1;
            for (int first = 0; first < chipInstances.size(); first++) {
                for (int second = first + 1; second < chipInstances.size(); second++) {
                    Collections.swap(chipInstances, first, second);
                    long cost = calculateChipPlacementCost(inputs);
                    Collections.swap(chipInstances, first, second);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestFirst = first;
                        bestSecond = second;
                    }
                }
            }
            if (bestFirst >= 0) {
                Collections.swap(chipInstances, bestFirst, bestSecond);
                improved = true;
            }
        } while (improved);
    }

    private long calculateChipPlacementCost(LinkedHashSet<String> inputs) {
        Map<String, Vector> inputPositions = new HashMap<>();
        int nextInputY = SIZE;
        for (String input : inputs) {
            inputPositions.put(input, new Vector(0, nextInputY));
            nextInputY += 4 * SIZE;
        }

        Map<ChipInstance, Map<Integer, GatePinPositions>> pinPositions = createEstimatedPinPositions();
        Map<String, Vector> sourcePositions = new HashMap<>(inputPositions);
        for (ChipInstance chip : chipInstances) {
            for (Gate gate : chip.allocatedGates) {
                if (gate.outputNet != null) {
                    sourcePositions.put(gate.outputNet, pinPositions.get(chip).get(gate.slot).output);
                }
            }
        }

        long cost = 0;
        for (ChipInstance chip : chipInstances) {
            Map<Integer, GatePinPositions> chipPins = pinPositions.get(chip);
            for (Gate gate : chip.allocatedGates) {
                GatePinPositions pins = chipPins.get(gate.slot);
                for (int pin = 0; pin < gate.inputNets.size(); pin++) {
                    Vector source = sourcePositions.get(gate.inputNets.get(pin));
                    Vector target = pin == 0 ? pins.inputA : pins.inputB;
                    if (source != null && target != null) {
                        cost += manhattanDistance(source, target);
                    }
                }
            }
        }
        return cost;
    }

    private void optimizePinAllocation(LinkedHashSet<String> inputs) {
        Map<String, Vector> inputPositions = new HashMap<>();
        int y = SIZE;
        for (String input : inputs) {
            inputPositions.put(input, new Vector(0, y));
            y += 4 * SIZE;
        }

        Map<ChipInstance, Map<Integer, GatePinPositions>> pinPositions = createEstimatedPinPositions();

        boolean improved;
        do {
            improved = false;
            long currentCost = calculatePinAllocationCost(inputPositions, pinPositions);
            for (ChipInstance chip : chipInstances) {
                List<Gate> gates = chip.allocatedGates;
                for (int i = 0; i < gates.size(); i++) {
                    Gate first = gates.get(i);
                    if (first.inputNets.size() == 2) {
                        Collections.swap(first.inputNets, 0, 1);
                        long swappedCost = calculatePinAllocationCost(inputPositions, pinPositions);
                        if (swappedCost < currentCost) {
                            currentCost = swappedCost;
                            improved = true;
                        } else {
                            Collections.swap(first.inputNets, 0, 1);
                        }
                    }

                    for (int j = i + 1; j < gates.size(); j++) {
                        Gate second = gates.get(j);
                        int slot = first.slot;
                        first.slot = second.slot;
                        second.slot = slot;

                        long swappedCost = calculatePinAllocationCost(inputPositions, pinPositions);
                        if (swappedCost < currentCost) {
                            currentCost = swappedCost;
                            improved = true;
                        } else {
                            slot = first.slot;
                            first.slot = second.slot;
                            second.slot = slot;
                        }
                    }
                }
            }
        } while (improved);
    }

    private long calculatePinAllocationCost(Map<String, Vector> inputPositions,
                                             Map<ChipInstance, Map<Integer, GatePinPositions>> pinPositions) {
        Map<String, Vector> sourcePositions = new HashMap<>(inputPositions);
        for (ChipInstance chip : chipInstances) {
            Map<Integer, GatePinPositions> chipPins = pinPositions.get(chip);
            for (Gate gate : chip.allocatedGates) {
                Vector output = chipPins.get(gate.slot).output;
                if (gate.outputNet != null && output != null) {
                    sourcePositions.put(gate.outputNet, output);
                }
            }
        }

        long cost = 0;
        for (ChipInstance chip : chipInstances) {
            Map<Integer, GatePinPositions> chipPins = pinPositions.get(chip);
            for (Gate gate : chip.allocatedGates) {
                GatePinPositions pins = chipPins.get(gate.slot);
                for (int i = 0; i < gate.inputNets.size(); i++) {
                    String net = gate.inputNets.get(i);
                    Vector source = sourcePositions.get(net);
                    Vector target = i == 0 ? pins.inputA : pins.inputB;
                    if (source != null && target != null) {
                        cost += manhattanDistance(source, target);
                    }
                }
            }
        }
        return cost;
    }

    private Map<ChipInstance, Map<Integer, GatePinPositions>> createEstimatedPinPositions() {
        int cellWidth = 0;
        int cellHeight = 0;
        for (ChipInstance chip : chipInstances) {
            VisualElement measure = new VisualElement(chip.chipFile).setShapeFactory(shapeFactory);
            for (int rotation = 0; rotation < 4; rotation++) {
                measure.setRotation(rotation).setPos(new Vector(0, 0));
                GraphicMinMax bounds = measure.getMinMax(false);
                cellWidth = Math.max(cellWidth, bounds.getMax().x - bounds.getMin().x);
                cellHeight = Math.max(cellHeight, bounds.getMax().y - bounds.getMin().y);
            }
        }
        cellWidth += 8 * SIZE;
        cellHeight += 8 * SIZE;
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(chipInstances.size())));
        Map<ChipInstance, Map<Integer, GatePinPositions>> positions = new LinkedHashMap<>();
        for (int i = 0; i < chipInstances.size(); i++) {
            ChipInstance chip = chipInstances.get(i);
            VisualElement chipVe = new VisualElement(chip.chipFile).setShapeFactory(shapeFactory);
            Vector cellOrigin = new Vector(16 * SIZE + (i % columns) * cellWidth,
                    12 * SIZE + (i / columns) * cellHeight);
            placeChipInCell(chipVe, cellOrigin, 0);
            Map<Integer, GatePinPositions> chipPins = new HashMap<>();
            for (int slot = 1; slot <= chip.maxSlots; slot++) {
                chipPins.put(slot, new GatePinPositions(getWorldPinPosition(chipVe, slot + "A"),
                        getWorldPinPosition(chipVe, slot + "B"),
                        getWorldPinPosition(chipVe, slot + "Y")));
            }
            positions.put(chip, chipPins);
        }
        return positions;
    }

    private long manhattanDistance(Vector first, Vector second) {
        return Math.abs(first.x - second.x) + Math.abs(first.y - second.y);
    }

    private void sortNetsByEndpointY(List<String> nets, Map<String, NetRoute> netRoutes, final boolean leftSide) {
        Collections.sort(nets, new Comparator<String>() {
            @Override
            public int compare(String first, String second) {
                int firstY = averageEndpointY(netRoutes.get(first), leftSide);
                int secondY = averageEndpointY(netRoutes.get(second), leftSide);
                return Integer.compare(firstY, secondY);
            }
        });
    }

    private int averageEndpointY(NetRoute route, boolean leftSide) {
        List<Endpoint> endpoints = leftSide ? route.leftEndpoints : route.rightEndpoints;
        if (endpoints.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (Endpoint endpoint : endpoints) {
            sum += endpoint.y;
        }
        return sum / endpoints.size();
    }

    private void padUnusedGateInputs() {
        for (ChipInstance ci : chipInstances) {
            for (int slot = ci.slotsUsed + 1; slot <= ci.maxSlots; slot++) {
                List<String> inNets = new ArrayList<>();
                inNets.add(getConstantNet(0));
                if (!"7404".equals(ci.chipType)) {
                    inNets.add(getConstantNet(0));
                }
                ci.allocatedGates.add(new Gate(slot, inNets, null));
            }
        }
    }

    private int placeInputs(Circuit circuit, LinkedHashSet<String> inputs,
                            Map<String, NetRoute> netRoutes, int xInput) throws BuilderException {
        int yInput = SIZE;
        for (String inName : inputs) {
            VisualElement inVe;
            if (useSwitchesAndLeds) {
                inVe = new VisualElement(DipSwitch.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            } else {
                inVe = new VisualElement(In.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
                checkPinNumber(inVe, inName);
            }
            inVe.getElementAttributes().set(Keys.LABEL, inName);
            inVe.setPos(new Vector(xInput, yInput));
            circuit.add(inVe);

            if (inVe.getPins().size() == 0) {
                throw new BuilderException("Input element " + inName + " has no output pin");
            }
            Vector position = inVe.getPins().get(0).getPos();
            inputPositions.put(inName, position);
            fixedRoutePoints.add(toRoutePoint(position));
            getRoute(netRoutes, inName).leftEndpoints.add(
                    new Endpoint(PinSide.INPUT, position, new Vector(1, 0)));
            yInput += 4 * SIZE;
        }
        return yInput;
    }

    private List<VisualElement> prepareChips(Map<String, NetRoute> netRoutes) {
        List<VisualElement> chipVisualElements = new ArrayList<>();
        int cellWidth = 0;
        int cellHeight = 0;
        for (ChipInstance chip : chipInstances) {
            VisualElement measure = new VisualElement(chip.chipFile).setShapeFactory(shapeFactory);
            for (int rotation = 0; rotation < 4; rotation++) {
                measure.setRotation(rotation).setPos(new Vector(0, 0));
                GraphicMinMax bounds = measure.getMinMax(false);
                cellWidth = Math.max(cellWidth, bounds.getMax().x - bounds.getMin().x);
                cellHeight = Math.max(cellHeight, bounds.getMax().y - bounds.getMin().y);
            }
        }
        cellWidth += 8 * SIZE;
        cellHeight += 8 * SIZE;
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(chipInstances.size())));
        List<Vector> cellOrigins = new ArrayList<>();
        for (int i = 0; i < chipInstances.size(); i++) {
            ChipInstance ci = chipInstances.get(i);
            int column = i % columns;
            int row = i / columns;
            cellOrigins.add(new Vector(16 * SIZE + column * cellWidth, 12 * SIZE + row * cellHeight));
            VisualElement chipVe = new VisualElement(ci.chipFile).setShapeFactory(shapeFactory);
            chipVe.getElementAttributes().set(Keys.LABEL, "IC" + ci.id + " (" + ci.chipType + ")");
            chipVisualElements.add(chipVe);
            placeChipInCell(chipVe, cellOrigins.get(i), 0);
        }

        optimizeChipRotations(chipVisualElements, cellOrigins);
        for (int i = 0; i < chipInstances.size(); i++) {
            ChipInstance ci = chipInstances.get(i);
            VisualElement chipVe = chipVisualElements.get(i);
            GraphicMinMax bounds = chipVe.getMinMax(false);
            List<Vector> pinPositions = new ArrayList<>();
            for (Pin pin : chipVe.getPins()) {
                pinPositions.add(pin.getPos());
            }
            chipGeometry.put(ci, new ChipGeometry(bounds.getMin(), bounds.getMax(), pinPositions));
            for (Gate g : ci.allocatedGates) {
                int slot = g.slot;
                registerGatePin(netRoutes, chipVe, ci, slot + "A",
                        g.inputNets.size() >= 1 ? g.inputNets.get(0) : null);
                if (!"7404".equals(ci.chipType)) {
                    registerGatePin(netRoutes, chipVe, ci, slot + "B",
                            g.inputNets.size() >= 2 ? g.inputNets.get(1) : null);
                }
                if (g.outputNet != null) {
                    registerGatePin(netRoutes, chipVe, ci, slot + "Y", g.outputNet);
                }
            }
        }
        return chipVisualElements;
    }

    private void placeChipInCell(VisualElement chipVe, Vector cellOrigin, int rotation) {
        chipVe.setRotation(rotation).setPos(new Vector(0, 0));
        Vector min = chipVe.getMinMax(false).getMin();
        chipVe.setPos(new Vector(alignToGrid(cellOrigin.x - min.x),
                alignToGrid(cellOrigin.y - min.y)));
    }

    private void optimizeChipRotations(List<VisualElement> chipVisualElements, List<Vector> cellOrigins) {
        boolean improved;
        do {
            improved = false;
            long currentCost = calculateChipWiringCost(chipVisualElements);
            for (int i = 0; i < chipVisualElements.size(); i++) {
                VisualElement chipVe = chipVisualElements.get(i);
                int currentRotation = chipVe.getRotate();
                int bestRotation = currentRotation;
                long bestCost = currentCost;
                for (int rotation = 0; rotation < 4; rotation++) {
                    if (rotation == currentRotation) {
                        continue;
                    }
                    placeChipInCell(chipVe, cellOrigins.get(i), rotation);
                    long cost = calculateChipWiringCost(chipVisualElements);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestRotation = rotation;
                    }
                }
                placeChipInCell(chipVe, cellOrigins.get(i), bestRotation);
                if (bestRotation != currentRotation) {
                    currentCost = bestCost;
                    improved = true;
                }
            }
        } while (improved);
    }

    private long calculateChipWiringCost(List<VisualElement> chipVisualElements) {
        Map<String, Vector> sourcePositions = new HashMap<>(inputPositions);
        for (int i = 0; i < chipInstances.size(); i++) {
            ChipInstance chip = chipInstances.get(i);
            VisualElement chipVe = chipVisualElements.get(i);
            for (Gate gate : chip.allocatedGates) {
                if (gate.outputNet != null) {
                    Vector position = getWorldPinPosition(chipVe, gate.slot + "Y");
                    if (position != null) {
                        sourcePositions.put(gate.outputNet, position);
                    }
                }
            }
        }

        long cost = 0;
        for (int i = 0; i < chipInstances.size(); i++) {
            ChipInstance chip = chipInstances.get(i);
            VisualElement chipVe = chipVisualElements.get(i);
            for (Gate gate : chip.allocatedGates) {
                for (int pin = 0; pin < gate.inputNets.size(); pin++) {
                    Vector source = sourcePositions.get(gate.inputNets.get(pin));
                    Vector target = getWorldPinPosition(chipVe, gate.slot + (pin == 0 ? "A" : "B"));
                    if (source != null && target != null) {
                        cost += Math.abs(source.x - target.x) + Math.abs(source.y - target.y);
                    }
                }
            }
        }
        return cost;
    }

    private int getChipLayoutMaxX() {
        int maxX = 0;
        for (ChipGeometry geometry : chipGeometry.values()) {
            maxX = Math.max(maxX, geometry.max.x);
        }
        return maxX;
    }

    private int alignToGrid(int coordinate) {
        return (int) Math.ceil((double) coordinate / SIZE) * SIZE;
    }

    private Map<String, Integer> calculateOutputPositions(LinkedHashMap<String, String> combResultNets,
                                                          Map<String, NetRoute> netRoutes) {
        Map<String, Integer> yMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : combResultNets.entrySet()) {
            String outName = entry.getKey();
            String netName = entry.getValue();
            int targetY = -1;
            NetRoute nr = netRoutes.get(netName);
            if (nr != null) {
                List<Endpoint> endpoints = new ArrayList<>(nr.leftEndpoints);
                endpoints.addAll(nr.rightEndpoints);
                for (Endpoint endpoint : endpoints) {
                    if (endpoint.chip != null && endpoint.output) {
                        targetY = endpoint.position.y;
                        break;
                    }
                }
                if (targetY < 0 && !endpoints.isEmpty()) {
                    targetY = endpoints.get(0).position.y;
                }
            }
            if (targetY < 0) {
                targetY = SIZE;
            } else {
                targetY = targetY + SIZE;
            }
            yMap.put(outName, targetY);
        }

        int lastY = -1000;
        for (Map.Entry<String, Integer> entry : yMap.entrySet()) {
            int y = entry.getValue();
            if (y < lastY + 4 * SIZE) {
                y = lastY + 4 * SIZE;
            }
            entry.setValue(y);
            lastY = y;
        }
        return yMap;
    }

    private void registerOutputEndpoints(LinkedHashMap<String, String> combResultNets,
                                         Map<String, NetRoute> netRoutes,
                                         Map<String, Integer> outputYMap, int xOutput) {
        for (Map.Entry<String, String> entry : combResultNets.entrySet()) {
            String outName = entry.getKey();
            String netName = entry.getValue();
            if (!"CONST_0".equals(netName) && !"CONST_1".equals(netName)) {
                int y = outputYMap.get(outName);
                Vector position = new Vector(xOutput, y);
                fixedRoutePoints.add(toRoutePoint(position));
                getRoute(netRoutes, netName).rightEndpoints.add(new Endpoint(
                        PinSide.OUTPUT, position, new Vector(-1, 0)));
            }
        }
    }

    private void classifyNets(Map<String, NetRoute> netRoutes,
                              List<String> leftNets, List<String> rightNets, Set<String> bridgedNets,
                              Map<String, List<LocalConnection>> localConnections,
                              Set<String> directChipNets) {
        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            String name = entry.getKey();
            NetRoute route = entry.getValue();
            if (directChipNets.contains(name)) {
                bridgedNets.add(name);
                continue;
            }
            Set<Endpoint> localEndpoints = getLocalEndpointsToSkip(route, localConnections.get(name));
            boolean directOutput = isDirectOutputNet(route);
            boolean hasLeft = !directOutput
                    && hasExternallyRoutedEndpoint(route.leftEndpoints, localEndpoints);
            boolean hasRight = hasExternallyRoutedEndpoint(route.rightEndpoints, localEndpoints);
            if (hasLeft) {
                leftNets.add(name);
            }
            if (hasRight) {
                rightNets.add(name);
            }
            if (hasLeft && hasRight) {
                bridgedNets.add(name);
            }
        }
    }

    private void placeChipsInCircuit(Circuit circuit, List<VisualElement> chipVisualElements) {
        for (int i = 0; i < chipVisualElements.size(); i++) {
            VisualElement chipVe = chipVisualElements.get(i);
            circuit.add(chipVe);

            ChipInstance ci = chipInstances.get(i);
            for (Gate g : ci.allocatedGates) {
                if (g.outputNet == null) {
                    tunnelUnusedPin(circuit, chipVe, g.slot + "A");
                    if (!"7404".equals(ci.chipType)) {
                        tunnelUnusedPin(circuit, chipVe, g.slot + "B");
                    }
                }
            }
        }
    }

    private int placeOutputsInCircuit(Circuit circuit, LinkedHashMap<String, String> combResultNets,
                                      Map<String, Integer> outputYMap, int xOutput) {
        int maxY = SIZE;
        for (Map.Entry<String, String> entry : combResultNets.entrySet()) {
            String outName = entry.getKey();
            String netName = entry.getValue();
            int yOutput = outputYMap.get(outName);
            Vector outPos = new Vector(xOutput, yOutput);
            maxY = Math.max(maxY, yOutput);

            VisualElement outVe;
            if (useSwitchesAndLeds) {
                outVe = new VisualElement(Out.LEDDESCRIPTION.getName()).setShapeFactory(shapeFactory);
            } else {
                outVe = new VisualElement(Out.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
                checkPinNumber(outVe, outName);
            }
            outVe.getElementAttributes().set(Keys.LABEL, outName);
            outVe.setPos(outPos);
            circuit.add(outVe);

            if ("CONST_0".equals(netName)) {
                Vector wireEnd = outPos.add(-2 * SIZE, 0);
                circuit.add(new Wire(wireEnd, outPos));
                registerFixedTrack(wireEnd, outPos, "CONST_0");
                VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
                tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_0").set(Keys.ROTATE, new Rotation(2));
                tunnel.setPos(wireEnd);
                circuit.add(tunnel);
                const0Used = true;
            } else if ("CONST_1".equals(netName)) {
                Vector wireEnd = outPos.add(-2 * SIZE, 0);
                circuit.add(new Wire(wireEnd, outPos));
                registerFixedTrack(wireEnd, outPos, "CONST_1");
                VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
                tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_1").set(Keys.ROTATE, new Rotation(2));
                tunnel.setPos(wireEnd);
                circuit.add(tunnel);
                const1Used = true;
            }
        }
        return maxY + 4 * SIZE;
    }

    private NetRoute getRoute(Map<String, NetRoute> netRoutes, String name) {
        return netRoutes.computeIfAbsent(name, k -> new NetRoute());
    }

    private void registerGatePin(Map<String, NetRoute> netRoutes, VisualElement chipVe, ChipInstance chip,
                                 String pinName, String netName) {
        if (netName == null || "CONST_0".equals(netName) || "CONST_1".equals(netName)) {
            return;
        }
        Vector offset = getPinOffset(chipVe, pinName);
        Vector position = getWorldPinPosition(chipVe, pinName);
        if (offset == null || position == null) {
            return;
        }
        Vector normal = getPinOutwardNormal(chipVe, offset);
        PinSide side = getChipPinSide(normal);
        Endpoint endpoint = new Endpoint(side, position, normal, chip, pinName.endsWith("Y"));
        fixedRoutePoints.add(toRoutePoint(position));
        RoutePoint escape = getEscapePoint(endpoint);
        fixedRoutePoints.add(escape);
        addTrackPoints(toRoutePoint(position), escape, netName);
        if (side == PinSide.CHIP_LEFT || side == PinSide.CHIP_TOP) {
            getRoute(netRoutes, netName).leftEndpoints.add(
                    endpoint);
        } else {
            getRoute(netRoutes, netName).rightEndpoints.add(
                    endpoint);
        }
    }

    private Vector getWorldPinPosition(VisualElement chipVe, String pinName) {
        for (Pin pin : chipVe.getPins()) {
            if (pin.getName().equalsIgnoreCase(pinName)) {
                return pin.getPos();
            }
        }
        return null;
    }

    private Vector getPinOutwardNormal(VisualElement chipVe, Vector localPosition) {
        int maxX = 0;
        for (Pin pin : chipVe.getShape().getPins()) {
            maxX = Math.max(maxX, pin.getPos().x);
        }
        int normalX = localPosition.x == 0 ? -1 : localPosition.x == maxX ? 1 : 0;
        int normalY = 0;
        switch (chipVe.getRotate()) {
            case 1:
                return new Vector(normalY, -normalX);
            case 2:
                return new Vector(-normalX, -normalY);
            case 3:
                return new Vector(-normalY, normalX);
            default:
                return new Vector(normalX, normalY);
        }
    }

    private PinSide getChipPinSide(Vector normal) {
        if (normal.x < 0) {
            return PinSide.CHIP_LEFT;
        } else if (normal.x > 0) {
            return PinSide.CHIP_RIGHT;
        } else if (normal.y < 0) {
            return PinSide.CHIP_TOP;
        } else {
            return PinSide.CHIP_BOTTOM;
        }
    }

    private boolean hasExternallyRoutedEndpoint(List<Endpoint> endpoints, Set<Endpoint> localEndpoints) {
        for (Endpoint endpoint : endpoints) {
            if (!localEndpoints.contains(endpoint)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<LocalConnection>> assignLocalConnections(Map<String, NetRoute> netRoutes) {
        Map<ChipInstance, Map<PinSide, List<LocalConnection>>> connectionsByChip = new LinkedHashMap<>();
        Map<String, List<LocalConnection>> selectedByNet = new LinkedHashMap<>();
        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            NetRoute route = entry.getValue();
            Map<ChipInstance, List<Endpoint>> pinsByChip = new LinkedHashMap<>();
            List<Endpoint> endpoints = new ArrayList<>(route.leftEndpoints);
            endpoints.addAll(route.rightEndpoints);
            List<Endpoint> externalEndpoints = new ArrayList<>();
            for (Endpoint endpoint : endpoints) {
                if (endpoint.chip == null) {
                    externalEndpoints.add(endpoint);
                } else {
                    pinsByChip.computeIfAbsent(endpoint.chip, k -> new ArrayList<Endpoint>()).add(endpoint);
                }
            }
            boolean hasExternalInput = false;
            for (Endpoint endpoint : externalEndpoints) {
                if (endpoint.side == PinSide.INPUT) {
                    hasExternalInput = true;
                    break;
                }
            }
            if (hasExternalInput) {
                continue;
            }

            for (Map.Entry<ChipInstance, List<Endpoint>> chipEntry : pinsByChip.entrySet()) {
                List<Endpoint> pins = chipEntry.getValue();
                if (pins.size() < 2) {
                    continue;
                }
                Endpoint source = chooseLocalAnchor(pins, externalEndpoints);
                for (Endpoint target : pins) {
                    if (target != source) {
                        LocalConnection connection = new LocalConnection(entry.getKey(), source, target);
                        connectionsByChip.computeIfAbsent(chipEntry.getKey(),
                                k -> new LinkedHashMap<PinSide, List<LocalConnection>>())
                                .computeIfAbsent(source.side, k -> new ArrayList<LocalConnection>())
                                .add(connection);
                    }
                }
            }
        }

        for (Map<PinSide, List<LocalConnection>> connectionsBySide : connectionsByChip.values()) {
            for (List<LocalConnection> candidates : connectionsBySide.values()) {
                Collections.sort(candidates, new Comparator<LocalConnection>() {
                    @Override
                    public int compare(LocalConnection first, LocalConnection second) {
                        int firstDistance = Math.abs(first.source.y - first.target.y);
                        int secondDistance = Math.abs(second.source.y - second.target.y);
                        if (firstDistance != secondDistance) {
                            return Integer.compare(firstDistance, secondDistance);
                        }
                        int sourceComparison = Integer.compare(first.source.y, second.source.y);
                        return sourceComparison != 0 ? sourceComparison : first.netName.compareTo(second.netName);
                    }
                });

                int trackCount = 0;
                for (LocalConnection candidate : candidates) {
                    if (trackCount == 5) {
                        break;
                    }
                    candidate.track = trackCount++;
                    selectedByNet.computeIfAbsent(candidate.netName, k -> new ArrayList<LocalConnection>())
                            .add(candidate);
                }
            }
        }
        return selectedByNet;
    }

    private Endpoint chooseLocalAnchor(List<Endpoint> pins, List<Endpoint> externalEndpoints) {
        for (Endpoint pin : pins) {
            if (pin.output) {
                return pin;
            }
        }

        List<Endpoint> externalInputs = new ArrayList<>();
        for (Endpoint external : externalEndpoints) {
            if (external.side == PinSide.INPUT) {
                externalInputs.add(external);
            }
        }

        Endpoint anchor = null;
        long bestCost = Long.MAX_VALUE;
        for (Endpoint pin : pins) {
            if (externalInputs.isEmpty()) {
                if (anchor == null || pin.y < anchor.y) {
                    anchor = pin;
                }
            } else {
                for (Endpoint external : externalInputs) {
                    long distance = Math.abs(pin.y - external.y);
                    long sidePenalty = isWrongChipSide(external, pin) ? 24L * SIZE : 0;
                    long cost = distance + sidePenalty;
                    if (cost < bestCost) {
                        bestCost = cost;
                        anchor = pin;
                    }
                }
            }
        }
        return anchor;
    }

    private boolean isWrongChipSide(Endpoint external, Endpoint pin) {
        if (external.side == PinSide.INPUT) {
            return pin.side != PinSide.CHIP_LEFT;
        }
        return false;
    }

    private Set<Endpoint> getLocalEndpointsToSkip(NetRoute route, List<LocalConnection> connections) {
        Set<Endpoint> localEndpoints = new HashSet<>();
        if (connections == null || connections.isEmpty()) {
            return localEndpoints;
        }

        Set<Endpoint> localSources = new HashSet<>();
        ChipInstance localChip = null;
        boolean singleLocalChip = true;
        for (LocalConnection connection : connections) {
            localEndpoints.add(connection.target);
            localSources.add(connection.source);
            if (localChip == null) {
                localChip = connection.source.chip;
            } else if (localChip != connection.source.chip) {
                singleLocalChip = false;
            }
        }

        Set<Endpoint> connectedLocally = new HashSet<>(localEndpoints);
        connectedLocally.addAll(localSources);
        if (singleLocalChip
                && route.leftEndpoints.size() + route.rightEndpoints.size() == connectedLocally.size()) {
            localEndpoints.addAll(localSources);
        }
        return localEndpoints;
    }

    private void tunnelUnusedPin(Circuit circuit, VisualElement chipVe, String pinName) {
        Vector offset = getPinOffset(chipVe, pinName);
        Vector pinPos = getWorldPinPosition(chipVe, pinName);
        if (offset == null || pinPos == null) {
            return;
        }
        Vector normal = getPinOutwardNormal(chipVe, offset);
        Vector wireEnd = pinPos.add(normal.x * 2 * SIZE, normal.y * 2 * SIZE);

        circuit.add(new Wire(pinPos, wireEnd));
        fixedRoutePoints.add(toRoutePoint(pinPos));
        fixedRoutePoints.add(toRoutePoint(wireEnd));
        addTrackPoints(toRoutePoint(pinPos), toRoutePoint(wireEnd), "CONST_0");

        VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
        tunnel.getElementAttributes()
                .set(Keys.NETNAME, "CONST_0")
                .set(Keys.ROTATE, new Rotation(getRotationForNormal(normal)));
        tunnel.setPos(wireEnd);
        circuit.add(tunnel);
        const0Used = true;
    }

    private int getRotationForNormal(Vector normal) {
        if (normal.x < 0) {
            return 2;
        } else if (normal.x > 0) {
            return 0;
        } else if (normal.y > 0) {
            return 1;
        } else {
            return 3;
        }
    }

    private static class SideConfig {
        private final int xTrunk;
        private final int xOuter;
        private final int xInner;
        private final PinSide outerSide;
        private final Color color;

        SideConfig(int xTrunk, int xOuter, int xInner, PinSide outerSide, Color color) {
            this.xTrunk = xTrunk;
            this.xOuter = xOuter;
            this.xInner = xInner;
            this.outerSide = outerSide;
            this.color = color;
        }
    }

    private void routeNets(Circuit circuit, Map<String, NetRoute> netRoutes,
                           List<String> leftNets, List<String> rightNets, List<String> bridgedList,
                           RoutingLayout layout, Map<String, List<LocalConnection>> localConnections) {

        Map<String, Color> netColorMap = assignNetColors(netRoutes.keySet());
        Map<String, Integer> bridgeYMap = assignBridgeYCoordinates(bridgedList, netRoutes, layout);

        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            String netName = entry.getKey();
            NetRoute route = entry.getValue();
            Color color = netColorMap.get(netName);
            int yBridge = bridgeYMap.getOrDefault(netName, -1);
            List<LocalConnection> netLocalConnections = localConnections.get(netName);
            Set<Endpoint> localEndpoints = getLocalEndpointsToSkip(route, netLocalConnections);
            if (layout.directChipNets.contains(netName)) {
                routeDirectChipConnection(circuit, route, yBridge, layout, color);
                continue;
            }
            if (isDirectOutputNet(route)) {
                routeDirectOutput(circuit, route, rightNets.indexOf(netName),
                        layout.directLeftOutputTracks.getOrDefault(netName, -1),
                        leftNets.size(), layout, color);
                continue;
            }

            if (netLocalConnections != null) {
                for (LocalConnection connection : netLocalConnections) {
                    int sourceEdgeX = getChipEdgeX(connection.source, layout);
                    int targetEdgeX = getChipEdgeX(connection.target, layout);
                    int trackX = connection.source.side == PinSide.CHIP_LEFT
                            ? layout.xChip + (connection.track + 1) * SIZE
                            : layout.xChipRight - (connection.track + 1) * SIZE;
                    circuit.add(new Wire(new Vector(sourceEdgeX, connection.source.y),
                            new Vector(trackX, connection.source.y)).setColor(color));
                    circuit.add(new Wire(new Vector(trackX, connection.source.y),
                            new Vector(trackX, connection.target.y)).setColor(color));
                    circuit.add(new Wire(new Vector(trackX, connection.target.y),
                            new Vector(targetEdgeX, connection.target.y)).setColor(color));
                }
            }

            List<Endpoint> leftEndpoints = getExternallyRoutedEndpoints(route.leftEndpoints, localEndpoints);
            List<Endpoint> rightEndpoints = getExternallyRoutedEndpoints(route.rightEndpoints, localEndpoints);

            if (!leftEndpoints.isEmpty()) {
                int xTrunk = layout.xLeftTrackStart + leftNets.indexOf(netName) * layout.trackPitch;
                SideConfig cfg = new SideConfig(xTrunk, layout.xInput, layout.xChip, PinSide.INPUT, color);
                routeSide(circuit, leftEndpoints, cfg, yBridge);
            }

            if (!rightEndpoints.isEmpty()) {
                int xTrunk = layout.xRightTrackStart + rightNets.indexOf(netName) * layout.trackPitch;
                SideConfig cfg = new SideConfig(xTrunk, layout.xOutput, layout.xChipRight, PinSide.OUTPUT, color);
                routeSide(circuit, rightEndpoints, cfg, yBridge);
            }

            if (yBridge >= 0) {
                int xTrunkLeft = layout.xLeftTrackStart + leftNets.indexOf(netName) * layout.trackPitch;
                int xTrunkRight = layout.xRightTrackStart + rightNets.indexOf(netName) * layout.trackPitch;
                circuit.add(new Wire(new Vector(xTrunkLeft, yBridge), new Vector(xTrunkRight, yBridge)).setColor(color));
            }
        }

    }

    private void routeGridNets(Circuit circuit, Map<String, NetRoute> netRoutes, int xOutput)
            throws BuilderException {
        Map<String, Color> colors = assignNetColors(netRoutes.keySet());
        ArrayList<Wire> routedWires = new ArrayList<>();
        Set<RoutePoint> occupied = new HashSet<>(fixedRoutePoints);
        RouteBounds bounds = getRouteBounds(netRoutes, xOutput);
        List<Map.Entry<String, NetRoute>> orderedRoutes = new ArrayList<>(netRoutes.entrySet());
        Collections.sort(orderedRoutes, new Comparator<Map.Entry<String, NetRoute>>() {
            @Override
            public int compare(Map.Entry<String, NetRoute> first, Map.Entry<String, NetRoute> second) {
                int spanComparison = Long.compare(getRouteSpan(first.getValue()),
                        getRouteSpan(second.getValue()));
                if (spanComparison != 0) {
                    return spanComparison;
                }
                int firstCount = getRouteEndpoints(first.getValue()).size();
                int secondCount = getRouteEndpoints(second.getValue()).size();
                return Integer.compare(secondCount, firstCount);
            }
        });

        for (Map.Entry<String, NetRoute> entry : orderedRoutes) {
            currentRouteNet = entry.getKey();
            List<Endpoint> endpoints = getRouteEndpoints(entry.getValue());
            if (endpoints.size() < 2) {
                continue;
            }

            Endpoint source = chooseRouteSource(endpoints);
            RoutePoint sourcePin = toRoutePoint(source.position);
            Endpoint localTarget = getDirectSameChipTarget(endpoints, source);
            if (localTarget != null) {
                RoutePoint targetPin = toRoutePoint(localTarget.position);
                Set<RoutePoint> protectedPins = getOtherPinPositions(netRoutes, source, localTarget);
                Set<RoutePoint> sameNet = getEndpointRoutePoints(endpoints);
                List<RoutePoint> directPath = findOrthogonalPath(sourcePin, targetPin,
                        occupied, sameNet, bounds,
                        source.chip, protectedPins);
                if (directPath == null) {
                    directPath = findPathWithExpandedBounds(sourcePin, targetPin,
                            occupied, sameNet, bounds, source.chip, protectedPins);
                }
                if (directPath == null) {
                    throw new BuilderException("Unable to route same-chip net " + entry.getKey());
                }
                addOrthogonalWires(routedWires, directPath, colors.get(entry.getKey()));
                addRouteEndpoints(occupied, directPath, entry.getKey());
                continue;
            }

            RoutePoint sourceEscape = getEscapePoint(source);
            Set<RoutePoint> sameNet = getEndpointRoutePoints(endpoints);
            List<List<RoutePoint>> netPaths = new ArrayList<>();

            for (Endpoint target : endpoints) {
                if (target == source) {
                    continue;
                }
                RoutePoint targetPin = toRoutePoint(target.position);
                RoutePoint targetEscape = getEscapePoint(target);
                List<RoutePoint> path = findOrthogonalPath(sourceEscape, targetEscape,
                        occupied, sameNet, bounds, null, Collections.<RoutePoint>emptySet());
                if (path == null) {
                    path = findPathWithExpandedBounds(sourceEscape, targetEscape, occupied, sameNet,
                            bounds, null, Collections.<RoutePoint>emptySet());
                }
                if (path == null) {
                    throw new BuilderException("Unable to route net " + entry.getKey()
                            + " around chip footprints");
                }

                List<RoutePoint> netPoints = new ArrayList<>();
                netPoints.add(sourcePin);
                netPoints.add(sourceEscape);
                netPoints.addAll(path);
                netPoints.add(targetEscape);
                netPoints.add(targetPin);
                netPaths.add(netPoints);

                sameNet.addAll(path);
                sameNet.add(targetPin);
                sameNet.add(targetEscape);
                addSegmentPoints(sameNet, targetEscape, targetPin);
            }

            for (List<RoutePoint> netPath : netPaths) {
                addOrthogonalWires(routedWires, netPath, colors.get(entry.getKey()));
                addRouteEndpoints(occupied, netPath, entry.getKey());
            }
        }
        currentRouteNet = null;
        circuit.add(routedWires);
    }

    private void addRouteEndpoints(Set<RoutePoint> occupied, List<RoutePoint> routePoints, String netName) {
        List<RoutePoint> simplified = simplifyRoutePoints(routePoints);
        occupied.addAll(simplified);
        for (int i = 1; i < simplified.size(); i++) {
            addTrackPoints(simplified.get(i - 1), simplified.get(i), netName);
        }
    }

    private void addTrackPoints(RoutePoint first, RoutePoint second, String netName) {
        int direction = first.y == second.y ? 1 : 2;
        int dx = Integer.compare(second.x, first.x) * SIZE;
        int dy = Integer.compare(second.y, first.y) * SIZE;
        RoutePoint point = first;
        while (true) {
            Map<String, Integer> trackDirections = occupiedTrackDirections.computeIfAbsent(
                    point, key -> new HashMap<String, Integer>());
            Integer existing = trackDirections.get(netName);
            trackDirections.put(netName, (existing == null ? 0 : existing) | direction);
            if (point.equals(second)) {
                return;
            }
            point = point.add(dx, dy);
        }
    }

    private List<RoutePoint> findPathWithExpandedBounds(RoutePoint start, RoutePoint goal,
                                                         Set<RoutePoint> occupied,
                                                         Set<RoutePoint> sameNet,
                                                         RouteBounds bounds,
                                                         ChipInstance allowedChip,
                                                         Set<RoutePoint> protectedPins) {
        RouteBounds expandedBounds = bounds;
        for (int attempt = 0; attempt < 8; attempt++) {
            expandedBounds = expandRouteBounds(expandedBounds, 48 * SIZE);
            List<RoutePoint> path = findOrthogonalPath(start, goal, occupied, sameNet,
                    expandedBounds, allowedChip, protectedPins);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private Set<RoutePoint> getEndpointRoutePoints(List<Endpoint> endpoints) {
        Set<RoutePoint> points = new HashSet<>();
        for (Endpoint endpoint : endpoints) {
            RoutePoint pin = toRoutePoint(endpoint.position);
            RoutePoint escape = getEscapePoint(endpoint);
            addSegmentPoints(points, pin, escape);
        }
        return points;
    }

    private void addSegmentPoints(Set<RoutePoint> points, RoutePoint first, RoutePoint second) {
        int dx = Integer.compare(second.x, first.x) * SIZE;
        int dy = Integer.compare(second.y, first.y) * SIZE;
        RoutePoint point = first;
        while (!point.equals(second)) {
            points.add(point);
            point = point.add(dx, dy);
        }
        points.add(second);
    }

    private void addSegmentPoints(Set<RoutePoint> points, RoutePoint first, Vector second) {
        addSegmentPoints(points, first, toRoutePoint(second));
    }

    private RouteBounds expandRouteBounds(RouteBounds bounds, int amount) {
        return new RouteBounds(bounds.minX - amount, bounds.maxX + amount,
                bounds.minY - amount, bounds.maxY + amount);
    }

    private List<Endpoint> getRouteEndpoints(NetRoute route) {
        List<Endpoint> endpoints = new ArrayList<>(route.leftEndpoints);
        endpoints.addAll(route.rightEndpoints);
        return endpoints;
    }

    private long getRouteSpan(NetRoute route) {
        List<Endpoint> endpoints = getRouteEndpoints(route);
        long span = 0;
        for (int first = 0; first < endpoints.size(); first++) {
            for (int second = first + 1; second < endpoints.size(); second++) {
                span = Math.max(span, manhattanDistance(endpoints.get(first).position,
                        endpoints.get(second).position));
            }
        }
        return span;
    }

    private static final int[][] ROUTE_DIRECTIONS = new int[][]{
            {1, 0}, {0, 1}, {-1, 0}, {0, -1}
    };

    private static class RouteBounds {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;

        RouteBounds(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private RouteBounds getRouteBounds(Map<String, NetRoute> netRoutes, int xOutput) {
        int minX = -ROUTE_MARGIN;
        int maxX = xOutput + ROUTE_MARGIN;
        int minY = -ROUTE_MARGIN;
        int maxY = ROUTE_MARGIN;
        for (ChipGeometry geometry : chipGeometry.values()) {
            minX = Math.min(minX, geometry.min.x - ROUTE_MARGIN);
            maxX = Math.max(maxX, geometry.max.x + ROUTE_MARGIN);
            minY = Math.min(minY, geometry.min.y - ROUTE_MARGIN);
            maxY = Math.max(maxY, geometry.max.y + ROUTE_MARGIN);
        }
        for (NetRoute route : netRoutes.values()) {
            for (Endpoint endpoint : route.leftEndpoints) {
                minX = Math.min(minX, endpoint.position.x - ROUTE_MARGIN);
                maxX = Math.max(maxX, endpoint.position.x + ROUTE_MARGIN);
                minY = Math.min(minY, endpoint.position.y - ROUTE_MARGIN);
                maxY = Math.max(maxY, endpoint.position.y + ROUTE_MARGIN);
            }
            for (Endpoint endpoint : route.rightEndpoints) {
                minX = Math.min(minX, endpoint.position.x - ROUTE_MARGIN);
                maxX = Math.max(maxX, endpoint.position.x + ROUTE_MARGIN);
                minY = Math.min(minY, endpoint.position.y - ROUTE_MARGIN);
                maxY = Math.max(maxY, endpoint.position.y + ROUTE_MARGIN);
            }
        }
        return new RouteBounds(minX, maxX, minY, maxY);
    }

    private Endpoint chooseRouteSource(List<Endpoint> endpoints) {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.chip != null && endpoint.output) {
                return endpoint;
            }
        }
        return endpoints.get(0);
    }

    private Endpoint getDirectSameChipTarget(List<Endpoint> endpoints, Endpoint source) {
        if (endpoints.size() != 2 || source.chip == null || !source.output) {
            return null;
        }
        Endpoint target = endpoints.get(0) == source ? endpoints.get(1) : endpoints.get(0);
        return target.chip == source.chip && !target.output ? target : null;
    }

    private Set<RoutePoint> getOtherPinPositions(Map<String, NetRoute> netRoutes,
                                                  Endpoint first, Endpoint second) {
        Set<RoutePoint> positions = new HashSet<>();
        ChipGeometry geometry = chipGeometry.get(first.chip);
        for (Vector pinPosition : geometry.pinPositions) {
            RoutePoint point = toRoutePoint(pinPosition);
            if (!point.equals(toRoutePoint(first.position))
                    && !point.equals(toRoutePoint(second.position))) {
                positions.add(point);
            }
        }
        for (NetRoute route : netRoutes.values()) {
            for (Endpoint endpoint : route.leftEndpoints) {
                addProtectedPin(positions, endpoint, first, second);
            }
            for (Endpoint endpoint : route.rightEndpoints) {
                addProtectedPin(positions, endpoint, first, second);
            }
        }
        return positions;
    }

    private void addProtectedPin(Set<RoutePoint> positions, Endpoint endpoint,
                                 Endpoint first, Endpoint second) {
        if (endpoint.chip == first.chip && endpoint != first && endpoint != second) {
            positions.add(toRoutePoint(endpoint.position));
        }
    }

    private RoutePoint toRoutePoint(Vector position) {
        return new RoutePoint(position.x, position.y);
    }

    private RoutePoint getEscapePoint(Endpoint endpoint) {
        if (endpoint.chip == null) {
            return toRoutePoint(endpoint.position);
        }
        return new RoutePoint(endpoint.position.x + endpoint.normal.x * 2 * SIZE,
                endpoint.position.y + endpoint.normal.y * 2 * SIZE);
    }

    private List<RoutePoint> findOrthogonalPath(RoutePoint start, RoutePoint goal,
                                                 Set<RoutePoint> occupied, Set<RoutePoint> sameNet,
                                                 RouteBounds bounds, ChipInstance allowedChip,
                                                 Set<RoutePoint> protectedPins) {
        PriorityQueue<RouteSearchNode> open = new PriorityQueue<>();
        Map<String, Integer> bestCosts = new HashMap<>();
        RouteSearchNode initial = new RouteSearchNode(start, -1, 0, routeHeuristic(start, goal), null);
        open.add(initial);
        bestCosts.put(routeSearchKey(start, -1), 0);

        while (!open.isEmpty()) {
            RouteSearchNode current = open.poll();
            if (current.point.equals(goal)) {
                return reconstructRoute(current);
            }
            if (current.cost > bestCosts.getOrDefault(
                    routeSearchKey(current.point, current.direction), Integer.MAX_VALUE)) {
                continue;
            }

            for (int direction = 0; direction < ROUTE_DIRECTIONS.length; direction++) {
                RoutePoint next = current.point.add(ROUTE_DIRECTIONS[direction][0] * SIZE,
                        ROUTE_DIRECTIONS[direction][1] * SIZE);
                if (!isWithinRouteBounds(next, bounds)
                        || (!next.equals(goal) && isInsideChip(next, allowedChip))
                        || (protectedPins.contains(next) && !next.equals(goal))) {
                    continue;
                }
                if (occupied.contains(next) && !sameNet.contains(next)) {
                    continue;
                }
                if (!canContinueAcrossTrack(current, direction)) {
                    continue;
                }
                Integer nextTrack = getForeignTrackDirections(next);
                int routeDirection = direction % 2 == 0 ? 1 : 2;
                if (nextTrack != null && (next.equals(goal) || (nextTrack & routeDirection) != 0)) {
                    continue;
                }

                int stepCost = 10;
                if (current.direction >= 0 && current.direction != direction) {
                    stepCost += ROUTE_BEND_COST * 10;
                }
                if (!sameNet.contains(next) && isNearOccupiedTrack(next, occupied)) {
                    stepCost += 2;
                }
                int cost = current.cost + stepCost;
                String key = routeSearchKey(next, direction);
                if (cost < bestCosts.getOrDefault(key, Integer.MAX_VALUE)) {
                    bestCosts.put(key, cost);
                    open.add(new RouteSearchNode(next, direction, cost,
                            routeHeuristic(next, goal), current));
                }
            }
        }
        return null;
    }

    private boolean canContinueAcrossTrack(RouteSearchNode current, int nextDirection) {
        Integer trackDirections = getForeignTrackDirections(current.point);
        if (trackDirections == null) {
            return true;
        }
        int routeDirection = current.direction % 2 == 0 ? 1 : 2;
        return current.direction == nextDirection && (trackDirections & routeDirection) == 0;
    }

    private Integer getForeignTrackDirections(RoutePoint point) {
        Map<String, Integer> trackDirections = occupiedTrackDirections.get(point);
        if (trackDirections == null) {
            return null;
        }
        int directions = 0;
        for (Map.Entry<String, Integer> entry : trackDirections.entrySet()) {
            if (!entry.getKey().equals(currentRouteNet)) {
                directions |= entry.getValue();
            }
        }
        return directions == 0 ? null : directions;
    }

    private boolean isWithinRouteBounds(RoutePoint point, RouteBounds bounds) {
        return point.x >= bounds.minX && point.x <= bounds.maxX
                && point.y >= bounds.minY && point.y <= bounds.maxY;
    }

    private boolean isInsideChip(RoutePoint point, ChipInstance allowedChip) {
        for (Map.Entry<ChipInstance, ChipGeometry> entry : chipGeometry.entrySet()) {
            if (entry.getKey() == allowedChip) {
                continue;
            }
            ChipGeometry geometry = entry.getValue();
            if (point.x >= geometry.min.x - SIZE && point.x <= geometry.max.x + SIZE
                    && point.y >= geometry.min.y - SIZE && point.y <= geometry.max.y + SIZE) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearOccupiedTrack(RoutePoint point, Set<RoutePoint> occupied) {
        for (int[] direction : ROUTE_DIRECTIONS) {
            if (occupied.contains(point.add(direction[0] * SIZE, direction[1] * SIZE))) {
                return true;
            }
        }
        return false;
    }

    private int routeHeuristic(RoutePoint first, RoutePoint second) {
        return (Math.abs(first.x - second.x) + Math.abs(first.y - second.y)) / SIZE * 10;
    }

    private String routeSearchKey(RoutePoint point, int direction) {
        return point.x + ":" + point.y + ":" + direction;
    }

    private List<RoutePoint> reconstructRoute(RouteSearchNode end) {
        List<RoutePoint> path = new ArrayList<>();
        for (RouteSearchNode node = end; node != null; node = node.previous) {
            path.add(node.point);
        }
        Collections.reverse(path);
        return path;
    }

    private void addOrthogonalWires(ArrayList<Wire> wires, List<RoutePoint> points, Color color) {
        List<RoutePoint> simplified = simplifyRoutePoints(points);
        for (int i = 1; i < simplified.size(); i++) {
            RoutePoint first = simplified.get(i - 1);
            RoutePoint second = simplified.get(i);
            if (first.x == second.x || first.y == second.y) {
                wires.add(new Wire(first.toVector(), second.toVector()).setColor(color));
            } else {
                wires.add(new Wire(first.toVector(), new Vector(second.x, first.y)).setColor(color));
                wires.add(new Wire(new Vector(second.x, first.y), second.toVector()).setColor(color));
            }
        }
    }

    private List<RoutePoint> simplifyRoutePoints(List<RoutePoint> points) {
        List<RoutePoint> simplified = new ArrayList<>();
        for (RoutePoint point : points) {
            if (simplified.isEmpty() || !simplified.get(simplified.size() - 1).equals(point)) {
                while (simplified.size() >= 2 && isCollinear(
                        simplified.get(simplified.size() - 2),
                        simplified.get(simplified.size() - 1), point)) {
                    simplified.remove(simplified.size() - 1);
                }
                simplified.add(point);
            }
        }
        return simplified;
    }

    private boolean isCollinear(RoutePoint first, RoutePoint second, RoutePoint third) {
        return (first.x == second.x && second.x == third.x)
                || (first.y == second.y && second.y == third.y);
    }

    private int getChipEdgeX(Endpoint endpoint, RoutingLayout layout) {
        return endpoint.side == PinSide.CHIP_LEFT ? layout.xChip : layout.xChipRight;
    }

    private Set<String> getDirectChipNets(Map<String, NetRoute> netRoutes,
                                          Map<String, List<LocalConnection>> localConnections) {
        Set<String> directNets = new HashSet<>();
        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            if (localConnections.containsKey(entry.getKey())) {
                continue;
            }
            List<Endpoint> endpoints = new ArrayList<>(entry.getValue().leftEndpoints);
            endpoints.addAll(entry.getValue().rightEndpoints);
            if (endpoints.size() != 2 || endpoints.get(0).chip == null || endpoints.get(1).chip == null
                    || endpoints.get(0).chip == endpoints.get(1).chip
                    || endpoints.get(0).output == endpoints.get(1).output) {
                continue;
            }
            directNets.add(entry.getKey());
        }
        return directNets;
    }

    private void routeDirectChipConnection(Circuit circuit, NetRoute route, int yBridge,
                                           RoutingLayout layout, Color color) {
        Endpoint source = null;
        Endpoint target = null;
        for (Endpoint endpoint : route.leftEndpoints) {
            if (endpoint.chip != null) {
                if (endpoint.output) {
                    source = endpoint;
                } else {
                    target = endpoint;
                }
            }
        }
        for (Endpoint endpoint : route.rightEndpoints) {
            if (endpoint.chip != null) {
                if (endpoint.output) {
                    source = endpoint;
                } else {
                    target = endpoint;
                }
            }
        }
        if (source == null || target == null) {
            return;
        }

        int sourceIndex = chipInstances.indexOf(source.chip);
        int targetIndex = chipInstances.indexOf(target.chip);
        int firstGap = Math.min(sourceIndex, targetIndex);
        int lastGap = Math.max(sourceIndex, targetIndex) - 1;
        int originalGap = 0;
        int bestGapDistance = Integer.MAX_VALUE;
        for (int gap = 0; gap < layout.numChips; gap++) {
            int gapBaseY = gap * layout.chipPitchY + 15 * SIZE;
            int distance = Math.abs(yBridge - gapBaseY);
            if (distance < bestGapDistance) {
                bestGapDistance = distance;
                originalGap = gap;
            }
        }
        int gapIndex = Math.max(firstGap, Math.min(lastGap, originalGap));
        int gapLaneOffset = yBridge - originalGap * layout.chipPitchY - 15 * SIZE;
        int gapY = gapIndex * layout.chipPitchY + 15 * SIZE + gapLaneOffset;
        int sourceOutsideX = source.side == PinSide.CHIP_LEFT
                ? layout.xChip - SIZE : layout.xChipRight + SIZE;
        int targetOutsideX = target.side == PinSide.CHIP_LEFT
                ? layout.xChip - SIZE : layout.xChipRight + SIZE;

        addWire(circuit, source, sourceOutsideX, layout, color);
        circuit.add(new Wire(new Vector(sourceOutsideX, source.y),
                new Vector(sourceOutsideX, gapY)).setColor(color));
        if (sourceOutsideX != targetOutsideX) {
            circuit.add(new Wire(new Vector(sourceOutsideX, gapY),
                    new Vector(targetOutsideX, gapY)).setColor(color));
        }
        circuit.add(new Wire(new Vector(targetOutsideX, gapY),
                new Vector(targetOutsideX, target.y)).setColor(color));
        addWire(circuit, target, targetOutsideX, layout, color);
    }

    private void addWire(Circuit circuit, Endpoint endpoint, int outsideX,
                         RoutingLayout layout, Color color) {
        int edgeX = getChipEdgeX(endpoint, layout);
        circuit.add(new Wire(new Vector(edgeX, endpoint.y),
                new Vector(outsideX, endpoint.y)).setColor(color));
    }

    private boolean isDirectOutputNet(NetRoute route) {
        Endpoint chipOutput = null;
        Endpoint output = null;
        int endpointCount = route.leftEndpoints.size() + route.rightEndpoints.size();
        if (endpointCount != 2) {
            return false;
        }
        List<Endpoint> endpoints = new ArrayList<>(route.leftEndpoints);
        endpoints.addAll(route.rightEndpoints);
        for (Endpoint endpoint : endpoints) {
            if (endpoint.chip == null && endpoint.side == PinSide.OUTPUT) {
                output = endpoint;
            } else if (endpoint.chip != null && endpoint.output) {
                chipOutput = endpoint;
            } else {
                return false;
            }
        }
        return chipOutput != null && output != null;
    }

    private Map<String, Integer> getDirectLeftOutputTracks(Map<String, NetRoute> netRoutes) {
        Map<String, Integer> tracks = new LinkedHashMap<>();
        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            NetRoute route = entry.getValue();
            if (!isDirectOutputNet(route)) {
                continue;
            }
            for (Endpoint endpoint : route.leftEndpoints) {
                if (endpoint.chip != null && endpoint.output
                        && endpoint.side == PinSide.CHIP_LEFT) {
                    tracks.put(entry.getKey(), tracks.size());
                    break;
                }
            }
        }
        return tracks;
    }

    private void routeDirectOutput(Circuit circuit, NetRoute route, int trackIndex,
                                   int directOutputTrack, int leftTrackCount, RoutingLayout layout,
                                   Color color) {
        Endpoint chipOutput = null;
        Endpoint output = null;
        List<Endpoint> endpoints = new ArrayList<>(route.leftEndpoints);
        endpoints.addAll(route.rightEndpoints);
        for (Endpoint endpoint : endpoints) {
            if (endpoint.chip == null) {
                output = endpoint;
            } else {
                chipOutput = endpoint;
            }
        }

        int xTrunk = layout.xRightTrackStart + trackIndex * layout.trackPitch;
        int sourceEdgeX = getChipEdgeX(chipOutput, layout);
        if (chipOutput.side == PinSide.CHIP_RIGHT) {
            circuit.add(new Wire(new Vector(sourceEdgeX, chipOutput.y),
                    new Vector(xTrunk, chipOutput.y)).setColor(color));
        } else {
            int chipY = chipInstances.indexOf(chipOutput.chip) * layout.chipPitchY;
            int topLaneY = chipY - 2 * SIZE;
            int bottomLaneY = chipY + 14 * SIZE;
            int laneY = Math.abs(chipOutput.y - topLaneY) + Math.abs(output.y - topLaneY)
                    <= Math.abs(chipOutput.y - bottomLaneY) + Math.abs(output.y - bottomLaneY)
                    ? topLaneY : bottomLaneY;
            int outsideX = layout.xLeftTrackStart
                    + (leftTrackCount + directOutputTrack + 1) * layout.trackPitch;
            circuit.add(new Wire(new Vector(sourceEdgeX, chipOutput.y),
                    new Vector(outsideX, chipOutput.y)).setColor(color));
            circuit.add(new Wire(new Vector(outsideX, chipOutput.y),
                    new Vector(outsideX, laneY)).setColor(color));
            circuit.add(new Wire(new Vector(outsideX, laneY),
                    new Vector(xTrunk, laneY)).setColor(color));
        }
        int sourceY = chipOutput.side == PinSide.CHIP_RIGHT
                ? chipOutput.y : getDirectOutputLaneY(chipOutput, output, layout);
        circuit.add(new Wire(new Vector(xTrunk, sourceY),
                new Vector(xTrunk, output.y)).setColor(color));
        circuit.add(new Wire(new Vector(xTrunk, output.y),
                new Vector(layout.xOutput, output.y)).setColor(color));
    }

    private int getDirectOutputLaneY(Endpoint chipOutput, Endpoint output, RoutingLayout layout) {
        int chipY = chipInstances.indexOf(chipOutput.chip) * layout.chipPitchY;
        int topLaneY = chipY - 2 * SIZE;
        int bottomLaneY = chipY + 14 * SIZE;
        return Math.abs(chipOutput.y - topLaneY) + Math.abs(output.y - topLaneY)
                <= Math.abs(chipOutput.y - bottomLaneY) + Math.abs(output.y - bottomLaneY)
                ? topLaneY : bottomLaneY;
    }

    private Map<String, Color> assignNetColors(Set<String> netNames) {
        Map<String, Color> map = new HashMap<>();
        Set<Color> usedColors = new HashSet<>();
        int idx = 0;
        for (String name : netNames) {
            Color color;
            if (idx < NET_COLORS.length) {
                color = NET_COLORS[idx];
            } else {
                int candidateIndex = idx - NET_COLORS.length;
                do {
                    float hue = (float) ((candidateIndex * 0.6180339887498949) % 1.0);
                    color = Color.getHSBColor(hue, 0.75f, 0.95f);
                    candidateIndex++;
                } while (usedColors.contains(color));
            }
            usedColors.add(color);
            map.put(name, color);
            idx++;
        }
        return map;
    }

    private Map<String, Integer> assignBridgeYCoordinates(List<String> bridgedList,
                                                          Map<String, NetRoute> netRoutes,
                                                          RoutingLayout layout) {
        Map<String, Integer> bridgeYMap = new HashMap<>();
        Map<Integer, Integer> gapUsage = new HashMap<>();
        List<String> orderedBridges = new ArrayList<>(bridgedList);
        Collections.sort(orderedBridges, new Comparator<String>() {
            @Override
            public int compare(String first, String second) {
                int firstY = computeAverageY(netRoutes.get(first));
                int secondY = computeAverageY(netRoutes.get(second));
                int firstGap = findBestGap(firstY, layout.numChips, layout.chipPitchY);
                int secondGap = findBestGap(secondY, layout.numChips, layout.chipPitchY);
                int gapComparison = Integer.compare(firstGap, secondGap);
                return gapComparison != 0 ? gapComparison : Integer.compare(firstY, secondY);
            }
        });

        for (String netName : orderedBridges) {
            NetRoute route = netRoutes.get(netName);
            int yAvg = computeAverageY(route);
            int gapIdx = findBestGap(yAvg, layout.numChips, layout.chipPitchY);
            int count = gapUsage.getOrDefault(gapIdx, 0);
            gapUsage.put(gapIdx, count + 1);

            int yBridge = computeGapTrackY(gapIdx, count, layout);
            bridgeYMap.put(netName, yBridge);
        }
        return bridgeYMap;
    }

    private int computeAverageY(NetRoute route) {
        if (route == null) {
            return 0;
        }
        int sum = 0;
        int count = 0;
        for (Endpoint ep : route.leftEndpoints) {
            sum += ep.y;
            count++;
        }
        for (Endpoint ep : route.rightEndpoints) {
            sum += ep.y;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private int findBestGap(int yAvg, int numChips, int chipPitchY) {
        if (numChips <= 1) {
            return 0;
        }
        int numGaps = numChips;
        int bestGap = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < numGaps; i++) {
            int center = getGapCenter(i, chipPitchY);
            int dist = Math.abs(center - yAvg);
            if (dist < bestDist) {
                bestDist = dist;
                bestGap = i;
            }
        }
        return bestGap;
    }

    private int getGapCenter(int gapIdx, int chipPitchY) {
        return gapIdx * chipPitchY + (chipPitchY + 14 * SIZE) / 2;
    }

    private int computeGapTrackY(int gapIdx, int trackIdx, RoutingLayout layout) {
        return gapIdx * layout.chipPitchY + (15 + trackIdx * 2) * SIZE;
    }

    private void routeSide(Circuit circuit, List<Endpoint> endpoints, SideConfig cfg, int yBridge) {
        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;

        for (Endpoint ep : endpoints) {
            int px = (ep.side == cfg.outerSide) ? cfg.xOuter : cfg.xInner;
            int py = ep.y;
            if (px != cfg.xTrunk) {
                circuit.add(new Wire(new Vector(px, py), new Vector(cfg.xTrunk, py)).setColor(cfg.color));
            }
            yMin = Math.min(yMin, py);
            yMax = Math.max(yMax, py);
        }

        if (yBridge >= 0) {
            yMin = Math.min(yMin, yBridge);
            yMax = Math.max(yMax, yBridge);
        }

        if (yMin < yMax) {
            circuit.add(new Wire(new Vector(cfg.xTrunk, yMin), new Vector(cfg.xTrunk, yMax)).setColor(cfg.color));
        }
    }

    private void placeConstants(Circuit circuit, int xInput, int yInput) {
        if (const1Used) {
            VisualElement vdd = new VisualElement(VDD.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            vdd.setPos(new Vector(xInput, yInput));
            circuit.add(vdd);
            Vector wireEnd = new Vector(xInput + 2 * SIZE, yInput);
            circuit.add(new Wire(new Vector(xInput, yInput), wireEnd));
            registerFixedTrack(new Vector(xInput, yInput), wireEnd, "CONST_1");
            VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_1");
            tunnel.setPos(wireEnd);
            circuit.add(tunnel);
            yInput += 4 * SIZE;
        }
        if (const0Used) {
            VisualElement gnd = new VisualElement(Ground.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            gnd.setPos(new Vector(xInput, yInput));
            circuit.add(gnd);
            Vector wireEnd = new Vector(xInput + 2 * SIZE, yInput);
            circuit.add(new Wire(new Vector(xInput, yInput), wireEnd));
            registerFixedTrack(new Vector(xInput, yInput), wireEnd, "CONST_0");
            VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_0");
            tunnel.setPos(wireEnd);
            circuit.add(tunnel);
        }
    }

    private void registerFixedTrack(Vector first, Vector second, String netName) {
        RoutePoint start = toRoutePoint(first);
        RoutePoint end = toRoutePoint(second);
        fixedRoutePoints.add(start);
        fixedRoutePoints.add(end);
        addTrackPoints(start, end, netName);
    }

    private LinkedHashSet<String> findInputVariables() {
        LinkedHashSet<String> inputs = new LinkedHashSet<>();
        if (declaredVars != null) {
            for (Variable v : declaredVars) {
                inputs.add(v.getIdentifier());
            }
        }
        for (Expression e : combinatorialOutputs.values()) {
            for (Variable v : e.traverse(new VariableVisitor()).getVariables()) {
                inputs.add(v.getIdentifier());
            }
        }
        for (Expression e : sequentialOutputs.values()) {
            for (Variable v : e.traverse(new VariableVisitor()).getVariables()) {
                inputs.add(v.getIdentifier());
            }
        }
        inputs.removeAll(combinatorialOutputs.keySet());
        inputs.removeAll(sequentialOutputs.keySet());
        return inputs;
    }

    private void placeSequential(Circuit circuit, LinkedHashMap<String, String> seqResultNets, int xOutput, int yOutput) {
        int clkX = xOutput - 4 * SIZE;
        int clkYMin = yOutput;

        for (Map.Entry<String, String> entry : seqResultNets.entrySet()) {
            String stateName = entry.getKey();
            String dNet = entry.getValue();

            VisualElement ff = new VisualElement(FlipflopD.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            ff.getElementAttributes().set(Keys.LABEL, stateName);
            Vector ffPos = new Vector(xOutput, yOutput);
            ff.setPos(ffPos);
            circuit.add(ff);

            Vector dPinPos = ffPos;
            circuit.add(new Wire(dPinPos.add(-2 * SIZE, 0), dPinPos));
            VisualElement dTunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            dTunnel.getElementAttributes()
                    .set(Keys.NETNAME, dNet)
                    .set(Keys.ROTATE, new Rotation(2));
            dTunnel.setPos(dPinPos.add(-2 * SIZE, 0));
            circuit.add(dTunnel);

            circuit.add(new Wire(new Vector(clkX, ffPos.y + SIZE), new Vector(ffPos.x, ffPos.y + SIZE)));

            Vector qPinPos = ffPos.add(SIZE * 3, 0);
            circuit.add(new Wire(qPinPos, qPinPos.add(2 * SIZE, 0)));
            VisualElement qTunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            qTunnel.getElementAttributes().set(Keys.NETNAME, stateName);
            qTunnel.setPos(qPinPos.add(2 * SIZE, 0));
            circuit.add(qTunnel);

            yOutput += 6 * SIZE;
        }

        VisualElement clock = new VisualElement(Clock.DESCRIPTION.getName())
                .setShapeFactory(shapeFactory)
                .setPos(new Vector(clkX, clkYMin - 2 * SIZE));
        clock.getElementAttributes()
                .set(Keys.LABEL, "C")
                .set(Keys.ROTATE, new Rotation(3))
                .set(Keys.FREQUENCY, 2)
                .set(Keys.RUN_AT_REAL_TIME, true);
        circuit.add(clock);
        circuit.add(new Wire(new Vector(clkX, clkYMin - 2 * SIZE), new Vector(clkX, yOutput - SIZE * 5)));
    }

    private Vector getPinOffset(VisualElement ve, String pinName) {
        if (ve.getShape() != null && ve.getShape().getPins() != null) {
            for (Pin p : ve.getShape().getPins()) {
                if (p.getName().equalsIgnoreCase(pinName)) {
                    return p.getPos();
                }
            }
        }
        return null;
    }

    private void checkPinNumber(VisualElement ve, String label) {
        if (mai != null && mai.getPins() != null) {
            String num = mai.getPins().get(label);
            if (num != null && num.length() > 0) {
                ve.getElementAttributes().set(Keys.PINNUMBER, num);
            }
        }
    }

    private String nextNetName() {
        return "net_" + (++netCounter);
    }

    private String getConstantNet(int val) {
        if (val == 1) {
            const1Used = true;
            return "CONST_1";
        } else {
            const0Used = true;
            return "CONST_0";
        }
    }

    private String processExpression(Expression expr) throws BuilderException {
        while (expr instanceof NamedExpression) {
            expr = ((NamedExpression) expr).getExpression();
        }
        if (expr instanceof Variable) {
            return ((Variable) expr).getIdentifier();
        } else if (expr instanceof Constant) {
            return ((Constant) expr).getValue() ? getConstantNet(1) : getConstantNet(0);
        } else if (expr instanceof Not) {
            Expression inner = ((Not) expr).getExpression();
            while (inner instanceof NamedExpression) {
                inner = ((NamedExpression) inner).getExpression();
            }
            if (inner instanceof Operation.And) {
                return processNAnd((Operation.And) inner);
            } else if (inner instanceof Operation.Or) {
                return processNOr((Operation.Or) inner);
            } else if (inner instanceof Operation.XOr) {
                return processXNOr((Operation.XOr) inner);
            } else {
                String inNet = processExpression(inner);
                String outNet = nextNetName();
                allocateGate("7404", "7404_noPower.dig", Collections.singletonList(inNet), outNet);
                return outNet;
            }
        } else if (expr instanceof Operation.And) {
            return processAnd((Operation.And) expr);
        } else if (expr instanceof Operation.Or) {
            return processOr((Operation.Or) expr);
        } else if (expr instanceof Operation.XOr) {
            return processXOr((Operation.XOr) expr);
        } else {
            throw new BuilderException("Unsupported expression: " + expr.getClass().getSimpleName());
        }
    }

    private String processAnd(Operation.And and) throws BuilderException {
        ArrayList<Expression> exprs = and.getExpressions();
        if (exprs.isEmpty()) {
            return getConstantNet(1);
        }
        if (exprs.size() == 1) {
            return processExpression(exprs.get(0));
        }

        String cur = processExpression(exprs.get(0));
        for (int i = 1; i < exprs.size(); i++) {
            String next = processExpression(exprs.get(i));
            String out = nextNetName();
            allocateGate("7408", "7408_noPower.dig", Arrays.asList(cur, next), out);
            cur = out;
        }
        return cur;
    }

    private String processOr(Operation.Or or) throws BuilderException {
        ArrayList<Expression> exprs = or.getExpressions();
        if (exprs.isEmpty()) {
            return getConstantNet(0);
        }
        if (exprs.size() == 1) {
            return processExpression(exprs.get(0));
        }

        String cur = processExpression(exprs.get(0));
        for (int i = 1; i < exprs.size(); i++) {
            String next = processExpression(exprs.get(i));
            String out = nextNetName();
            allocateGate("7432", "7432_noPower.dig", Arrays.asList(cur, next), out);
            cur = out;
        }
        return cur;
    }

    private String processXOr(Operation.XOr xor) throws BuilderException {
        ArrayList<Expression> exprs = xor.getExpressions();
        if (exprs.isEmpty()) {
            return getConstantNet(0);
        }
        if (exprs.size() == 1) {
            return processExpression(exprs.get(0));
        }

        String cur = processExpression(exprs.get(0));
        for (int i = 1; i < exprs.size(); i++) {
            String next = processExpression(exprs.get(i));
            String out = nextNetName();
            allocateGate("7486", "7486_noPower.dig", Arrays.asList(cur, next), out);
            cur = out;
        }
        return cur;
    }

    private String processNAnd(Operation.And and) throws BuilderException {
        ArrayList<Expression> exprs = and.getExpressions();
        if (exprs.isEmpty()) {
            return getConstantNet(0);
        }
        if (exprs.size() == 1) {
            String inNet = processExpression(exprs.get(0));
            String outNet = nextNetName();
            allocateGate("7404", "7404_noPower.dig", Collections.singletonList(inNet), outNet);
            return outNet;
        }
        if (exprs.size() == 2) {
            String in1 = processExpression(exprs.get(0));
            String in2 = processExpression(exprs.get(1));
            String out = nextNetName();
            allocateGate("7400", "7400_noPower.dig", Arrays.asList(in1, in2), out);
            return out;
        }
        String cur = processExpression(exprs.get(0));
        for (int i = 1; i < exprs.size() - 1; i++) {
            String next = processExpression(exprs.get(i));
            String out = nextNetName();
            allocateGate("7408", "7408_noPower.dig", Arrays.asList(cur, next), out);
            cur = out;
        }
        String last = processExpression(exprs.get(exprs.size() - 1));
        String out = nextNetName();
        allocateGate("7400", "7400_noPower.dig", Arrays.asList(cur, last), out);
        return out;
    }

    private String processNOr(Operation.Or or) throws BuilderException {
        ArrayList<Expression> exprs = or.getExpressions();
        if (exprs.isEmpty()) {
            return getConstantNet(1);
        }
        if (exprs.size() == 1) {
            String inNet = processExpression(exprs.get(0));
            String outNet = nextNetName();
            allocateGate("7404", "7404_noPower.dig", Collections.singletonList(inNet), outNet);
            return outNet;
        }
        if (exprs.size() == 2) {
            String in1 = processExpression(exprs.get(0));
            String in2 = processExpression(exprs.get(1));
            String out = nextNetName();
            allocateGate("7402", "7402_noPower.dig", Arrays.asList(in1, in2), out);
            return out;
        }
        String cur = processExpression(exprs.get(0));
        for (int i = 1; i < exprs.size() - 1; i++) {
            String next = processExpression(exprs.get(i));
            String out = nextNetName();
            allocateGate("7432", "7432_noPower.dig", Arrays.asList(cur, next), out);
            cur = out;
        }
        String last = processExpression(exprs.get(exprs.size() - 1));
        String out = nextNetName();
        allocateGate("7402", "7402_noPower.dig", Arrays.asList(cur, last), out);
        return out;
    }

    private String processXNOr(Operation.XOr xor) throws BuilderException {
        ArrayList<Expression> exprs = xor.getExpressions();
        if (exprs.isEmpty()) {
            return getConstantNet(1);
        }
        if (exprs.size() == 1) {
            String inNet = processExpression(exprs.get(0));
            String outNet = nextNetName();
            allocateGate("7404", "7404_noPower.dig", Collections.singletonList(inNet), outNet);
            return outNet;
        }
        if (exprs.size() == 2) {
            String in1 = processExpression(exprs.get(0));
            String in2 = processExpression(exprs.get(1));
            String out = nextNetName();
            allocateGate("747266", "747266_noPower.dig", Arrays.asList(in1, in2), out);
            return out;
        }
        String cur = processExpression(exprs.get(0));
        for (int i = 1; i < exprs.size() - 1; i++) {
            String next = processExpression(exprs.get(i));
            String out = nextNetName();
            allocateGate("7486", "7486_noPower.dig", Arrays.asList(cur, next), out);
            cur = out;
        }
        String last = processExpression(exprs.get(exprs.size() - 1));
        String out = nextNetName();
        allocateGate("747266", "747266_noPower.dig", Arrays.asList(cur, last), out);
        return out;
    }

    private void allocateGate(String chipType, String chipFile, List<String> inputNets, String outputNet) {
        int maxSlots = "7404".equals(chipType) ? 6 : 4;

        ChipInstance targetChip = null;
        for (ChipInstance ci : chipInstances) {
            if (ci.chipType.equals(chipType) && ci.slotsUsed < ci.maxSlots) {
                targetChip = ci;
                break;
            }
        }

        if (targetChip == null) {
            chipCounter++;
            targetChip = new ChipInstance(chipType, chipFile, chipCounter, maxSlots);
            chipInstances.add(targetChip);
        }

        targetChip.slotsUsed++;
        int slot = targetChip.slotsUsed;
        targetChip.allocatedGates.add(new Gate(slot, inputNets, outputNet));
    }

    private static class ChipInstance {
        private final String chipType;
        private final String chipFile;
        private final int id;
        private final int maxSlots;
        private int slotsUsed = 0;
        private final List<Gate> allocatedGates = new ArrayList<>();

        ChipInstance(String chipType, String chipFile, int id, int maxSlots) {
            this.chipType = chipType;
            this.chipFile = chipFile;
            this.id = id;
            this.maxSlots = maxSlots;
        }
    }

    private static class Gate {
        private int slot;
        private final List<String> inputNets;
        private final String outputNet;

        Gate(int slot, List<String> inputNets, String outputNet) {
            this.slot = slot;
            this.inputNets = new ArrayList<>(inputNets);
            this.outputNet = outputNet;
        }
    }

    private static class GatePinPositions {
        private final Vector inputA;
        private final Vector inputB;
        private final Vector output;

        GatePinPositions(Vector inputA, Vector inputB, Vector output) {
            this.inputA = inputA;
            this.inputB = inputB;
            this.output = output;
        }
    }
}
