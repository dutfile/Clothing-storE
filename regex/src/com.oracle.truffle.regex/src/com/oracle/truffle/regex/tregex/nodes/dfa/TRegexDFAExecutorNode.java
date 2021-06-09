/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.nodes.dfa;

import static com.oracle.truffle.api.CompilerDirectives.LIKELY_PROBABILITY;
import static com.oracle.truffle.api.CompilerDirectives.SLOWPATH_PROBABILITY;
import static com.oracle.truffle.api.CompilerDirectives.UNLIKELY_PROBABILITY;
import static com.oracle.truffle.api.CompilerDirectives.injectBranchProbability;

import java.util.Arrays;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.regex.RegexRootNode;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.tregex.TRegexOptions;
import com.oracle.truffle.regex.tregex.matchers.CharMatcher;
import com.oracle.truffle.regex.tregex.nodes.TRegexExecutorLocals;
import com.oracle.truffle.regex.tregex.nodes.TRegexExecutorNode;
import com.oracle.truffle.regex.tregex.nodes.dfa.SequentialMatchers.SimpleSequentialMatchers;
import com.oracle.truffle.regex.tregex.nodes.dfa.SequentialMatchers.UTF16Or32SequentialMatchers;
import com.oracle.truffle.regex.tregex.nodes.dfa.SequentialMatchers.UTF16RawSequentialMatchers;
import com.oracle.truffle.regex.tregex.nodes.dfa.SequentialMatchers.UTF8SequentialMatchers;
import com.oracle.truffle.regex.tregex.nodes.input.InputIndexOfNode;
import com.oracle.truffle.regex.tregex.nodes.input.InputIndexOfStringNode;

public final class TRegexDFAExecutorNode extends TRegexExecutorNode {

    private static final int IP_TRANSITION_MARKER = 0x8000;
    public static final int NO_MATCH = -2;
    private final TRegexDFAExecutorProperties props;
    private final int maxNumberOfNFAStates;
    @CompilationFinal(dimensions = 1) private final TruffleString.CodePointSet[] indexOfParameters;
    @CompilationFinal(dimensions = 1) private final DFAAbstractStateNode[] states;
    @CompilationFinal(dimensions = 1) private final int[] cgResultOrder;
    private final TRegexDFAExecutorDebugRecorder debugRecorder;

    @Children private InputIndexOfNode[] indexOfNodes;
    @Child private InputIndexOfStringNode indexOfStringNode;
    @Child private TRegexDFAExecutorNode innerLiteralPrefixMatcher;

    public TRegexDFAExecutorNode(
                    RegexSource source,
                    TRegexDFAExecutorProperties props,
                    int numberOfCaptureGroups,
                    int maxNumberOfNFAStates,
                    TruffleString.CodePointSet[] indexOfParameters,
                    DFAAbstractStateNode[] states,
                    TRegexDFAExecutorDebugRecorder debugRecorder,
                    TRegexDFAExecutorNode innerLiteralPrefixMatcher) {
        this(source, props, numberOfCaptureGroups, calcNumberOfTransitions(states), maxNumberOfNFAStates, indexOfParameters, states,
                        props.isGenericCG() && maxNumberOfNFAStates > 1 ? initResultOrder(maxNumberOfNFAStates, numberOfCaptureGroups, props) : null, debugRecorder,
                        innerLiteralPrefixMatcher);
    }

    public TRegexDFAExecutorNode(
                    RegexSource source,
                    TRegexDFAExecutorProperties props,
                    int numberOfCaptureGroups,
                    int numberOfTransitions,
                    int maxNumberOfNFAStates,
                    TruffleString.CodePointSet[] indexOfParameters,
                    DFAAbstractStateNode[] states,
                    int[] cgResultOrder,
                    TRegexDFAExecutorDebugRecorder debugRecorder,
                    TRegexDFAExecutorNode innerLiteralPrefixMatcher) {
        super(source, numberOfCaptureGroups, numberOfTransitions);
        this.props = props;
        this.maxNumberOfNFAStates = maxNumberOfNFAStates;
        this.indexOfParameters = indexOfParameters;
        this.states = states;
        this.cgResultOrder = cgResultOrder;
        this.debugRecorder = debugRecorder;
        this.innerLiteralPrefixMatcher = innerLiteralPrefixMatcher;
    }

    private TRegexDFAExecutorNode(TRegexDFAExecutorNode copy, TRegexDFAExecutorNode innerLiteralPrefixMatcher) {
        this(copy.getSource(), copy.props, copy.getNumberOfCaptureGroups(), copy.getNumberOfTransitions(), copy.maxNumberOfNFAStates, copy.indexOfParameters, copy.states, copy.cgResultOrder,
                        copy.debugRecorder,
                        innerLiteralPrefixMatcher);
    }

