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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.neemann.digital.draw.shapes.GenericShape.SIZE;

/**
 * Circuit builder that maps generated expressions onto 74xx DIL chips.
 * Connections between inputs, chips, and outputs are routed using wires,
 * while unused gate inputs are tied to Ground using tunnels.
 */
public class CircuitBuilder74xx {

    private final ShapeFactory shapeFactory;
    private final Collection<Variable> declaredVars;
    private final Map<String, Expression> combinatorialOutputs;
    private final Map<String, Expression> sequentialOutputs;
    private final boolean useSwitchesAndLeds;
    private final ModelAnalyserInfo mai;

    private final List<ChipInstance> chipInstances = new ArrayList<>();
    private final Map<String, Integer> chipCounter = new HashMap<>();
    private int netCounter = 0;
    private boolean const1Used = false;
    private boolean const0Used = false;

    private enum PinSide {
        INPUT, CHIP_LEFT, CHIP_RIGHT, OUTPUT
    }

    private static class Endpoint {
        private final PinSide side;
        private final int y;

        Endpoint(PinSide side, int y) {
            this.side = side;
            this.y = y;
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
        private final int yBottom;

        RoutingLayout(int xInput, int xChip, int xChipRight, int xOutput,
                      int xLeftTrackStart, int xRightTrackStart, int yBottom) {
            this.xInput = xInput;
            this.xChip = xChip;
            this.xChipRight = xChipRight;
            this.xOutput = xOutput;
            this.xLeftTrackStart = xLeftTrackStart;
            this.xRightTrackStart = xRightTrackStart;
            this.yBottom = yBottom;
        }
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

        padUnusedGateInputs();

        LinkedHashSet<String> inputs = findInputVariables();
        Map<String, NetRoute> netRoutes = new LinkedHashMap<>();

        int xInput = 0;
        int yInput = placeInputs(circuit, inputs, netRoutes, xInput);

        int chipPitchY = 18 * SIZE;
        List<Integer> chipYPositions = new ArrayList<>();
        List<VisualElement> chipVisualElements = prepareChips(netRoutes, chipYPositions, chipPitchY);

        registerOutputEndpoints(combResultNets, netRoutes);

        List<String> leftNets = new ArrayList<>();
        List<String> rightNets = new ArrayList<>();
        Set<String> bridgedNets = new HashSet<>();
        classifyNets(netRoutes, leftNets, rightNets, bridgedNets);

        int xLeftTrackStart = xInput + 6 * SIZE;
        int xChip = xLeftTrackStart + (leftNets.size() + 4) * SIZE;
        int xChipRight = xChip + 6 * SIZE;
        int xRightTrackStart = xChipRight + 5 * SIZE;
        int xOutput = xRightTrackStart + (rightNets.size() + 4) * SIZE;

        placeChipsInCircuit(circuit, chipVisualElements, chipYPositions, xChip);
        int yOutput = placeOutputsInCircuit(circuit, combResultNets, xOutput);

        int yBottom = Math.max(yInput, Math.max(yOutput, chipInstances.size() * chipPitchY)) + 4 * SIZE;
        RoutingLayout layout = new RoutingLayout(xInput, xChip, xChipRight, xOutput,
                xLeftTrackStart, xRightTrackStart, yBottom);

        routeNets(circuit, netRoutes, leftNets, rightNets, new ArrayList<>(bridgedNets), layout);

        placeConstants(circuit, xInput, yInput);

        if (!seqResultNets.isEmpty()) {
            placeSequential(circuit, seqResultNets, xOutput, yOutput);
        }

        return circuit;
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
                            Map<String, NetRoute> netRoutes, int xInput) {
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

            getRoute(netRoutes, inName).leftEndpoints.add(new Endpoint(PinSide.INPUT, yInput));
            yInput += 4 * SIZE;
        }
        return yInput;
    }

    private List<VisualElement> prepareChips(Map<String, NetRoute> netRoutes,
                                             List<Integer> chipYPositions, int chipPitchY) {
        List<VisualElement> chipVisualElements = new ArrayList<>();
        for (int i = 0; i < chipInstances.size(); i++) {
            ChipInstance ci = chipInstances.get(i);
            int chipY = i * chipPitchY;
            chipYPositions.add(chipY);

            VisualElement chipVe = new VisualElement(ci.chipFile).setShapeFactory(shapeFactory);
            chipVe.getElementAttributes().set(Keys.LABEL, "IC" + ci.id + " (" + ci.chipType + ")");
            chipVisualElements.add(chipVe);

            for (Gate g : ci.allocatedGates) {
                int slot = g.slot;
                registerGatePin(netRoutes, chipVe, chipY, slot + "A", g.inputNets.size() >= 1 ? g.inputNets.get(0) : null);
                if (!"7404".equals(ci.chipType)) {
                    registerGatePin(netRoutes, chipVe, chipY, slot + "B", g.inputNets.size() >= 2 ? g.inputNets.get(1) : null);
                }
                if (g.outputNet != null) {
                    registerGatePin(netRoutes, chipVe, chipY, slot + "Y", g.outputNet);
                }
            }
        }
        return chipVisualElements;
    }

