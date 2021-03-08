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

package org.graalvm.visualizer.source;

import org.graalvm.visualizer.source.spi.StackProcessor;
import org.graalvm.visualizer.source.spi.LocationServices;
import org.graalvm.visualizer.data.InputGraph;
import org.graalvm.visualizer.data.InputNode;
import org.openide.filesystems.FileObject;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;
import org.openide.util.Utilities;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Represents relations between the graph and the original source(s)
 */
public final class GraphSource {
    private final RequestProcessor loaderProc = new RequestProcessor(GraphSource.class.getName(), 3);
    
    private final Reference<InputGraph> graph;
    private final FileRegistry fileRegistry;
    
    private final Map<Location, Set<Location>> children = new HashMap<>();
    private final Map<FileObject, List<Location>> fileLocations = new HashMap<>();
    private final Map<FileKey, Collection<Location>> keyLocations = new HashMap<>();
    private final Object[] stackData;
    private final Reference<NodeStack>[] nodeStacks;
    
    /**
     * Map of 'final' locations to node IDs
     */
    private final Map<Location, Object> nodeMap = new HashMap<>();
    
    /**
     * Cache of all locations, to cannonicalize stack entries in the graph
     */
    private final Map<Location, Location> uniqueLocations = new HashMap<>();

    GraphSource(InputGraph graph, FileRegistry fileRegistry) {
        this.graph = new G(graph);
        this.fileRegistry = fileRegistry;
        
        int s = graph.getHighestNodeId() + 1;
        this.stackData = new Object[s];
        this.nodeStacks = new Reference[s];
        fileRegistry.addFileRegistryListener(new LocationUpdater(this));
    }
    
    FileRegistry getFileRegistry() {
        return fileRegistry;
    }
    
    public InputGraph getGraph() {
        return graph.get();
    }
    
    private void reset() {
        synchronized (children) {
            computed = false;
            children.clear();
            fileLocations.clear();
            keyLocations.clear();
            nodeMap.clear();
        }
    }
    
    class G extends WeakReference<InputGraph> implements Runnable {
        public G(InputGraph referent) {
            super(referent, Utilities.activeReferenceQueue());
        }

        @Override
        public v