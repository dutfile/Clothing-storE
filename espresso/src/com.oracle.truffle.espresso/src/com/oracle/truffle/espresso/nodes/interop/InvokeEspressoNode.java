/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.nodes.interop;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.nodes.EspressoNode;
import com.oracle.truffle.espresso.nodes.bytecodes.InitCheck;
import com.oracle.truffle.espresso.runtime.InteropUtils;
import com.oracle.truffle.espresso.runtime.StaticObject;

@GenerateUncached
public abstract class InvokeEspressoNode extends EspressoNode {
    static final int LIMIT = 4;

    public final Object execute(Method method, Object receiver, Object[] arguments, boolean argsConverted) throws ArityException, UnsupportedTypeException {
        Method.MethodVersion resolutionSeed = method.getMethodVersion();
        if (!resolutionSeed.getRedefineAssumption().isValid()) {
            // OK, we know it's a removed method then
            resolutionSeed = method.getContext().getClassRedefinition().handleRemovedMethod(
                            method,
                            method.isStatic() ? method.getDeclaringKlass() : ((StaticObject) receiver).getKlass()).getMethodVersion();
        }
        Object result = executeMethod(resolutionSeed, receiver, arguments, argsConverted);
        /*
         * Unwrap foreign objects (invariant: foreign objects are always wrapped when coming in
         * Espresso and unwrapped when going out)
         */
        return InteropUtils.unwrap(getLanguage(), result, getMeta());
    }

    public final Object execute(Method method, Object receiver, Object[] arguments) throws ArityException, UnsupportedTypeException {
        return execute(method, receiver, arguments, false);
    }

    public static ToEspressoNode[] createToEspresso(long argsLength) {
        ToEspressoNode[] toEspresso = new ToEspressoNode[(int) argsLength];
        for (int i = 0; i < argsLength; i++) {
            toEspresso[i] = ToEspressoNodeGen.create();
        }
        return toEspresso;
    }

    static DirectCallNode createDirectCallNode(CallTarget callTarget) {
        return DirectCallNode.create(callTarget);
    }

    abstract Object executeMethod(Method.MethodVersion method, Object receiver, Object[] arguments, boolean argsConverted) throws ArityException, UnsupportedTypeException;

    @ExplodeLoop
    @Specialization(guards = "method == cachedMethod", limit = "LIMIT", assumptions = "cachedMethod.getRedefineAssumption()")
    Object doCached(Method.MethodVersion method, Object receiver, Object[] arguments, boolean argsConverted,
                    @Cached("method") Method.MethodVersion cachedMethod,
                    @Cached("createToEspresso(method.getMethod().getParameterCount())") ToEspressoNode[] toEspressoNodes,
                    @Cached("cachedMethod.getMethod().resolveParameterKlasses()") Klass[] parameterKlasses,
                    @Cached(value = "createDirectCallNode(method.getMethod().getCallTarget())") DirectCallNode directCallNode,
                    @Cached InitCheck initCheck,
                    @Cached BranchProfile badArityProfile)
                    throws ArityException, UnsupportedTypeException {

        checkValidInvoke(method.getMethod(), receiver);

        int expectedArity = cachedMethod.getMethod().getParameterCount();
        if (arguments.length != expectedArity) {
            badArityProfile.enter();
            throw ArityException.create(expectedArity, expectedArity, arguments.length);
        }

        Object[] convertedArguments = argsConverted ? argument