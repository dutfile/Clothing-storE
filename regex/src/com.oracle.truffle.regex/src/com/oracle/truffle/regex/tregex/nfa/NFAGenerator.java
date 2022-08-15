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
package com.oracle.truffle.regex.tregex.nfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.tregex.TRegexOptions;
import com.oracle.truffle.regex.tregex.automaton.StateSet;
import com.oracle.truffle.regex.tregex.automaton.TransitionBuilder;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.parser.Counter;
import com.oracle.truffle.regex.tregex.parser.ast.CharacterClass;
import com.oracle.truffle.regex.tregex.parser.ast.LookBehindAssertion;
import com.oracle.truffle.regex.tregex.parser.ast.MatchFound;
import com.oracle.truffle.regex.tregex.parser.ast.PositionAssertion;
import com.oracle.truffle.regex.tregex.parser.ast.RegexAST;
import com.oracle.truffle.regex.tregex.parser.ast.RegexASTNode;
import com.oracle.truffle.regex.tregex.parser.ast.Sequence;
import com.oracle.truffle.regex.tregex.parser.ast.Term;
import com.oracle.truffle.regex.util.TBitSet;
import org.graalvm.collections.EconomicMap;

public final class NFAGenerator {

    private final RegexAST ast;
    private final Counter.ThresholdCounter stateID = new Counter.ThresholdCounter(TRegexOptions.TRegexMaxNFASize, "NFA explosion");
    private final Counter.ThresholdCounter transitionID = new Counter.ThresholdCounter(Short.MAX_VALUE, "NFA transition explosion");
    private final NFAState dummyInitialState;
    private final NFAState[] anchoredInitialStates;
    private final NFAState[] initialStates;
    /**
     * These are like {@link #initialStates}, but with {@code mustAdvance} set to {@code false},
     * i.e. we have already advanced when we are in these states. In a regular expression with
     * {@code MustAdvance=true}, all loopback transitions end in {@link #advancedInitialState}
     * instead of {@link #initialStates}.
     */
    private final NFAState advancedInitialState;
    private final NFAState anchoredFinalState;
    private final NFAState finalState;
    private final NFAStateTransition[] anchoredEntries;
    private final NFAStateTransition[] unAnchoredEntries;
    private final NFAStateTransition anchoredReverseEntry;
    private final NFAStateTransition unAnchoredReverseEntry;
    private NFAStateTransition initialLoopBack;
    private final Deque<NFAState> expansionQueue = new ArrayDeque<>();
    private final Map<NFAStateID, NFAState> nfaStates = new HashMap<>();
    private final List<NFAState> hardPrefixStates = new ArrayList<>();
    private final ASTStepVisitor astStepVisitor;
    private final ASTTransitionCanonicalizer astTransitionCanonicalizer;
    private final TBitSet transitionGBUpdateIndices;
    private final TBitSet transitionGBClearIndices;
    private final ArrayList<NFAStateTransition> transitionsBuffer = new ArrayList<>();
    private final CompilationBuffer compilationBuffer;

