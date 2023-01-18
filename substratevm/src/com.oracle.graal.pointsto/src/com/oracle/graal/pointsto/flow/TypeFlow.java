/*
 * Copyright (c) 2013, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package com.oracle.graal.pointsto.flow;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.graalvm.compiler.graph.Node;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.api.PointstoOptions;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.PointsToAnalysisMethod;
import com.oracle.graal.pointsto.results.StaticAnalysisResultsBuilder;
import com.oracle.graal.pointsto.typestate.PointsToStats;
import com.oracle.graal.pointsto.typestate.TypeState;
import com.oracle.graal.pointsto.typestate.TypeStateUtils;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.graal.pointsto.util.ConcurrentLightHashSet;
import com.oracle.svm.util.ClassUtil;

import jdk.vm.ci.code.BytecodePosition;

@SuppressWarnings("rawtypes")
public abstract class TypeFlow<T> {
    private static final AtomicReferenceFieldUpdater<TypeFlow, Object> USE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TypeFlow.class, Object.class, "uses");
    private static final AtomicReferenceFieldUpdater<TypeFlow, Object> INPUTS_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TypeFlow.class, Object.class, "inputs");
    private static final AtomicReferenceFieldUpdater<TypeFlow, Object> OBSERVERS_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TypeFlow.class, Object.class, "observers");
    private static final AtomicReferenceFieldUpdater<TypeFlow, Object> OBSERVEES_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TypeFlow.class, Object.class, "observees");
    protected static final AtomicInteger nextId = new AtomicInteger();

    protected final int id;

    protected T source;
    /*
     * The declared type of the node corresponding to this flow. The declared type is inferred from
     * stamps during bytecode parsing, and, when missing, it is approximated by Object.
     */
    protected final AnalysisType declaredType;

    protected volatile TypeState state;

    /** The set of all {@link TypeFlow}s that need to be update when this flow changes. */
    @SuppressWarnings("unused") private volatile Object uses;

    /** The set of all flows that have this flow as an use. */
    @SuppressWarnings("unused") private volatile Object inputs;

    /** The set of all observers, i.e., objects that are notified when this flow changes. */
    @SuppressWarnings("unused") private volatile Object observers;

    /** The set of all observees, i.e., objects that notify this flow when they change. */
    @SuppressWarnings("unused") private volatile Object observees;

    private int slot;
    private final boolean isClone; // true -> clone, false -> original
    protected final MethodFlowsGraph graphRef;

    public volatile boolean inQueue;

    /**
     * A TypeFlow is saturated when its type count is beyond a predetermined limit set via
     * {@link PointstoOptions#TypeFlowSaturationCutoff}. If true, this flow is marked as saturated,
     * i.e., it will not process state updates from its inputs anymore. Type flows should check the
     * saturated state of an use before calling {@link #addState(PointsToAnalysis, TypeState)} and
     * if the flag is set they should unlink the use. This will result in a lazy removal of this
     * flow from the type flow graph.
     * <p/>
     * A type flow can also be marked as saturated when one of its inputs has reached the saturated
     * state and has propagated the "saturated" marker downstream. Thus, since in such a situation
     * the input stops propagating type states, a flow's type state may be incomplete. It is up to
     * individual type flows to subscribe themselves directly to the type flows of their declared
     * types if they need further updates.
     * <p/>
     * When static analysis results are built in
     * {@link StaticAnalysisResultsBuilder#makeOrApplyResults} the type state is considered only if
     * the type flow was not marked as saturated.
     * <p/>
     * The initial value is false, i.e., the flow is initially not saturated.
     */
    private volatile boolean isSaturated;

    @SuppressWarnings("rawtypes")//
    private static final AtomicReferenceFieldUpdater<TypeFlow, TypeState> STATE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(TypeFlow.class, TypeState.class, "state");

    private TypeFlow(T source, AnalysisType declaredType, TypeState typeState, int slot, boolean isClone, MethodFlowsGraph graphRef) {
        this.id = nextId.incrementAndGet();
        this.source = source;
        this.declaredType = declaredType;
        this.slot = slot;
        this.isClone = isClone;
        this.graphRef = graphRef;
        this.state = typeState;

        validateSource();
    }

    private void validateSource() {
        assert !(source instanceof Node) : "must not reference Graal node from TypeFlow: " + source;
    }

    public TypeFlow() {
        this(null, null, TypeState.forEmpty(), -1, false, null);
    }

    public TypeFlow(TypeState typeState) {
        this(null, null, typeState, -1, false, null);
    }

    public TypeFlow(T source, AnalysisType declaredType) {
        this(source, declaredType, TypeState.forEmpty(), -1, false, null);
    }

    public TypeFlow(T source, AnalysisType declaredType, boolean canBeNull) {
        this(source, declaredType, canBeNull ? TypeState.forNull() : TypeState.forEmpty(), -1, false, null);
    }

    public TypeFlow(T source, AnalysisType declaredType, TypeState state) {
        this(source, declaredType, state, -1, false, null);
    }

    /**
     * Shallow copy constructor. Does not copy the flows or the state.
     *
     * @param original the original flow
     * @param graphRef the holder method clone
     */
    public TypeFlow(TypeFlow<T> original, MethodFlowsGraph graphRef) {
        this(original, graphRef, TypeState.forEmpty());
    }

    public TypeFlow(TypeFlow<T> original, MethodFlowsGraph graphRef, TypeState cloneState) {
        this(original.getSource(), original.getDeclaredType(), cloneState, original.getSlot(), true, graphRef);
        PointsToStats.registerTypeFlowRetainReason(this, original);
    }

    /**
     * By default a type flow is not cloneable.
     *
     * @param bb
     * @param methodFlows
     */
    public TypeFlow<T> copy(PointsToAnalysis bb, MethodFlowsGraph methodFlows) {
        return this;
    }

    /**
     * Initialization code for some type flow corner cases. {@link #needsInitialization()} also
     * needs to be overridden to enable type flow initialization.
     *
     * @param bb
     */
    public void initFlow(PointsToAnalysis bb) {
        throw AnalysisError.shouldNotReachHere("Type flow " + format(false, true) + " is not overriding initFlow().");
    }

    /**
     * Type flows that require initialization after the graph is created need to override this
     * method and return true.
     */
    public boolean needsInitialization() {
        return false;
    }

    /** Some flows have a receiver (e.g., loads, store and invokes). */
    public TypeFlow<?> receiver() {
        return null;
    }

    public int id() {
        return id;
    }

    public MethodFlowsGraph graphRef() {
        if (graphRef != null) {
            return graphRef;
        }
        if (source instanceof BytecodePosition && !isClone) {
            BytecodePosition po