    @Override
    public TRegexDFAExecutorNode shallowCopy() {
        return new TRegexDFAExecutorNode(this, innerLiteralPrefixMatcher == null ? null : innerLiteralPrefixMatcher.shallowCopy());
    }

    private DFAInitialStateNode getInitialState() {
        return (DFAInitialStateNode) states[0];
    }

    public int getPrefixLength() {
        return getInitialState().getPrefixLength();
    }

    public boolean isAnchored() {
        return !getInitialState().hasUnAnchoredEntry();
    }

    @Override
    public String getName() {
        return "dfa";
    }

    @Override
    public boolean isForward() {
        return props.isForward();
    }

    @Override
    public boolean isTrivial() {
        return getNumberOfTransitions() < (isGenericCG() ? (TRegexOptions.TRegexMaxTransitionsInTrivialExecutor * 3) / 4 : TRegexOptions.TRegexMaxTransitionsInTrivialExecutor);
    }

    public boolean isBackward() {
        return !props.isForward();
    }

    public boolean isSearching() {
        return props.isSearching();
    }

    public boolean isSimpleCG() {
        return props.isSimpleCG();
    }

    public boolean isGenericCG() {
        return props.isGenericCG();
    }

    public boolean isRegressionTestMode() {
        return props.isRegressionTestMode();
    }

    public int getNumberOfStates() {
        return states.length;
    }

    private static int calcNumberOfTransitions(DFAAbstractStateNode[] states) {
        int sum = 0;
        for (DFAAbstractStateNode state : states) {
            sum += state.getSuccessors().length;
            if (state instanceof DFAStateNode && !((DFAStateNode) state).treeTransitionMatching() &&
                            ((DFAStateNode) state).getSequentialMatchers().getNoMatchSuccessor() >= 0) {
                sum++;
            }
        }
        return sum;
    }

    public boolean recordExecution() {
        return debugRecorder != null;
    }

    public TRegexDFAExecutorDebugRecorder getDebugRecorder() {
        return debugRecorder;
    }

    InputIndexOfNode getIndexOfNode(int index) {
        if (indexOfNodes == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            InputIndexOfNode[] nodes = new InputIndexOfNode[indexOfParameters.length];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = InputIndexOfNode.create();
            }
            indexOfNodes = insert(nodes);
        }
        return indexOfNodes[index];
    }

    InputIndexOfStringNode getIndexOfStringNode() {
        if (indexOfStringNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indexOfStringNode = insert(InputIndexOfStringNode.create());
        }
        return indexOfStringNode;
    }

    @Override
    public TRegexExecutorLocals createLocals(TruffleString input, int fromIndex, int index, int maxIndex) {
        return new TRegexDFAExecutorLocals(input, fromIndex, index, maxIndex, createCGData());
    }

    @Override
    public boolean writesCaptureGroups() {
        return isSimpleCG();
    }

    private DFACaptureGroupTrackingData createCGData() {
        if (isSimpleCG()) {
            return new DFACaptureGroupTrackingData(null,
                            createResultsArray(resultLength()),
                            props.isSimpleCGMustCopy() ? new int[resultLength()] : null);
        } else if (isGenericCG()) {
            return new DFACaptureGroupTrackingData(
                            maxNumberOfNFAStates == 1 ? null : Arrays.copyOf(cgResultOrder, cgResultOrder.length),
                            createResultsArray(maxNumberOfNFAStates * resultLength()),
                            new int[resultLength()]);
        } else {
            return null;
        }
    }

    private int resultLength() {
        return getNumberOfCaptureGroups() * 2 + (props.tracksLastGroup() ? 1 : 0);
    }

    private static int[] createResultsArray(int length) {
        int[] results = new int[length];
        Arrays.fill(results, -1);
        return results;
    }

    /**
     * records position of the END of the match found, or -1 if no match exists.
     */
    @Override
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.MERGE_EXPLODE)
    public Object execute(VirtualFrame frame, final TRegexExecutorLocals abstractLocals, final TruffleString.CodeRange codeRange) {
        TRegexDFAExecutorLocals locals = (TRegexDFAExecutorLocals) abstractLocals;
        CompilerDirectives.ensureVirtualized(locals);
        CompilerAsserts.partialEvaluationConstant(states);
        CompilerAsserts.partialEvaluationConstant(states.length);
        CompilerAsserts.pa