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
        public void run() {
            reset();
        }
    }
    
    Location uniqueLocation(Location l) {
        synchronized (uniqueLocations) {
            Location orig = uniqueLocations.putIfAbsent(l, l);
            return orig == null ? l : orig;
        }
    }
    
    public Location findNodeLocation(InputNode n) {
        InputGraph g = getGraph();
        if (g == null) {
            return null;
        }
        synchronized (children) {
            if (!g.getNodes().contains(n)) {
                return null;
            }
        }
        NodeStack st = getNodeStack(n);
        return st == null ? null : st.top().getLocation();
    }
    
    public Collection<InputNode> getNodesAt(Location l) {
        Set<InputNode> nodes = new HashSet<>();
        synchronized (children) {
            InputGraph g = getGraph();
            if (g == null) {
                return Collections.emptySet();
            }
            Object o = nodeMap.get(l);
            Collection<StackData> data;
            if (o == null) {
                return Collections.emptySet();
            }
            if (o instanceof Collection) {
                data = ((Collection)o);
            } else {
                data = Collections.singletonList((StackData)o);
            }
            for (StackData sd : data) {
                InputNode n = g.getNode(sd.getNodeId());
                if (n != null) {
                    nodes.add(n);
                }
            }
        }
        return nodes;
    }
    
    Collection<FileKey>  getFileKeys() {
        compute(null);
        synchronized (children) {
            Set<FileKey> result = new HashSet<>(keyLocations.keySet());
            for (Map.Entry<FileObject, List<Location>> e : fileLocations.entrySet()) {
                FileObject f = e.getKey();
                Collection<Location> c = e.getValue();
                FileKey k;
                if (!c.isEmpty()) {
                    k = c.iterator().next().getFile();
                } else {
                    Language lng = Language.getRegistry().findLanguageByMime(f.getMIMEType());
                    if (lng == null) {
                        continue;
                    }
                    k = FileKey.fromFile(f);
                }
                result.add(k);
            }
            return result;
        }
    }
    
    Collection<Location> getFileLocations(FileKey fk, boolean nodesPresent) {
        if (fk.isResolved()) {
            return getFileLocations(fk.getResolvedFile(), nodesPresent);
        }
        Collection<Location> locs;
        synchronized (children) {
            locs = keyLocations.get(fk);
        }
        return filterLocations(new ArrayList<>(locs), nodesPresent);
    }
    
    private Loader createLoader(InputGraph g, Collection<InputNode> nodesToLoad) {
        Collection<? extends StackProcessor.Factory> factories = Lookup.getDefault().lookupAll(StackProcessor.Factory.class);
        Map<String, ProcessorContext> contexts = new HashMap<>();
        String[] allIds = null;
        for (StackProcessor.Factory f : factories) {
            String[] ids = f.getLanguageIDs();
            if (ids == null) {
                if (allIds == null) {
                    allIds = Language.getRegistry().getMimeTypes().toArray(new String[1]);
                }
                ids = allIds;
            }
            for (String m : ids) {
                ProcessorContext ctx = contexts.computeIfAbsent(m, (mime) -> new ProcessorContext(this, g, fileRegistry, mime));
                StackProcessor p = f.createProcessor(ctx);
                if (p == null) {
                    continue;
                }
                ctx.addProcessor(p);
            }
        }
        return new Loader(contexts.values(), nodesToLoad);
    }
    
    private int compareLine(Location l1, Location l2) {
        return l1.getLine() - l2.getLine();
    }
    
    // @GuardedBy(children)
    private void mergeResults(ProcessorContext ctx) {
        // merge children
        if (this.children.isEmpty()) {
            this.children.putAll(ctx.successors);
        } else {
            for (Map.Entry en : ctx.successors.entrySet()) {
                Location parent = (Location)en.getKey();
                Set<Location> nue = (Set<Location>)en.getValue();
                
                Set<Location> existing = children.get(parent);
                if (existing == null) {
                    children.put(parent, nue);
                } else {
                    existing.addAll(nue);
                }
            }
        }
        
        // merge file locations
        for (Map.Entry en : ctx.fileLocations.entrySet()) {
            FileObject f = (FileObject)en.getKey();
            List l = (List)en.getValue();
            List<Location> locs = fileLocations.putIfAbsent(f, l);
            if (locs != null) {
                Set<Location> x = new HashSet<>(locs);
                x.addAll(l);
                locs = new ArrayList<>(x);
                fileLocations.put(f, locs);
            } else {
                locs = l;
            }
            Collections.sort(locs, this::compareLine);
        }
        for (Map.Entry en : ctx.keyLocations.entrySet()) {
            FileKey f = (FileKey)en.getKey();
            List l = (List)en.getValue();
            if (f.isResolved()) {
                List<Location> locs = fileLocations.putIfAbsent(f.getResolvedFile(), l);
                if (locs != null) {
                    Set<Location> all = new HashSet<>(locs);
                    all.addAll(l);
                    locs = new ArrayList<>(all);
                    fileLocations.put(f.getResolvedFile(), locs);
                } else {
                    locs = l;
                }
                Collections.sort(locs, this::compareLine);
            } else {
                keyLocations.computeIfAbsent(f, (x) -> new HashSet<Location>()).addAll(l);
            }
        }
        
        // merge final locations
        for (Map.Entry<Location, Collection<StackData>> en : ctx.finalLocations.entrySet()) {
            addNodeMap(en.getKey(), en.getValue());
        }
    }
    
    private volatile boolean computed;
    
    private ScheduledFuture computeTask;
    
    class Loader implements Runnable {
        private final Collection<ProcessorContext> contexts;
        private Collection<InputNode> nodesToLoad;
        
        
        public Loader(Collection<ProcessorContext> contexts, Collection<InputNode> nodesToLoad) {
            this.contexts = new ArrayList<>(contexts);
            this.nodesToLoad = nodesToLoad == null ? null : new HashSet<>(nodesToLoad);
        }
        
        @Override
        public void run() {
            boolean fullLoad = nodesToLoad == null;
            try {
                InputGraph g = graph.get();
                if (g == null) {
                    return;
                }
                if (!fullLoad) {
                    synchronized (children) {
                        for (InputNode n : new ArrayList<>(nodesToLoad)) {
                            if (stackData[n.getId()] != null) {
                                nodesToLoad.remove(n);
                            }
                        }
                    }
                }
                if (nodesToLoad == null) {
                    nodesToLoad = g.getNodes();
                }
                for (InputNode n : nodesToLoad) {
                    for (ProcessorContext c : contexts) {
                        c.processNode(n);
                    }
                }
                synchronized (children) {
                    if (computed) {
                        return;
                    }
                    if (fullLoad) {
                        reset();
                    }
                    for (ProcessorContext c : contexts) {
                        mergeResults(c);
                    }
                    if (fullLoad) {
                        computed = true;
                    } else {
                        
                    }
                    computeTask = null;
                }
            } catch (Throwable ex) {
                Exceptions.printStackTrace(ex);
            }
        }
    }
    
    // @GuardedBy(children)
    private void addNodeMap(Location l, Collection<StackData> nodes) {
        Object o = nodeMap.get(l);
        if (o == null) {
            if (!nodes.isEmpty()) {
                if (nodes.size() == 1) {
                    nodeMap.put(l, nodes.iterator().next());
                } else {
                    nodeMap.put(l, nodes);
                }
            }
        } else if (o instanceof Collection) {
            ((Collection)o).addAll(nodes);
        } else if (!nodes.isEmpty()) {
            Collection c = new ArrayList<>();
            c.addAll(nodes);
            c.add((StackData)o);
            nodeMap.put(l, c);
        }
        for (StackData sd : nodes) {
            putStackData(sd);
        }
    }
    
    private void keysResolved(Collection<FileKey> keys) {
        synchronized (children) {
            for (FileKey rk : keys) {
                Collection<Location> newLocs = keyLocations.remove(rk);
                if (newLocs == null) {
                    continue;
                }
                FileObject f = rk.getResolvedFile();
                List<Location> locs = fileLocations.get(f);
                if (locs != null) {
                    newLocs.addAll(locs);
                    locs = new ArrayList<>(newLocs);
                } else {
                    locs = new ArrayList<>(newLocs);
                    fileLocations.put(f, locs);
                }
                Collections.sort(locs, this::compareLine);
            }
        }
        // XXX some change event ?
    }
    
    public Collection<FileObject>   getSourceFiles() {
        compute(null);
        synchronized (children) {
            return new HashSet<>(fileLocations.keySet());
        }
    }
    
    /**
     * Gets all locations within a file. If `nodePresent' is true, only locations
     * which contains an InputNode are returned. Otherwise the method returns
     * locations within the file, which some node stack passes through.
     * @param f file 
     * @param nodePresent if true, only locations where a Node was generated from
     * are returned
     * @return list of locations, sorted by line number
     */
    public List<Location> getFileLocations(FileObject f, boolean nodePresent) {
        compute(null);
        List<Location> locs;
        synchronized (children) {
            locs = fileLocations.get(f);
        }
        return filterLocations(locs, nodePresent);
    }
    
    private List<Location> filterLocations(List<Location> locs, boolean nodePresent) {
        if (locs == null || locs.isEmpty()) {
            return Collections.emptyList();
        } else if (!nodePresent) {
            return locs;
        }
        List<Location> result = new ArrayList<>(locs);
        synchronized (children) {
            for (Iterator<Location> it = result.iterator(); it.hasNext(); ) {
                Location l = it.next();
                if (!nodeMap.containsKey(l)) {
                    it.remove();
                }
            }
        }
        return result;
    }
    
    public Collection<InputNode>  findLangugageNodes(String mime) {
        compute(null);
        Collection<InputNode> result = new ArrayList<>();
        InputGraph g = graph.get();
        if (g == null) {
            return Collections.emptyList();
        }
        synchronized (children) {
            for (int i = 0; i < stackData.length; i++) {
                StackData sd = getStackData(i, mime);
                if (sd != null) {
                    result.add(g.getNode(i));
                }
            }
        }
        return result;
    }
    
    /**
     * Prepares data structures, in a dedicated thread.
     * @param nodes nodes to load
     * @return Future which becomes complete when data loads.
     */
    public Future<Void> prepare(Collection<InputNode> nodes) {
        InputGraph g = getGraph();
        if (g == null) {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            cf.complete(null);
            return cf;
        }
        synchronized (children) {
            boolean ready = computed;
            if (!ready && nodes != null) {
                ready = true;
                for (InputNode n : nodes) {
                    Reference<NodeStack> r = nodeStacks[n.getId()];
                    if (r == null) {
                        ready = false;
                        break;
                    }
                }
            }
            if (ready) {
                CompletableFuture<Void> cf = new CompletableFuture<Void>();
                cf.complete(null);
                return cf;
            }
            ScheduledFuture task;
            if (nodes == null) {
                if (computeTask == null) {
                    computeTask = loaderProc.schedule(createLoader(g, nodes), 0, TimeUnit.MILLISECONDS);
                }
                task = computeTask;
            } else {
                task = loaderProc.schedule(createLoader(g, nodes), 0, TimeUnit.MILLISECONDS);
            }
            return task;
        }
    }
    

    private void compute(Collection<InputNode> nodes) {
        try {
            prepare(nodes).get();
        } catch (InterruptedException | ExecutionException ex) {
        }
    }
    
    private void addStackResult(Collection<Nod