    private void registerOutputEndpoints(LinkedHashMap<String, String> combResultNets,
                                         Map<String, NetRoute> netRoutes) {
        int y = SIZE;
        for (Map.Entry<String, String> entry : combResultNets.entrySet()) {
            String netName = entry.getValue();
            if (!"CONST_0".equals(netName) && !"CONST_1".equals(netName)) {
                getRoute(netRoutes, netName).rightEndpoints.add(new Endpoint(PinSide.OUTPUT, y));
            }
            y += 4 * SIZE;
        }
    }

    private void classifyNets(Map<String, NetRoute> netRoutes,
                              List<String> leftNets, List<String> rightNets, Set<String> bridgedNets) {
        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            String name = entry.getKey();
            NetRoute route = entry.getValue();
            boolean hasLeft = !route.leftEndpoints.isEmpty();
            boolean hasRight = !route.rightEndpoints.isEmpty();
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

    private void placeChipsInCircuit(Circuit circuit, List<VisualElement> chipVisualElements,
                                     List<Integer> chipYPositions, int xChip) {
        for (int i = 0; i < chipVisualElements.size(); i++) {
            VisualElement chipVe = chipVisualElements.get(i);
            int chipY = chipYPositions.get(i);
            chipVe.setPos(new Vector(xChip, chipY));
            circuit.add(chipVe);

            ChipInstance ci = chipInstances.get(i);
            for (Gate g : ci.allocatedGates) {
                if (g.outputNet == null) {
                    tunnelUnusedPin(circuit, chipVe, xChip, chipY, g.slot + "A");
                    if (!"7404".equals(ci.chipType)) {
                        tunnelUnusedPin(circuit, chipVe, xChip, chipY, g.slot + "B");
                    }
                }
            }
        }
    }

    private int placeOutputsInCircuit(Circuit circuit, LinkedHashMap<String, String> combResultNets, int xOutput) {
        int yOutput = SIZE;
        for (Map.Entry<String, String> entry : combResultNets.entrySet()) {
            String outName = entry.getKey();
            String netName = entry.getValue();
            Vector outPos = new Vector(xOutput, yOutput);

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
                VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
                tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_0").set(Keys.ROTATE, new Rotation(2));
                tunnel.setPos(wireEnd);
                circuit.add(tunnel);
                const0Used = true;
            } else if ("CONST_1".equals(netName)) {
                Vector wireEnd = outPos.add(-2 * SIZE, 0);
                circuit.add(new Wire(wireEnd, outPos));
                VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
                tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_1").set(Keys.ROTATE, new Rotation(2));
                tunnel.setPos(wireEnd);
                circuit.add(tunnel);
                const1Used = true;
            }

            yOutput += 4 * SIZE;
        }
        return yOutput;
    }

    private NetRoute getRoute(Map<String, NetRoute> netRoutes, String name) {
        return netRoutes.computeIfAbsent(name, k -> new NetRoute());
    }

    private void registerGatePin(Map<String, NetRoute> netRoutes, VisualElement chipVe, int chipY,
                                 String pinName, String netName) {
        if (netName == null || "CONST_0".equals(netName) || "CONST_1".equals(netName)) {
            return;
        }
        Vector offset = getPinOffset(chipVe, pinName);
        if (offset == null) {
            return;
        }
        boolean isLeft = (offset.x == 0);
        int py = chipY + offset.y;
        if (isLeft) {
            getRoute(netRoutes, netName).leftEndpoints.add(new Endpoint(PinSide.CHIP_LEFT, py));
        } else {
            getRoute(netRoutes, netName).rightEndpoints.add(new Endpoint(PinSide.CHIP_RIGHT, py));
        }
    }

