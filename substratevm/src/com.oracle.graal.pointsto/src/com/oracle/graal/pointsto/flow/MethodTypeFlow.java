
/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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

import static jdk.vm.ci.common.JVMCIError.shouldNotReachHere;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.graalvm.collections.EconomicMap;
import org.graalvm.compiler.debug.Assertions;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.builder.TypeFlowGraphBuilder;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.PointsToAnalysisMethod;
import com.oracle.graal.pointsto.typestate.TypeState;
import com.oracle.graal.pointsto.util.AnalysisError;

public class MethodTypeFlow extends TypeFlow<AnalysisMethod> {

    protected final PointsToAnalysisMethod method;
    protected volatile MethodFlowsGraph flowsGraph;
    private InvokeTypeFlow parsingReason;
    private int returnedParameterIndex;
    private MethodFlowsGraph.GraphKind graphKind;

    /**
     * Used to detect races between calling {@link #getMethodFlowsGraph()} and
     * {@link #updateFlowsGraph}. Once the method flows graph has been retrieved, then it cannot be
     * updated again.
     */
    private Object sealedFlowsGraph;

    private boolean forceReparseOnCreation = false;

    public MethodTypeFlow(PointsToAnalysisMethod method) {
        super(method, null);
        this.method = method;
        this.graphKind = MethodFlowsGraph.GraphKind.FULL;
    }

    public PointsToAnalysisMethod getMethod() {
        return method;
    }

    /**
     * Signals that a STUB graphkind should be generated upon creation.
     */
    public synchronized void setAsStubFlow() {
        graphKind = MethodFlowsGraph.GraphKind.STUB;
        assert !method.isOriginalMethod() : "setting original method as stub";
        assert !flowsGraphCreated() : "cannot set as flow creation kind flows graph is created";
    }

    /**
     * Helper to see when the flows graph was sealed.
     */
    private void throwSealedError() {
        assert sealedFlowsGraph != null;
        StringBuilder sb = new StringBuilder();
        sb.append("Sealed problem:\n");