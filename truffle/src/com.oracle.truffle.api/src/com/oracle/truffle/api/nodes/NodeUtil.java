/*
 * Copyright (c) 2012, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.nodes;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.Node.Children;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Utility class that manages the special access methods for node instances.
 *
 * @since 0.8 or earlier
 */
public final class NodeUtil {

    private NodeUtil() {
    }

    /** @since 0.8 or earlier */
    @SuppressWarnings("unchecked")
    public static <T extends Node> T cloneNode(T orig) {
        return (T) orig.deepCopy();
    }

    static Node deepCopyImpl(Node orig) {
        CompilerAsserts.neverPartOfCompilation("do not call Node.deepCopyImpl from compiled code");
        final Node clone = orig.copy();
        if (!sameType(clone, orig)) {
            throw CompilerDirectives.shouldNotReachHere(String.format("Invalid return type after copy(): orig.getClass() = %s, clone.getClass() = %s",
                            orig.getClass(), clone == null ? "null" : clone.getClass()));
        }
        NodeClass nodeClass = clone.getNodeClass();
        clone.setParent(null);

        for (Object field : nodeClass.getNodeFieldArray()) {
            if (nodeClass.isChildField(field)) {
                Node child = (Node) nodeClass.getFieldObject(field, orig);
                if (child != null) {
                    Node clonedChild = child.deepCopy();
                    clonedChild.setParent(clone);
                    if (!sameType(child, clonedChild)) {
                        throw CompilerDirectives.shouldNotReachHere(
                                        String.format("Invalid return type after deepCopy(): orig.getClass() = %s, orig.fieldName = '%s', child.getClass() = %s, clonedChild.getClass() = %s",
                                                        orig.getClass(), nodeClass.getFieldName(field), child.getClass(), clonedChild.getClass()));
                    }
                    nodeClass.putFieldObject(field, clone, clonedChild);
                }
            } else if (nodeClass.isChildrenField(field)) {
                Object[] children = (Object[]) nodeClass.getFieldObject(field, orig);
                if (children != null) {
                    Object[] clonedChildren;
                    if (children.length > 0) {
                        clonedChildren = (Object[]) Array.newInstance(children.getClass().getComponentType(), children.length);
                        for (int i = 0; i < children.length; i++) {
                            if (children[i] != null) {
                                Node clonedChild = ((Node) children[i]).deepCopy();
                                if (!sameType(children[i], clonedChild)) {
                                    throw CompilerDirectives.shouldNotReachHere(String.format(
                                                    "Invalid return type after deepCopy(): orig.getClass() = %s, orig.fieldName = '%s', children[i].getClass() = %s, clonedChild.getClass() = %s",
                                                    orig.getClass(), nodeClass.getFieldName(field), children[i].getClass(), clonedChild == null ? "null" : clonedChild.getClass()));
                                }
                                clonedChild.setParent(clone);
                                clonedChildren[i] = clonedChild;
                            }
                        }
                    } else {
                        clonedChildren = children;
                    }
                    nodeClass.putFieldObject(field, clone, clonedChildren);
                }
            } else if (nodeClass.isCloneableField(field)) {
                Object cloneable = nodeClass.getFieldObject(field, clone);
                if (cloneable != null && cloneable == nodeClass.getFieldObject(field, orig)) {
                    Object clonedClonable = ((NodeCloneable) cloneable).clone();
                    if (!sameType(cloneable, clonedClonable)) {
                        throw CompilerDirectives.shouldNotReachHere(
                                        String.format("Invalid return type after clone(): orig.getClass() = %s, orig.fieldName = '%s', cloneable.getClass() = %s, clonedCloneable.getClass() =%s",
                                                        orig.getClass(), nodeClass.getFieldName(field), cloneable.getClass(), clonedClonable == null ? "null" : clonedClonable.getClass()));
                    }
                    nodeClass.putFieldObject(field, clone, clonedClonable);
                }
            } else if (nodeClass.nodeFieldsOrderedByKind()) {
                break;
            }
        }
        return clone;
    }

    private static boolean sameType(Object clone, Object orig) {
        if (clone == null || orig == null) {
            return clone == orig;
        }
        return clone.getClass() == orig.getClass();
    }

    /** @since 0.8 or earlier */
    public static List<Node> findNodeChildren(Node node) {
        CompilerAsserts.neverPartOfCompilation("do not call Node.findNodeChildren from compiled code");
        List<Node> nodes = new ArrayList<>();
        NodeClass nodeClass = node.getNodeClass(