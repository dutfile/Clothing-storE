/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.core.jdk;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.impl.InternalPlatform;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoAccess;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.code.FrameInfoQueryResult;
import com.oracle.svm.core.code.SimpleCodeInfoQueryResult;
import com.oracle.svm.core.code.UntetheredCodeInfo;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.graal.stackvalue.UnsafeStackValue;
import com.oracle.svm.core.heap.StoredContinuation;
import com.oracle.svm.core.heap.StoredContinuationAccess;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.stack.JavaStackFrameVisitor;
import com.oracle.svm.core.stack.JavaStackWalk;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.thread.Continuation;
import com.oracle.svm.core.thread.JavaThreads;
import com.oracle.svm.core.thread.LoomSupport;
import com.oracle.svm.core.thread.Target_java_lang_VirtualThread;
import com.oracle.svm.core.thread.Target_jdk_internal_vm_Continuation;
import com.oracle.svm.core.thread.Target_jdk_internal_vm_ContinuationScope;
import com.oracle.svm.core.util.VMError;

@TargetClass(value = java.lang.StackWalker.class)
@Platforms(InternalPlatform.NATIVE_ONLY.class)
final class Target_java_lang_StackWalker {

    /**
     * Current continuation that the stack walker is on.
     */
    @Alias @TargetElement(onlyWith = LoomJDK.class)//
    Target_jdk_internal_vm_Continuation continuation;

    /**
     * Target continuation scope if we're iterating a {@link Target_jdk_internal_vm_Continuation}.
     */
    @Alias @TargetElement(onlyWith = LoomJDK.class)//
    Target_jdk_internal_vm_ContinuationScope contScope;

    @Alias Set<Option> options;
    @Alias boolean retainClassRef;

    @Substitute
    @NeverInline("Starting a stack walk in the caller frame")
    private void forEach(Consumer<? super StackFrame> action) {
        boolean showHiddenFrames = options.contains(StackWalker.Option.SHOW_HIDDEN_FRAMES);
        boolean showReflectFrames = options.contains(StackWalker.Option.SHOW_REFLECT_FRAMES);

        JavaStackWalker.walkCurrentThread(KnownIntrinsics.readCallerStackPointer(), new JavaStackFrameVisitor() {
            @Override
            public boolean visitFrame(FrameInfoQueryResult frameInfo) {
                if (StackTraceUtils.shouldShowFrame(frameInfo, showHiddenFrames, showReflectFrames, showHiddenFrames)) {
                    action.accept(new StackFrameImpl(frameInfo));
                }
                return true;
            }
        });
    }

    /*
     * NOTE: this implementation could be optimized by an intrinsic constant-folding operation in
     * case deep enough method inlining has happened.
     */
    @Substitute
    @NeverInline("Starting a stack walk in the caller frame")
    @SuppressWarnings("static-method")
    private Class<?> getCallerClass() {
        if (!retainClassRef) {
            throw new UnsupportedOperationException("This stack walker does not have RETAIN_CLASS_REFERENCE access");
        }

        /*
         * It is intentional that the StackWalker.options is ignored. The specification JavaDoc of
         * StackWalker.getCallerClass states:
         *
         * This method filters reflection frames, MethodHandle, and hidden frames regardless of the
         * SHOW_REFLECT_FRAMES and SHOW_HIDDEN_FRAMES options this StackWalker has been configured
         * with.
         */

        Class<?> result = StackTraceUtils.getCallerClass(KnownIntrinsics.readCallerStackPointer(), false);
        if (result == null) {
            throw new IllegalCallerException("No calling frame");
        }
        return result;
    }

