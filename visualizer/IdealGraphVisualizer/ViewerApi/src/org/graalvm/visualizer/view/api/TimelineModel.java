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

package org.graalvm.visualizer.view.api;

import org.graalvm.visualizer.data.GraphContainer;
import org.graalvm.visualizer.data.InputGraph;
import org.graalvm.visualizer.util.RangeSliderModel;
import java.beans.PropertyChangeListener;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Timeline model provides view on the compilation split into several
 * graph types. It collects {@link RangeSliderModel}s (range) built upon {@link GraphContainers}
 * (partition) for recognized graph types. 
 * <p/>
 * The RangeSliderModels are configured so that the contain <b>gaps</b> in their
 * <code>slot</code> sequences, so that each slot corresponds to one graph in the
 * compilation Group - perhaps a graph from a different RangeSliderModel in the Timeline.
 * <p/>
 * The model has always one <b>primary</b> partition and range slider, and potentially several
 * secondaries. The Timeline model refires individual range change events, tracks
 * the former positions of each range and reports the old ones in events. It also reports
 * changes to the number of partitions, if e.g. graphs are added or removed to/from the
 * group.
 * <p/>
 * TimelineModel initializes lazily; it starts with primary partition that consists of
 * one empty Graph. Then it loads data outside EDT and replans the update into EDT. Since 
 * this delayed behaviour, it supports {@link #whenStable()} Executor that allows tasks
 * to be run after the Timeline is initialized / refreshed.
 * <p/>
 * Timeline also handles 'hide duplicates' feature: hides duplicate graphs
 * from the individual RangeSliders. 
 * <p/>
 * Note that positions in RangeSliders do not need to correspond to InputGraph positions
 * within GraphContainers or Groups: some graphs may be missing (e.g. filtered out), slots
 * correspond to the desired visual representation rather than data indexes.
 * Always use {@link #findGraph} to locate the appropriate graph instance.
 * @author sdedic
 */
public interface TimelineModel {
    /**
     * Hide duplicates property name
     */
    static final String PROP_HIDE_DUPLICATES = "hideDuplicates"; // NOI18N
    
    /**
    