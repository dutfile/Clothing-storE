/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.genscavenge;

import java.nio.ByteBuffer;
import java.util.List;

import org.graalvm.compiler.core.common.NumUtil;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.genscavenge.ChunkedImageHeapAllocator.AlignedChunk;
import com.oracle.svm.core.genscavenge.ChunkedImageHeapAllocator.Chunk;
import com.oracle.svm.core.genscavenge.ChunkedImageHeapAllocator.UnalignedChunk;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.image.ImageHeap;
import com.oracle.svm.core.image.ImageHeapLayoutInfo;

public class ChunkedImageHeapLayouter extends AbstractImageHeapLayouter<ChunkedImageHeapPartition> {
    private final ImageHeapInfo heapInfo;
    private final long startOffset;
    private final int nullRegionSize;
    private final long hugeObjectThreshold;
    private ChunkedImageHeapAllocator allocator;

    public ChunkedImageHeapLayouter(ImageHeapInfo heapInfo, long startOffset, int nullRegionSize) {
        assert startOffset == 0 || startOffset >= Heap.getHeap().getImageHeapOffsetInAddressSpace() : "must be relative to the heap base";
        this.heapInfo = heapInfo;
        this.startOffset = startOffset;
        this.nullRegionSize = nullRegionSize;
        this.hugeObjectThreshold = HeapParameters.getLargeArrayThreshold().rawValue();
    }

    @Override
    protected ChunkedImageHeapPartition[] createPartitionsArray(int count) {
        return new ChunkedImageHeapPartition[count];
    }

    @Override
    protected ChunkedImageHeapPartition createPartition(String name, boolean containsReferences, boolean writable, boolean hugeObjects) {
        return new ChunkedImageHeapPartition(name, writable, hugeObjects);
    }

    @Override
    protected long getHugeObjectThreshold() {
        return hugeObjectThreshold;
    }

    @Override
    protected ImageHeapLayoutInfo doLayout(ImageHeap imageHeap) {
        long position = startOffset + nullRegionSize;
        allocator = new ChunkedImageHeapAllocator(imageHeap, position);
        for (ChunkedImageHeapPartition partition : getPartitions()) {
            partition.layout(allocator);
        }
        return populateInfoObjects(imageHeap.countDynamicHubs());
    }

    private ImageHeapLayoutInfo populateInfoObjects(int dynamicHubCount) {
        // Determine writable start boundary from chunks: a chunk that contains writable objects
        // must also have a writable card table
        long offsetOfFirstWritableAlignedChunk = getWritablePrimitive().getStartOffset();
        for (AlignedChunk chunk : allocator.getAlignedChunks()) {
            if (chunk.isWritable() && chunk.getBegin() < offsetOfFirstWritableAlignedChunk) {
                assert offsetOfFirstWritableAlignedChunk <= chunk.getEnd();
                offsetOfFirstWritableAlignedChunk = chunk.getBegin();
                break; // (chunks are in ascending memory order)
            }
        }
        long offsetOfFirstWritableUnalignedChunk = -1;
        for (UnalignedChunk chunk : allocator.getUnalignedChunks()) {
            if (chunk.isWritable()) {
                offsetOfFirstWritableUnalignedChunk = chunk.getBegin();
         