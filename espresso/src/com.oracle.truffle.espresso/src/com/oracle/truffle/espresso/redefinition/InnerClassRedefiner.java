/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.redefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.oracle.truffle.espresso.classfile.ClassfileParser;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.impl.ClassRegistry;
import com.oracle.truffle.espresso.impl.ConstantPoolPatcher;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.ObjectKlass;
import com.oracle.truffle.espresso.jdwp.api.ErrorCodes;
import com.oracle.truffle.espresso.jdwp.api.RedefineInfo;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.StaticObject;

public final class InnerClassRedefiner {

    public static final Pattern ANON_INNER_CLASS_PATTERN = Pattern.compile(".*\\$\\d+.*");
    public static final int METHOD_FINGERPRINT_EQUALS = 8;
    public static final int ENCLOSING_METHOD_FINGERPRINT_EQUALS = 4;
    public static final int FIELD_FINGERPRINT_EQUALS = 2;
    public static final int NUMBER_INNER_CLASSES = 1;
    public static final int MAX_SCORE = METHOD_FINGERPRINT_EQUALS + ENCLOSING_METHOD_FINGERPRINT_EQUALS + FIELD_FINGERPRINT_EQUALS + NUMBER_INNER_CLASSES;

    public static final String HOT_CLASS_MARKER = "$hot";

    private final EspressoContext context;

    // map from classloader to a map of class names to inner class infos
    private final Map<StaticObject, Map<Symbol<Symbol.Name>, ImmutableClassInfo>> innerClassInfoMap = new WeakHashMap<>();

    // map from classloader to a map of Type to
    private final Map<StaticObject, Map<Symbol<Symbol.Type>, Set<ObjectKlass>>> innerKlassCache = new WeakHashMap<>();

    // list of class info for all top-level classed about to be redefined
    private final Map<Symbol<Symbol.Name>, HotSwapClassInfo> hotswapState = new HashMap<>();

    public InnerClassRedefiner(EspressoContext context) {
        this.context = context;
    }

    public HotSwapClassInfo[] matchAnonymousInnerClasses(List<RedefineInfo> redefineInfos, List<ObjectKlass> removedInnerClasses) throws RedefintionNotSupportedException {
        hotswapState.clear();
        ArrayList<RedefineInfo> unhandled = new ArrayList<>(redefineInfos);

        Map<Symbol<Symbol.Name>, HotSwapClassInfo> handled = new HashMap<>(redefineInfos.size());
        // build inner/outer relationship from top-level to leaf class in order
        // each round below handles classes where the outer class was previously
        // handled
        int handledSize = 0;
        int previousHandledSize = -1;
        while (!unhandled.isEmpty() && handledSize > previousHandledSize) {
            Iterator<RedefineInfo> it = unhandled.iterator();
            while (it.hasNext()) {
                RedefineInfo redefineInfo = it.next();
                Symbol<Symbol.Name> klassName = ClassfileParser.getClassName(context.getClassLoadingEnv(), redefineInfo.getClassBytes());
                Matcher matcher = ANON_INNER_CLASS_PATTERN.matcher(klassName.toString());
                if (matcher.matches()) {
                    // don't assume that associated old klass instance represents this redefineInfo
                    redefineInfo.clearKlass();
                    // anonymous inner class or nested named
                    // inner class of an anonymous inner class
                    // get the outer classinfo if present
                    HotSwapClassInfo info = handled.get(getOuterClassName(klassName));
                    if (info != null) {
                        HotSwapClassInfo classInfo = ClassInfo.create(klassName, redefineInfo.getClassBytes(), info.getClassLoader(), context, redefineInfo.isInnerTestKlass());
                        info.addInnerClass(classInfo);
                        handled.put(klassName, classInfo);
                        it.remove();
                    }
                } else {
                    // pure named class
                    it.remove();
                    if (redefineInfo.getKlass() != null) {
                        HotSwapClassInfo classInfo = ClassInfo.create(redefineInfo, context, redefineInfo.isInnerTestKlass());
                        handled.put(klassName, classInfo);
                        hotswapState.put(klassName, classInfo);
                    }
                }
            }
            previousHandledSize = handledSize;
            handledSize = handled.size();
        }

        // store renaming rules to be used for constant pool patching when class renaming happens
        Map<StaticObject, Map<Symbol<Symbol.Name>, Symbol<Symbol.Name>>> renamingRules = new HashMap<>(0);
        // begin matching from collected top-level classes
        for (HotSwapClassInfo info : hotswapState.values()) {
            matchClassInfo(info, removedInnerClasses, renamingRules);
        }

        // get the full list of changed classes
        ArrayList<HotSwapClassInfo> result = new ArrayList<>();
        collectAllHotswapClasses(hotswapState.values(), result);

        // now, do the constant pool patching
        for (HotSwapClassInfo classInfo : result) {
            if (classInfo.getBytes() != null) {
                Map<Symbol<Symbol.Name>, Symbol<Symbol.Name>> rules = renamingRules.get(classInfo.getClassLoader());
                if (rules != null && !rules.isEmpty()) {
                    try {
                        classInfo.patchBytes(ConstantPoolPatcher.patchConstantPool(classInfo.getBytes(), rules, context));
                    } catch (ClassFormatError ex) {
                        throw new RedefintionNotSupportedException(ErrorCodes.INVALID_CLASS_FORMAT);
                    }
                }
            }
        }
        hotswapState.clear();
        return result.toArray(new HotSwapClassInfo[0]);
    }

    @SuppressWarnings("unchecked")
    private static void collectAllHotswapClasses(Collection<HotSwapClassInfo> infos, ArrayList<HotSwapClassInfo> result) {
        for (HotSwapClassInfo info : infos) {
            result.add(info);
            collectAllHotswapClasses((Collection<HotSwapClassInfo>) info.getInnerClasses(), result);
        }
    }

    private void fetchMissingInnerClasses(HotSwapClassInfo hotswapInfo) throws RedefintionNotSupportedException {
        StaticObject definingLoader = hotswapInfo.getClassLoader();

        ArrayList<Symbol<Symbol.Name>> innerNames = new ArrayList<>(1);
        try {
            searchConstantPoolForInnerClassNames(hotswapInfo, innerNames);
        } catch (ClassFormatError ex) {
            throw new RedefintionNotSupportedException(ErrorCodes.INVALID_CLASS_FORMAT);
        }

        // poke the defining guest classloader for the resources
        for (Symbol<Symbol.Name> innerName : innerNames) {
            if (!hotswapInfo.knowsInnerClass(innerName)) {
                byte[] classBytes = null;
                StaticObject resourceGuestString = context.getMeta().toGuestString(innerName + ".class");
                assert context.getCurrentThread() != null;
                StaticObject inputStream = (StaticObject) context.getMeta().java_lang_ClassLoader_getResourceAsStream.invokeDirect(definingLoader, resourceGuestString);
                if (StaticObject.notNull(inputStream)) {
                    classBytes = readAllBytes(inputStream);
                } else {
                    // There is no safe way to retrieve the class bytes using e.g. a scheme using
                    // j.l.ClassLoader#loadClass and special marker for the type to have the call
                    // end up in defineClass where we could grab the bytes. Guest language
                    // classloaders in many cases have caches for the class name that prevents
                    // forcefully attempting to load previously not loadable classes.
                    // without the class bytes, the matching is less precise and cached un-matched
                    // inner class instances that are executed after redefintion will lead to
                    // NoSuchMethod errors because they're marked as removed
                }
                if (classBytes != null) {
                    hotswapInfo.addInnerClass(ClassInfo.create(innerName, classBytes, definingLoader, context, false));
             