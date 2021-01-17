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

package org.graalvm.visualizer.source.impl;

import org.graalvm.visualizer.data.ChangedListener;
import org.graalvm.visualizer.data.InputGraph;
import org.graalvm.visualizer.data.InputNode;
import org.graalvm.visualizer.graph.Figure;
import org.graalvm.visualizer.source.GraphSource;
import org.graalvm.visualizer.source.Location;
import org.graalvm.visualizer.source.NodeLocationContext;
import org.graalvm.visualizer.source.NodeLocationEvent;
import org.graalvm.visualizer.source.NodeLocationListener;
import org.graalvm.visualizer.source.SourceUtils;
import org.graalvm.visualizer.view.api.DiagramViewer;
import org.graalvm.visualizer.view.api.DiagramViewerLocator;
import org.openide.cookies.EditorCookie;
import org.openide.modules.OnStart;
import org.openide.nodes.Node;
import org.openide.util.Lookup;
import org.openide.util.LookupEvent;
import org.openide.util.LookupListener;
import org.openide.util.RequestProcessor;
import org.openide.util.WeakListeners;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.openide.util.Utilities;
import java.util.HashSet;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import org.graalvm.visualizer.data.services.InputGraphProvider;
import org.graalvm.visualizer.view.api.DiagramViewerAdapter;
import org.graalvm.visualizer.view.api.DiagramViewerEvent;
import org.graalvm.visualizer.view.api.DiagramViewerListener;

/**
 * Synchronizes {@link NodeLocationContext} between the graph viewer and editors.
 * 
 * @author sdedic
 */
@OnStart
public class LocContextSynchronizer implements PropertyChangeListener, Runnable, LookupListener, NodeLocationListener {
    private final NodeLocationContext       locContext;
    private final TopComponent.Registry     registry;
    private Reference<TopComponent>         lastGraphComponentRef = new WeakReference<>(null);
    
    private boolean inSync;
    
    public LocContextSynchronizer() {
        locContext = Lookup.getDefault().lookup(NodeLocationContext.class);
        registry = WindowManager.getDefault().getRegistry();
        registry.addPropertyChangeListener(WeakListeners.propertyChange(this, registry));
        locContext.addNodeLocationListener(WeakListeners.create(NodeLocationListener.class, this, locContext));
    }

    @Override
    public void run() {
    }

    class R extends WeakReference<TopComponent> implements Runnable {
        public R(TopComponent referent) {
            super(referent, Utilities.activeReferenceQueue());
        }

        @Override
        public void run() {
            cleanupIfLast(this);
        }
    }
    
    private void cleanupIfLast(Reference<TopComponent> ref) {
        synchronized (this) {
            if (this.lastGraphComponentRef != ref) {
                return;
            }
        }
        locContext.setGraphContext(null, Collections.emptyList());
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        String p = evt.getPropertyName();
        if (TopComponent.Registry.PROP_TC_CLOSED.equals(p)) {
            TopComponent cl = (TopComponent)evt.getNewValue();
            TopComponent last = lastGraphComponentRef.get();
            if (cl == last) {
                synchronized (this) {
                    lastGraphComponentRef = new WeakReference(null);
                }
                locContext.setGraphContext(null, Collections.emptyList());
            }
            return;
        }
        if (!TopComponent.Registry.PROP_ACTIVATED_NODES.equals(p)) {
            return;
        }
        if (inSync) {
            return;
        }
        TopComponent tc = registry.getActivated();
        if (tc == null || !tc.isOpened()) {
            return;
        }
        InputGraph graph = findGraph(tc);
        TopComponent last = lastGraphComponentRef.get();
        Collection<InputNode> graphNodes = findNodes(tc, graph);
        
        if (graphNodes.isEmpty()) {
            EditorCookie ck = tc.getLookup().lookup(EditorCookie.class);
            if (ck != null) {
                // check that a graph viewer is available
                DiagramViewerLocator loc = Lookup.getDefault().lookup(DiagramViewerLocator.class);
                if (loc != null && loc.getActiveViewer() != null) {
                    JEditorPane[] panes = ck.getOpenedPanes();
                    if (panes != null) {
                        for (JEditorPane ep : panes) {
                            if (SwingUtilities.isDescendingFrom(ep, tc)) {
                                updateContextFromEditor(ep);
                                return;
                            }
                        }
                    }
                }
            } else {
                if (evListener != null) {
                    evListener.detach();
                    evListener = null;
                }
            }
        } else {
            synchronized (this) {
                if (tc != last) {
                    lastGraphComponentRef = new R(tc);
                }
                last = tc;
            }
        }
        if (graph == null && last != null) {
            return;
        }
        inSync = true;
        try {
            locContext.setGraphContext(graph, graphNodes);
        } finally {
            inSync = false;
        }
    }
    
    private static final RequestProcessor EDITSYNC_RP = new RequestProcessor();
    
    private RequestProcessor.Task delayedSync;
    
    /**
     * Attempts to update the shared context based on the editor caret position.
     */
    class EditorAndViewerListener extends DiagramViewerAdapter implements CaretListener, Runnable {
        private final Reference<DiagramViewer>    refViewer;
        private final DiagramViewerListener viewerL;
        private final CaretListener caretL;
        private final Reference<JEditorPane> refPane;

        public EditorAndViewerListener(DiagramViewer viewer, JEditorPane pane) {
            this.refViewer = new WeakReference<>(viewer);
            this.refPane = new WeakReference<>(pane);
            
            caretL = WeakListeners.create(CaretListener.class, this, pane);
            pane.addCaretListener(caretL);
            viewerL = WeakListeners.create(DiagramViewerListener.class, this, viewer);
            viewer.addDiagramViewerListener(viewerL);
        }
        
        @Override
        public void caretUpdate(CaretEvent e) {
            updateContextFromEditor((JEditorPane)e.getSource());
        }

        @Override
        public void diagramChanged(DiagramViewerEvent ev) {
            JEditorPane pane = refPane.get();
            if (pane == null) {
                detach();
            }
            updateContextFromEditor(pane);
        }
        
        void detach() {
            JEditorPane p = refPane.get();
            DiagramViewer v = refViewer.get();
            if (p != null && caretL != null) {
                p.removeCaretListener(caretL);
            }
            if (v != null && viewerL != null) {
                v.removeDiagramViewerListener(viewerL);
            }
            refPane.clear();
        }
        
        boolean matches(DiagramViewer v, JEditorPane p) {
            return refViewer.get() == v && refPane.get() == p;
        }

        @Override
        public void run() {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(this);
                return;
            }
            JEditorPane pane = refPane.get();
            if (pane == null || !SwingUtilities.isDescendingFrom(pane, registry.getActivated())) {
                return;
            }
            
    