
/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.visualizer.hierarchicallayout;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.Iterator;
import org.graalvm.visualizer.layout.LayoutGraph;
import org.graalvm.visualizer.layout.LayoutManager;
import org.graalvm.visualizer.layout.Link;
import org.graalvm.visualizer.layout.Port;
import org.graalvm.visualizer.layout.Vertex;
import org.graalvm.visualizer.settings.layout.LayoutSettings.LayoutSettingBean;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.graalvm.visualizer.settings.layout.LayoutSettings;
import static org.graalvm.visualizer.settings.layout.LayoutSettings.*;

public class HierarchicalLayoutManager implements LayoutManager {

    private static final Logger LOG = Logger.getLogger(HierarchicalLayoutManager.class.getName());

    public static final boolean TRACE = false;
    public static final boolean CHECK = false;
    public static final int SWEEP_ITERATIONS = 1;
    public static final int CROSSING_ITERATIONS = 2;
    public static final int DUMMY_HEIGHT = 1;
    public static final int DUMMY_WIDTH = 1;
    public static final int X_OFFSET = 8;
    public static final int LAYER_OFFSET = 8;
    public static final int MAX_LAYER_LENGTH = -1;
    public static final int VIP_BONUS = 10;

    private final AtomicBoolean cancelled;

    @Override
    public boolean cancel() {
        cancelled.set(true);
        return true;
    }

    public enum Combine {
        NONE,
        SAME_INPUTS,
        SAME_OUTPUTS
    }

    private final LayoutSettingBean setting;
    // Options
    private final Combine combine;
    private final int dummyWidth;
    private final int offset;
    private int maxLayerLength;
    // Algorithm global datastructures
    private final Set<Link> reversedLinks;
    private final Set<LayoutEdge> longEdges;
    private final List<LayoutNode> nodes;
    private final List<LayoutNode> standAlones;
    private final HashMap<Vertex, LayoutNode> vertexToLayoutNode;
    private final HashMap<Link, List<Point>> reversedLinkStartPoints;
    private final HashMap<Link, List<Point>> reversedLinkEndPoints;
    private LayoutGraph graph;
    private LayoutLayer[] layers;
    private int layerCount;

    //preloaded setting to speedup execution
    private final boolean isDefaultLayout;
    private final boolean isDummyCrossing;
    private final boolean isCrossingByConnDiff;
    private final boolean isDelayDanglingNodes;
    private final boolean isDrawLongEdges;

    private class LayoutNode {

        public int x;
        public int y;
        public int width;
        public int height;
        public int layer = -1;
        public int xOffset;
        public int yOffset;
        public int bottomYOffset;
        public final Vertex vertex; // Only used for non-dummy nodes, otherwise null

        public final List<LayoutEdge> preds = new ArrayList<>();
        public final List<LayoutEdge> succs = new ArrayList<>();
        public int pos = -1; // Position within layer

        public float crossingNumber = 0;

        public void loadCrossingNumber(boolean up) {
            crossingNumber = 0;
            int count = loadCrossingNumber(up, this);
            if (count > 0) {
                crossingNumber /= count;
            } else {
                crossingNumber = 0;
            }
        }

        public int loadCrossingNumber(boolean up, LayoutNode source) {
            int count = 0;
            if (up) {
                count = succs.stream().map((e) -> e.loadCrossingNumber(up, source)).reduce(count, Integer::sum);
            } else {
                count = preds.stream().map((e) -> e.loadCrossingNumber(up, source)).reduce(count, Integer::sum);
            }
            return count;
        }

        @Override
        public String toString() {
            return "Node " + vertex;
        }

        public LayoutNode(Vertex v) {
            vertex = v;
            if (v == null) {
                height = DUMMY_HEIGHT;
                width = dummyWidth;
            } else {
                Dimension size = v.getSize();
                height = size.height;
                width = size.width;
            }
        }

        public LayoutNode() {
            this(null);
        }

        public Collection<LayoutEdge> getVisibleSuccs() {
            List<LayoutEdge> edges = new ArrayList<>();
            for (int i = layer + 1; i < layerCount; ++i) {
                if (layers[i].isVisible()) {
                    for (LayoutEdge e : succs) {
                        LayoutEdge last = e;
                        for (int l = layer + 1; l < i; ++l) {
                            for (LayoutEdge ed : last.to.succs) {
                                if (ed.link == e.link) {
                                    last = ed;
                                    break;
                                }
                            }
                        }
                        if (last == e) {
                            edges.add(e);
                        } else {
                            edges.add(new LayoutEdge(e.from, last.to, e.relativeFrom, last.relativeTo, e.link, e.vip));
                        }
                    }
                    break;
                }
            }
            return edges;
        }

        public int getLeftSide() {
            return xOffset + x;
        }

        public int getWholeWidth() {
            return xOffset + width;
        }

        public int getWholeHeight() {
            return height + yOffset + bottomYOffset;
        }

        public int getRightSide() {
            return x + getWholeWidth();
        }

        public int getCenterX() {
            return getLeftSide() + (width / 2);
        }

        public int getBottom() {
            return y + height;
        }

        public boolean isDummy() {
            return vertex == null;
        }
    }