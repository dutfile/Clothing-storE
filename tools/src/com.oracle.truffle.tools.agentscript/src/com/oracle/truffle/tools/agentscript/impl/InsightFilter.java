
/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.agentscript.impl;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.graalvm.collections.EconomicMap;

final class InsightFilter {

    private static final String CONFIG_EXPRESSIONS = "expressions";
    private static final String CONFIG_STATEMENTS = "statements";
    private static final String CONFIG_ROOTS = "roots";
    private static final String CONFIG_ROOT_FILTER = "rootNameFilter";
    private static final String CONFIG_SOURCE_FILTER = "sourceFilter";
    private static final String CONFIG_AT = "at";
    private static final Set<String> CONFIG_PROPS = new HashSet<>(Arrays.asList(CONFIG_EXPRESSIONS, CONFIG_STATEMENTS, CONFIG_ROOTS, CONFIG_ROOT_FILTER, CONFIG_SOURCE_FILTER, CONFIG_AT));

    private static final String AT_SOURCE_PATH = "sourcePath";
    private static final String AT_SOURCE_URI = "sourceURI";
    private static final String AT_LINE = "line";
    private static final String AT_COLUMN = "column";
    private static final Set<String> AT_PROPS = new HashSet<>(Arrays.asList(AT_SOURCE_PATH, AT_SOURCE_URI, AT_LINE, AT_COLUMN));

    private static final InteropLibrary IOP = InteropLibrary.getFactory().getUncached();

    private final Set<Class<? extends Tag>> allTags;
    private final String rootNameRegExp;
    private final String sourcePathRegExp;
    private final URI sourceURI;
    private final int line;
    private final int column;
    private final Reference<Object> rootNameFn;
    private final int rootNameFnHash;
    private final Reference<Object> sourceFilterFn;
    private final int sourceFilterFnHash;

    private InsightFilter(Set<Class<? extends Tag>> allTags, String rootNameRegExp, URI sourceURI, String sourcePathRegExp, int line, int column, Object rootNameFn, Object sourceFilterFn) {
        this.allTags = allTags;
        this.rootNameRegExp = rootNameRegExp;
        this.sourceURI = sourceURI;
        this.sourcePathRegExp = sourcePathRegExp;
        this.line = line;
        this.column = column;
        this.rootNameFn = new WeakReference<>(rootNameFn);
        this.rootNameFnHash = (rootNameFn != null) ? rootNameFn.hashCode() : 0;
        this.sourceFilterFn = new WeakReference<>(sourceFilterFn);
        this.sourceFilterFnHash = (sourceFilterFn != null) ? sourceFilterFn.hashCode() : 0;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 79 * hash + Objects.hashCode(this.allTags);
        hash = 79 * hash + Objects.hashCode(this.rootNameRegExp);
        hash = 79 * hash + Objects.hashCode(this.sourcePathRegExp);
        hash = 79 * hash + Objects.hashCode(this.sourceURI);
        hash = 79 * hash + line;
        hash = 79 * hash + column;
        hash = 79 * hash + rootNameFnHash;
        hash = 79 * hash + sourceFilterFnHash;
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final InsightFilter other = (InsightFilter) obj;
        if (this.rootNameFn.get() != other.rootNameFn.get()) {
            return false;
        }
        if (this.sourceFilterFn.get() != other.sourceFilterFn.get()) {
            return false;
        }
        if (!Objects.equals(this.rootNameRegExp, other.rootNameRegExp)) {
            return false;
        }
        if (!Objects.equals(this.allTags, other.allTags)) {
            return false;
        }
        if (!Objects.equals(this.sourcePathRegExp, other.sourcePathRegExp)) {
            return false;
        }
        if (!Objects.equals(this.sourceURI, other.sourceURI)) {
            return false;
        }
        if (this.line != other.line) {
            return false;
        }
        if (this.column != other.column) {
            return false;
        }
        return true;
    }

    Class<?>[] getTags() {
        return allTags.toArray(new Class<?>[0]);
    }

    Set<Class<? extends Tag>> getTagsSet() {
        return allTags;
    }

    String getRootNameRegExp() {
        return rootNameRegExp;
    }

    String getSourcePathRegExp() {
        return sourcePathRegExp;
    }

    URI getSourceURI() {
        return sourceURI;
    }

    int getLine() {