    private void tunnelUnusedPin(Circuit circuit, VisualElement chipVe, int chipX, int chipY, String pinName) {
        Vector offset = getPinOffset(chipVe, pinName);
        if (offset == null) {
            return;
        }
        Vector pinPos = new Vector(chipX, chipY).add(offset);
        boolean isLeft = (offset.x == 0);
        int dx = isLeft ? -2 * SIZE : 2 * SIZE;
        Vector wireEnd = pinPos.add(dx, 0);

        circuit.add(new Wire(pinPos, wireEnd));

        VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
        tunnel.getElementAttributes()
                .set(Keys.NETNAME, "CONST_0")
                .set(Keys.ROTATE, new Rotation(isLeft ? 2 : 0));
        tunnel.setPos(wireEnd);
        circuit.add(tunnel);
        const0Used = true;
    }

    private static class SideConfig {
        private final int xTrunk;
        private final int xOuter;
        private final int xInner;
        private final PinSide outerSide;

        SideConfig(int xTrunk, int xOuter, int xInner, PinSide outerSide) {
            this.xTrunk = xTrunk;
            this.xOuter = xOuter;
            this.xInner = xInner;
            this.outerSide = outerSide;
        }
    }

    private void routeNets(Circuit circuit, Map<String, NetRoute> netRoutes,
                           List<String> leftNets, List<String> rightNets, List<String> bridgedList,
                           RoutingLayout layout) {

        for (Map.Entry<String, NetRoute> entry : netRoutes.entrySet()) {
            String netName = entry.getKey();
            NetRoute route = entry.getValue();
            boolean isBridged = bridgedList.contains(netName);
            int yBridge = isBridged ? layout.yBottom + (bridgedList.indexOf(netName) + 1) * SIZE : -1;

            if (!route.leftEndpoints.isEmpty()) {
                SideConfig cfg = new SideConfig(layout.xLeftTrackStart + leftNets.indexOf(netName) * SIZE,
                        layout.xInput, layout.xChip, PinSide.INPUT);
                routeSide(circuit, route.leftEndpoints, cfg, yBridge);
            }

            if (!route.rightEndpoints.isEmpty()) {
                SideConfig cfg = new SideConfig(layout.xRightTrackStart + rightNets.indexOf(netName) * SIZE,
                        layout.xOutput, layout.xChipRight, PinSide.OUTPUT);
                routeSide(circuit, route.rightEndpoints, cfg, yBridge);
            }

            if (isBridged) {
                int xTrunkLeft = layout.xLeftTrackStart + leftNets.indexOf(netName) * SIZE;
                int xTrunkRight = layout.xRightTrackStart + rightNets.indexOf(netName) * SIZE;
                circuit.add(new Wire(new Vector(xTrunkLeft, yBridge), new Vector(xTrunkRight, yBridge)));
            }
        }
    }

    private void routeSide(Circuit circuit, List<Endpoint> endpoints, SideConfig cfg, int yBridge) {
        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;

        for (Endpoint ep : endpoints) {
            int px = (ep.side == cfg.outerSide) ? cfg.xOuter : cfg.xInner;
            int py = ep.y;
            if (px != cfg.xTrunk) {
                circuit.add(new Wire(new Vector(px, py), new Vector(cfg.xTrunk, py)));
            }
            yMin = Math.min(yMin, py);
            yMax = Math.max(yMax, py);
        }

        if (yBridge >= 0) {
            yMin = Math.min(yMin, yBridge);
            yMax = Math.max(yMax, yBridge);
        }

        if (yMin < yMax) {
            circuit.add(new Wire(new Vector(cfg.xTrunk, yMin), new Vector(cfg.xTrunk, yMax)));
        }
    }

    private void placeConstants(Circuit circuit, int xInput, int yInput) {
        if (const1Used) {
            VisualElement vdd = new VisualElement(VDD.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            vdd.setPos(new Vector(xInput, yInput));
            circuit.add(vdd);
            Vector wireEnd = new Vector(xInput + 2 * SIZE, yInput);
            circuit.add(new Wire(new Vector(xInput, yInput), wireEnd));
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
            VisualElement tunnel = new VisualElement(Tunnel.DESCRIPTION.getName()).setShapeFactory(shapeFactory);
            tunnel.getElementAttributes().set(Keys.NETNAME, "CONST_0");
            tunnel.setPos(wireEnd);
            circuit.add(tunnel);
        }
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
            int id = chipCounter.getOrDefault(chipType, 0) + 1;
            chipCounter.put(chipType, id);
            targetChip = new ChipInstance(chipType, chipFile, id, maxSlots);
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
        private final int slot;
        private final List<String> inputNets;
        private final String outputNet;

        Gate(int slot, List<String> inputNets, String outputNet) {
            this.slot = slot;
            this.inputNets = inputNets;
            this.outputNet = outputNet;
        }
    }
}