    @Substitute
    @NeverInline("Starting a stack walk in the caller frame")
    private <T> T walk(Function<? super Stream<StackFrame>, ? extends T> function) {
        JavaStackWalk walk = UnsafeStackValue.get(JavaStackWalk.class);
        AbstractStackFrameSpliterator spliterator;
        if (LoomSupport.isEnabled() && continuation != null) {
            // walking a yielded continuation
            spliterator = new ContinuationSpliterator(walk, contScope, continuation);
        } else {
            // walking a platform thread or mounted continuation
            Pointer sp = KnownIntrinsics.readCallerStackPointer();
            if (LoomSupport.isEnabled() && (contScope != null || JavaThreads.isCurrentThreadVirtual())) {
                var scope = (contScope != null) ? contScope : Target_java_lang_VirtualThread.continuationScope();
                var top = Target_jdk_internal_vm_Continuation.getCurrentContinuation(scope);
                if (top != null) { // has a delimitation scope
                    JavaStackWalker.initWalk(walk, sp, LoomSupport.getInternalContinuation(top).getBaseSP());
                } else { // scope is not present in current continuation chain or null
                    JavaStackWalker.initWalk(walk, sp);
                }
            } else { // walking a platform thread
                JavaStackWalker.initWalk(walk, sp);
            }
            spliterator = new StackFrameSpliterator(walk, Thread.currentThread());
        }

        try {
            return function.apply(StreamSupport.stream(spliterator, false));
        } finally {
            spliterator.invalidate();
        }
    }

    private abstract class AbstractStackFrameSpliterator implements Spliterator<StackFrame> {
        @Override
        public Spliterator<StackFrame> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        @Override
        public int characteristics() {
            return Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.ORDERED;
        }

        @Uninterruptible(reason = "Wraps the now safe call to query frame information.", calleeMustBe = false)
        protected FrameInfoQueryResult queryFrameInfo(CodeInfo info, CodePointer ip) {
            return CodeInfoTable.lookupCodeInfoQueryResult(info, ip).getFrameInfo();
        }

        protected DeoptimizedFrame.VirtualFrame curDeoptimizedFrame;
        protected FrameInfoQueryResult curRegularFrame;

        @Override
        public boolean tryAdvance(Consumer<? super StackFrame> action) {
            checkState();

            boolean showHiddenFrames = options.contains(StackWalker.Option.SHOW_HIDDEN_FRAMES);
            boolean showReflectFrames = options.contains(StackWalker.Option.SHOW_REFLECT_FRAMES);

            while (true) {
                /* Check if we have pending virtual frames to process. */
                if (curDeoptimizedFrame != null) {
                    FrameInfoQueryResult frameInfo = curDeoptimizedFrame.getFrameInfo();
                    curDeoptimizedFrame = curDeoptimizedFrame.getCaller();

                    if (shouldShowFrame(frameInfo, showHiddenFrames, showReflectFrames, showHiddenFrames)) {
                        action.accept(new StackFrameImpl(frameInfo));
                        return true;
                    }

                } else if (curRegularFrame != null) {
                    FrameInfoQueryResult frameInfo = curRegularFrame;
                    curRegularFrame = curRegularFrame.getCaller();

                    if (shouldShowFrame(frameInfo, showHiddenFrames, showReflectFrames, showHiddenFrames)) {
                        action.accept(new StackFrameImpl(frameInfo));
                        return true;
                    }

                } else if (haveMoreFrames()) {
                    /* No more virtual frames, but we have more physical frames. */
                    advancePhysically();
                } else {
                    /* No more physical frames, we are done. */
                    return false;
                }
            }
        }

        protected boolean shouldShowFrame(FrameInfoQueryResult frameInfo, boolean showLambdaFrames, boolean showReflectFrames, boolean showHiddenFrames) {
            return StackTraceUtils.shouldShowFrame(frameInfo, showLambdaFrames, showReflectFrames, showHiddenFrames);
        }

        protected void invalidate() {
        }

        protected void checkState() {
        }

        protected abstract boolean haveMoreFrames();

        protected abstract void advancePhysically();
    }

    final class ContinuationSpliterator extends AbstractStackFrameSpliterator {
        private final Target_jdk_internal_vm_ContinuationScope contScope;
        private JavaStackWalk walk;

        private Target_jdk_internal_vm_Continuation continuation;
        private StoredContinuation stored;

        /**
         * Because we are interruptible in between walking frames, pointers into the stack become
         * invalid if a garbage collection happens and moves the continuation object, so we store
         * stack pointers as an offset relative to {@link StoredContinuationAccess#getFramesStart}.
         */
        private UnsignedWord spOffset;
        private UnsignedWord endSpOffset;

        ContinuationSpliterator(JavaStackWalk walk, Target_jdk_internal_vm_ContinuationScope contScope, Target_jdk_internal_vm_Continuation continuation) {
            walk.setPossiblyStaleIP(WordFactory.nullPointer());
            this.walk = walk;
            this.contScope = contScope;
            this.continuation = continuation;
        }

        @Uninterruptible(reason = 