    private NFAGenerator(RegexAST ast, CompilationBuffer compilationBuffer) {
        this.ast = ast;
        this.astStepVisitor = new ASTStepVisitor(ast);
        this.transitionGBUpdateIndices = new TBitSet(ast.getNumberOfCaptureGroups() * 2);
        this.transitionGBClearIndices = new TBitSet(ast.getNumberOfCaptureGroups() * 2);
        this.astTransitionCanonicalizer = new ASTTransitionCanonicalizer(ast, true, false);
        this.compilationBuffer = compilationBuffer;
        dummyInitialState = new NFAState((short) stateID.inc(), StateSet.create(ast, ast.getWrappedRoot()), CodePointSet.getEmpty(), Collections.emptySet(), false, ast.getOptions().isMustAdvance());
        nfaStates.put(NFAStateID.create(dummyInitialState), dummyInitialState);
        anchoredFinalState = createFinalState(StateSet.create(ast, ast.getReachableDollars()), false);
        anchoredFinalState.setAnchoredFinalState();
        finalState = createFinalState(StateSet.create(ast, ast.getRoot().getSubTreeParent().getMatchFound()), false);
        finalState.setUnAnchoredFinalState();
        assert transitionGBUpdateIndices.isEmpty() && transitionGBClearIndices.isEmpty();
        anchoredReverseEntry = createTransition(anchoredFinalState, dummyInitialState, ast.getEncoding().getFullSet(), -1);
        unAnchoredReverseEntry = createTransition(finalState, dummyInitialState, ast.getEncoding().getFullSet(), -1);
        int nEntries = ast.getWrappedPrefixLength() + 1;
        initialStates = new NFAState[nEntries];
        advancedInitialState = ast.getOptions().isMustAdvance() ? createFinalState(StateSet.create(ast, ast.getNFAUnAnchoredInitialState(0)), false) : null;
        unAnchoredEntries = new NFAStateTransition[nEntries];
        for (int i = 0; i < initialStates.length; i++) {
            initialStates[i] = createFinalState(StateSet.create(ast, ast.getNFAUnAnchoredInitialState(i)), ast.getOptions().isMustAdvance());
            initialStates[i].setUnAnchoredInitialState(true);
            unAnchoredEntries[i] = createTransition(dummyInitialState, initialStates[i], ast.getEncoding().getFullSet(), -1);
            if (i > 0) {
                initialStates[i].setHasPrefixStates(true);
            }
        }
        if (ast.getReachableCarets().isEmpty()) {
            anchoredInitialStates = initialStates;
            anchoredEntries = unAnchoredEntries;
            NFAStateTransition[] dummyInitNext = Arrays.copyOf(anchoredEntries, nEntries);
            dummyInitialState.setSuccessors(dummyInitNext, false);
        } else {
            anchoredInitialStates = new NFAState[nEntries];
            anchoredEntries = new NFAStateTransition[nEntries];
            for (int i = 0; i < anchoredInitialStates.length; i++) {
                anchoredInitialStates[i] = createFinalState(StateSet.create(ast, ast.getNFAAnchoredInitialState(i)), ast.getOptions().isMustAdvance());
                anchoredInitialStates[i].setAnchoredInitialState();
                if (i > 0) {
                    initialStates[i].setHasPrefixStates(true);
                }
                anchoredEntries[i] = createTransition(dummyInitialState, anchoredInitialStates[i], ast.getEncoding().getFullSet(), -1);
            }
            NFAStateTransition[] dummyInitNext = Arrays.copyOf(anchoredEntries, nEntries * 2);
            System.arraycopy(unAnchoredEntries, 0, dummyInitNext, nEntries, nEntries);
            dummyInitialState.setSuccessors(dummyInitNext, false);
        }
        NFAStateTransition[] dummyInitPrev = new NFAStateTransition[]{anchoredReverseEntry, unAnchoredReverseEntry};
        dummyInitialState.setPredecessors(dummyInitPrev);
    }

    public static NFA createNFA(RegexAST ast, CompilationBuffer compilationBuffer) {
        return new NFAGenerator(ast, compilationBuffer).doCreateNFA();
    }

    private NFA doCreateNFA() {
        Collections.addAll(expansionQueue, initialStates);
        if (ast.getOptions().isMustAdvance()) {
            expansionQueue.add(advancedInitialState);
        }
        if (!ast.getReachableCarets().isEmpty()) {
            Collections.addAll(expansionQueue, anchoredInitialStates);
        }

        while (!expansionQueue.isEmpty()) {
            expandNFAState(expansionQueue.pop());
        }

        assert transitionGBUpdateIndices.isEmpty() && transitionGBClearIndices.isEmpty();
        for (int i = 1; i < initialStates.length; i++) {
            addNewLoopBackTransition(initialStates[i], initialStates[i - 1]);
        }
        if (ast.getOptions().isMustAdvance()) {
            addNewLoopBackTransition(initialStates[0], advancedInitialState);
            initialLoopBack = createTransition(advancedInitialState, advancedInitialState, ast.getEncoding().getFullSet(), -1);
        } else {
            initialLoopBack = createTransition(initialStates[0], initialStates[0], ast.getEncoding().getFullSet(), -1);
        }

        for (NFAState s : nfaStates.values()) {
            if (s != dummyInitialState && (ast.getHardPrefixNodes().isDisjoint(s.getStateSet()) || ast.