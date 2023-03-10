
/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.nodes.func;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.dsl.GenerateAOT;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.Library;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.NodeVisitor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.llvm.runtime.DummyReceiver;
import com.oracle.truffle.llvm.runtime.LLVMFunction;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.datalayout.DataLayout;
import com.oracle.truffle.llvm.runtime.debug.scope.LLVMSourceLocation;
import com.oracle.truffle.llvm.runtime.memory.LLVMStack.LLVMStackAccess;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMHasDatalayoutNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;

import java.util.HashMap;
import java.util.Map;

public final class LLVMFunctionStartNode extends LLVMRootNode implements LLVMHasDatalayoutNode {

    @Child private LLVMExpressionNode node;
    private final String name;
    private final int explicitArgumentsCount;
    private final String originalName;
    private final Source bcSource;
    private final LLVMSourceLocation sourceLocation;

    private final DataLayout dataLayout;
    private final LLVMFunction rootFunction;

    public LLVMFunctionStartNode(LLVMLanguage language, LLVMStackAccess stackAccess, LLVMExpressionNode node, FrameDescriptor frameDescriptor, String name, int explicitArgumentsCount,
                    String originalName, Source bcSource,
                    LLVMSourceLocation location, DataLayout dataLayout, LLVMFunction rootFunction) {
        super(language, frameDescriptor, stackAccess);
        this.dataLayout = dataLayout;
        this.explicitArgumentsCount = explicitArgumentsCount;
        this.node = node;
        this.name = name;
        this.originalName = originalName;
        this.bcSource = bcSource;
        this.sourceLocation = location;
        this.rootFunction = rootFunction;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceLocation.getSourceSection();
    }

    @Override
    public boolean isInternal() {
        return bcSource.isInternal();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return node.executeGeneric(frame);
    }

    @Override
    public String getName() {
        if (originalName != null) {
            return originalName;
        }
        return name;
    }

    public LLVMFunction getRootFunction() {
        return rootFunction;
    }

    public int getExplicitArgumentsCount() {
        return explicitArgumentsCount;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getBcName() {
        return name;
    }

    public Source getBcSource() {
        return bcSource;
    }

    @Override
    public DataLayout getDatalayout() {
        return dataLayout;
    }

    @Override
    public Map<String, Object> getDebugProperties() {
        CompilerAsserts.neverPartOfCompilation();
        final HashMap<String, Object> properties = new HashMap<>();
        if (originalName != null) {
            properties.put("originalName", originalName);
        }
        if (bcSource != null) {
            properties.put("bcSource", bcSource);
        }
        if (sourceLocation != null) {
            properties.put("sourceLocation", sourceLocation);
        }
        return properties;
    }

    @Override
    protected ExecutionSignature prepareForAOT() {
        super.prepareForAOT();
        customPrepareForAOT(this);
        // TODO: use the FunctionDefinition to prepare the right signature
        return ExecutionSignature.GENERIC;
    }

    @Override
    protected Object translateStackTraceElement(TruffleStackTraceElement element) {
        return LLVMManagedPointer.cast(LLVMLanguage.getContext().getSymbolResolved(rootFunction, BranchProfile.getUncached())).getObject();
    }

    private static void customPrepareForAOT(RootNode root) {
        root.atomic(() -> {
            final LLVMLanguage language = LLVMLanguage.get(root);
            NodeUtil.forEachChild(root, new ReplacingAOTNodeVisitor(language, root));
        });
    }

    private static final class ReplacingAOTNodeVisitor implements NodeVisitor {

        private final LLVMLanguage language;
        private final RootNode root;

        private ReplacingAOTNodeVisitor(LLVMLanguage language, RootNode root) {
            this.language = language;
            this.root = root;
        }

        @Override
        public boolean visit(Node n) {
            Node node = n;

            boolean prepareForAOT = true;

            if (node.isAdoptable() && node instanceof Library) {
                // DummyReceiver holds the knowledge of library nodes that should be replaced in
                // the context of the current platform
                Library repl = DummyReceiver.getAOTLibraryReplacement((Library) node);
                if (repl != null) {
                    node = node.replace(repl);
                }
                // Dummy library replacements do not undergo the AOT preparation
                prepareForAOT = repl == null;
            }

            if (prepareForAOT && node instanceof GenerateAOT.Provider) {
                GenerateAOT.Provider provider = (GenerateAOT.Provider) node;
                if (node.isAdoptable() && node.getRootNode() == null) {
                    throw new AssertionError("Node is not yet adopted before prepare " + node);
                }
                provider.prepareForAOT(language, root);
            }

            return NodeUtil.forEachChild(node, this);
        }
    }
}