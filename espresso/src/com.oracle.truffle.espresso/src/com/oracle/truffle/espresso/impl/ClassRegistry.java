/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.truffle.espresso.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.espresso.classfile.Constants;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.Type;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.impl.ModuleTable.ModuleEntry;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.perf.DebugCloseable;
import com.oracle.truffle.espresso.perf.DebugTimer;
import com.oracle.truffle.espresso.redefinition.DefineKlassListener;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoThreadLocalState;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaType;

/**
 * A {@link ClassRegistry} maps type names to resolved {@link Klass} instances. Each class loader is
 * associated with a {@link ClassRegistry} and vice versa.
 *
 * This class is analogous to the ClassLoaderData C++ class in HotSpot.
 */
public abstract class ClassRegistry {

    /**
     * Storage class used to propagate information in the case of special kinds of class definition
     * (hidden, anonymous or with a specified protection domain).
     * 
     * Regular class definitions will use the {@link #EMPTY} instance.
     * 
     * Hidden and Unsafe anonymous classes are handled by not registering them in the class loader
     * registry.
     */
    public static final class ClassDefinitionInfo {
        public static final ClassDefinitionInfo EMPTY = new ClassDefinitionInfo(null, null, null, null, null, false, false);

        // Constructor for regular definition, but with a specified protection domain
        public ClassDefinitionInfo(StaticObject protectionDomain) {
            this(protectionDomain, null, null, null, null, false, false);
        }

        // Constructor for Unsafe anonymous class definition.
        public ClassDefinitionInfo(StaticObject protectionDomain, ObjectKlass hostKlass, StaticObject[] patches) {
            this(protectionDomain, hostKlass, patches, null, null, false, false);
        }

        // Constructor for Hidden class definition.
        public ClassDefinitionInfo(StaticObject protectionDomain, ObjectKlass dynamicNest, StaticObject classData, boolean isStrongHidden) {
            this(protectionDomain, null, null, dynamicNest, classData, true, isStrongHidden);
        }

        private ClassDefinitionInfo(StaticObject protectionDomain,
                        ObjectKlass hostKlass,
                        StaticObject[] patches,
                        ObjectKlass dynamicNest,
                        StaticObject classData,
                        boolean isHidden,
                        boolean isStrongHidden) {
            // isStrongHidden => isHidden
            assert !isStrongHidden || isHidden;
            this.protectionDomain = protectionDomain;
            this.hostKlass = hostKlass;
            this.patches = patches;
            this.dynamicNest = dynamicNest;
            this.classData = classData;
            this.isHidden = isHidden;
            this.isStrongHidden = isStrongHidden;
            assert isAnonymousClass() || patches == null;
        }

        public final StaticObject protectionDomain;

        // Unsafe Anonymous class
        public final ObjectKlass hostKlass;
        public final StaticObject[] patches;

        // Hidden class
        public final ObjectKlass dynamicNest;
        public final StaticObject classData;
        public final boolean isHidden;
        public final boolean isStrongHidden;
        public long klassID = -1;

        public boolean addedToRegistry() {
            return !isAnonymousClass() && !isHidden();
        }

        public boolean isAnonymousClass() {
            return hostKlass != null;
        }

        public boolean isHidden() {
            return isHidden;
        }

        public boolean isStrongHidden() {
            return isStrongHidden;
        }

        public int patchFlags(int classFlags) {
            int flags = classFlags;
            if (isHidden()) {
                flags |= Constants.ACC_IS_HIDDEN_CLASS;
            }
            return flags;
        }

        public void initKlassID(long futureKlassID) {
            this.klassID = futureKlassID;
        }
    }

    private static final DebugTimer KLASS_PROBE = DebugTimer.create("klass probe");
    private static final DebugTimer KLASS_DEFINE = DebugTimer.create("klass define");
    private static final DebugTimer KLASS_PARSE = DebugTimer.create("klass parse");

    /**
     * Traces the classes being initialized by this thread. Its only use is to be able to detect
     * class circularity errors. A class being defined, that needs its superclass also to be defined
     * will be pushed onto this stack. If the superclass is already present, then there is a
     * circularity error.
     */
    // TODO: Rework this, a thread local is certainly less than optimal.

    private DefineKlassListener defineKlassListener;

    public void registerOnLoadListener(DefineKlassListener listener) {
        defineKlassListener = listener;
    }

    public static final class TypeStack {
        Node head;

        public TypeStack() {
        }

        static final class Node {
            Symbol<Type> entry;
            Node next;

            Node(Symbol<Type> entry, Node next) {
                this.entry = entry;
                this.next = next;
            }
        }

        boolean isEmpty() {
            return head == null;
        }

        boolean contains(Symbol<Type> type) {
            Node curr = head;
            while (curr != null) {
                if (curr.entry == type) {
                    return true;
                }
                curr = curr.next;
            }
            return false;
        }

        Symbol<Type> pop() {
            if (isEmpty()) {
                CompilerAsserts.neverPartOfCompilation();
                throw EspressoError.shouldNotReachHere();
            }
            Symbol<Type> res = head.entry;
            head = head.next;
            return res;
        }

        void push(Symbol<Type> type) {
            head = new Node(type, head);
        }
    }

    private final long loaderID;

    private ModuleEntry unnamed;
    private final PackageTable packages;
    private final ModuleTable modules;

    public ModuleEntry getUnnamedModule() {
        return unnamed;
    }

    public final long getLoaderID() {
        return loaderID;
    }

    public ModuleTable modules() {
        return modules;
    }

    public PackageTable packages() {
        return packages;
    }

    /**
     * The map from symbol to classes for the classes defined by the class loader associated with
     * this registry. Use of {@link ConcurrentHashMap} allows for atomic insertion while still
     * supporting fast, non-blocking lookup. There's no need for deletion as class unloading removes
     * a whole class registry and all its contained classes.
     */
    protected final ConcurrentHashMap<Symbol<Type>, ClassRegistries.RegistryEntry> classes = new ConcurrentHashMap<>();

    /**
     * Strong hidden classes must be referenced by the class loader data to prevent them from being
     * reclaimed, while not appearing in the actual registry. This field simply keeps those hidden
     * classes strongly reachable from the class registry.
     */
    private volatile Collection<Klass> strongHiddenKlasses = null;

    private Object getStrongHiddenClassRegistrationLock() {
        return this;
    }

    private void registerStrongHiddenClass(Klass klass) {
        synchronized (getStrongHiddenClassRegistrationLock()) {
            if (strongHiddenKlasses == null) {
                strongHiddenKlasses = new ArrayList<>();
            }
            strongHiddenKlasses.add(klass);
        }
    }

    protected ClassRegistry(long loaderID) {
        this.loaderID = loaderID;
        ReadWriteLock rwLock = new ReentrantReadWriteLock();
        this.packages = new PackageTable(rwLock);
        this.modules = new ModuleTable(rwLock);
    }

    public void initUnnamedModule(StaticObject unnamedModule) {
        this.unnamed = ModuleEntry.createUnnamedModuleEntry(unnamedModule, this);
    }

    /**
     * Queries a registry to load a Klass for us.
     *
     * @param type the symbolic reference to the Klas