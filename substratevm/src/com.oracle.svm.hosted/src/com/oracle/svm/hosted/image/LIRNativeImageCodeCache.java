/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.image;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.code.CompilationResult.CodeAnnotation;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.word.WordFactory;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.objectfile.ObjectFile;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.meta.MethodPointer;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.code.HostedDirectCallTrampolineSupport;
import com.oracle.svm.hosted.code.HostedImageHeapConstantPatch;
import com.oracle.svm.hosted.code.HostedPatcher;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.hosted.meta.HostedMethod;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.code.site.Reference;

public class LIRNativeImageCodeCache extends NativeImageCodeCache {

    private static final byte CODE_FILLER_BYTE = (byte) 0xCC;

    private int codeCacheSize;

    private final Map<HostedMethod, Map<HostedMethod, Integer>> trampolineMap;
    private final Map<HostedMethod, List<Pair<HostedMethod, Integer>>> orderedTrampolineMap;
    private final Map<HostedMethod, Integer> compilationPosition;

    private final TargetDescription target;

    public LIRNativeImageCodeCache(Map<HostedMethod, CompilationResult> compilations, NativeImageHeap imageHeap) {
        super(compilations, imageHeap);
        target = ConfigurationValues.getTarget();
        trampolineMap = new HashMap<>();
        orderedTrampolineMap = new HashMap<>();

        compilationPosition = new HashMap<>();
        int compilationPos = 0;
        for (var entry : getOrderedCompilations()) {
            compilationPosition.put(entry.getLeft(), compilationPos);
            compilationPos++;
        }
    }

    @Override
    public int getCodeCacheSize() {
        assert codeCacheSize > 0;
        return codeCacheSize;
    }

    @Override
    public int getCodeAreaSize() {
        return getCodeCacheSize();
    }

    private void setCodeCacheSize(int size) {
        assert codeCacheSize == 0 && size > 0;
        codeCacheSize = size;
    }

    @Override
    public int codeSizeFor(HostedMethod method) {
        int methodStart = method.getCodeAddressOffset();
        int methodEnd;

        if (orderedTrampolineMap.containsKey(method)) {
            List<Pair<HostedMethod, Integer>> trampolineList = orderedTrampolineMap.get(method);
            int lastTrampolineStart = trampolineList.get(trampolineList.size() - 1).getRight();
            methodEnd = computeNextMethodStart(lastTrampolineStart, HostedDirectCallTrampolineSupport.singleton().getTrampolineSize());
        } else {
            methodEnd = computeNextMethodStart(methodStart, compilationResultFor(method).getTargetCodeSize());
        }

        return methodEnd - methodStart;
    }

    private boolean verifyMethodLayout() {
        HostedDirectCallTrampolineSupport trampolineSupport = HostedDirectCallTrampolineSupport.singleton();
        int currentPos = 0;
        for (Pair<HostedMethod, CompilationResult> entry : getOrderedCompilations()) {
            HostedMethod method = entry.getLeft();
            CompilationResult compilation = entry.getRight();

            int methodStart = method.getCodeAddressOffset();
            assert currentPos == methodStart;

            currentPos += compilation.getTargetCodeSize();

            if (orderedTrampolineMap.containsKey(method)) {
                for (var trampoline : orderedTrampolineMap.get(method)) {
                    int trampolineOffset = trampoline.getRight();

                    currentPos = NumUtil.roundUp(currentPos, trampolineSupport.getTrampolineAlignment());
                    assert trampolineOffset == currentPos;

                    currentPos += trampolineSupport.getTrampolineSize();
                }
            }

            currentPos = computeNextMethodStart(currentPos, 0);
            int size = currentPos - method.getCodeAddressOffset();
            assert codeSizeFor(method) == size;
        }

        return true;
    }

    @SuppressWarnings("try")
    @Override
    public void layoutMethods(DebugContext debug, BigBang bb, ForkJoinPool threadPool) {

        try (Indent indent = debug.logAndIndent("layout methods")) {
            // Assign initial location to all methods.
            HostedDirectCallTrampolineSupport trampolineSupport = HostedDirectCallTrampolineSupport.singleton();
            Map<HostedMethod, Integer> curOffsetMap = trampolineSupport.mayNeedTrampolines() ? new HashMap<>() : null;

            int curPos = 0;
            for (Pair<HostedMethod, CompilationResult> entry : getOrderedCompilations()) {
                HostedMethod method = entry.getLeft();
                CompilationResult compilation = entry.getRight();

                if (!trampolineSupport.mayNeedTrampolines()) {
                    method.setCodeAddressOffset(curPos);
                } else {
                    curOffsetMap.put(method, curPos);
                }
                curPos = computeNextMethodStart(curPos, compilation.getTargetCodeSize());
            }

            if (trampolineSupport.mayNeedTrampolines()) {

                // check for and add any needed trampolines
                addDirectCallTrampolines(curOffsetMap);

                // record final code address offsets and trampoline metadata
                for (Pair<HostedMethod, CompilationResult> pair : getOrderedCompilations()) {
                    HostedMethod method = pair.getLeft();
                    int methodStartOffset = curOffsetMap.get(method);
                    method.setCodeAddressOffset(methodStartOffset);
                    Map<HostedMethod, Integer> trampolines = trampolineMap.get(method);
                    if (trampolines.size() != 0) {
                        // assign an offset to each trampoline
                        List<Pair<HostedMethod, Integer>> sortedTrampolines = new ArrayList<>(trampolines.size());
                        int position = methodStartOffset + pair.getRight().getTargetCodeSize();
                        /*
                         * Need to have snapshot of trampoline key set since we update their
                         * positions.
                         */
                        for (HostedMethod callTarget : trampolines.keySet().toArray(HostedMethod.EMPTY_ARRAY)) {
                            position = NumUtil.roundUp(position, trampolineSupport.getTrampolineAlignment());
                            trampolines.put(callTarget, position);
                            sortedTrampolines.add(Pair.create(callTarget, position));
                            position += trampolineSupport.getTrampolineSize();
                        }
                        orderedTrampolineMap.put(method, sortedTrampolines);
                    }
                }
            }

            Pair<HostedMethod, CompilationResult> lastCompilation = getLastCompilation();
            HostedMethod lastMethod = lastCompilation.getLeft();

            // the total code size is the hypothetical start of the next method
            int totalSize;
            if (orderedTrampolineMap.containsKey(lastMethod)) {
                var trampolines = orderedTrampolineMap.get(lastMethod);
                int lastTrampolineStart = trampolines.get(trampolines.size() - 1).getRight();
                totalSize = computeNextMethodStart(lastTrampolineStart, trampolineSupport.getTrampolineSize());
            } else {
                totalSize = computeNextMethodStart(lastCompilation.getLeft().getCodeAddressOffset(), lastCompilation.getRight().getTargetCodeSize());
            }

            setCodeCacheSize(totalSize);

            assert verifyMethodLayout();

            buildRuntimeMetadata(new MethodPointer(getFirstCompilation().getLeft()), WordFactory.unsigned(totalSize));
        }
    }

    private static int comp