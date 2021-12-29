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
        NodeClass nodeClass = node.getNodeClass();

        for (Object nodeField : nodeClass.getNodeFieldArray()) {
            if (nodeClass.isChildField(nodeField)) {
                Object child = nodeClass.getFieldObject(nodeField, node);
                if (child != null) {
                    nodes.add((Node) child);
                }
            } else if (nodeClass.isChildrenField(nodeField)) {
                Object[] children = (Object[]) nodeClass.getFieldObject(nodeField, node);
                if (children != null) {
                    for (Object child : children) {
                        if (child != null) {
                            nodes.add((Node) child);
                        }
                    }
                }
            } else if (nodeClass.nodeFieldsOrderedByKind()) {
                break;
            }
        }
        return nodes;
    }

    /** @since 0.8 or earlier */
    public static <T extends Node> T nonAtomicReplace(Node oldNode, T newNode, CharSequence reason) {
        oldNode.replaceHelper(newNode, reason);
        return newNode;
    }

    /** @since 0.8 or earlier */
    public static boolean replaceChild(Node parent, Node oldChild, Node newChild) {
        return replaceChild(parent, oldChild, newChild, false);
    }

    /*
     * Fast version of child adoption.
     */
    static void adoptChildrenHelper(Node currentNode) {
        NodeClass clazz = currentNode.getNodeClass();
        for (Object field : clazz.getNodeFieldArray()) {
            if (clazz.isChildField(field)) {
                Object child = clazz.getFieldObject(field, currentNode);
                if (child != null) {
                    Node node = (Node) child;
                    if (node.getParent() != currentNode) {
                        currentNode.adoptHelper(node);
                    }
                }
            } else if (clazz.isChildrenField(field)) {
                Object arrayObject = clazz.getFieldObject(field, currentNode);
                if (arrayObject == null) {
                    continue;
                }
                Object[] array = (Object[]) arrayObject;
                for (int i = 0; i < array.length; i++) {
                    Object child = array[i];
                    if (child != null) {
                        Node node = (Node) child;
                        if (node.getParent() != currentNode) {
                            currentNode.adoptHelper(node);
                        }
                    }
                }
            } else if (clazz.nodeFieldsOrderedByKind()) {
                break;
            }
        }

    }

    /*
     * Slow version of child adoption. Unlike the adoptChildrenHelper this method traverses (and
     * counts) all nodes, i.e. including the ones already adopted.
     */
    static int adoptChildrenAndCountHelper(Node currentNode) {
        int count = 0;
        NodeClass clazz = currentNode.getNodeClass();
        for (Object field : clazz.getNodeFieldArray()) {
            if (clazz.isChildField(field)) {
                Object child = clazz.getFieldObject(field, currentNode);
                if (child != null) {
                    Node node = (Node) child;
                    count += currentNode.adoptAndCountHelper(node);
                }
            } else if (clazz.isChildrenField(field)) {
                Object arrayObject = clazz.getFieldObject(field, currentNode);
                if (arrayObject == null) {
                    continue;
                }
                Object[] array = (Object[]) arrayObject;
                for (int i = 0; i < array.length; i++) {
                    Object child = array[i];
                    if (child != null) {
                        Node node = (Node) child;
                        count += currentNode.adoptAndCountHelper(node);
                    }
                }
            } else if (clazz.nodeFieldsOrderedByKind()) {
                break;
            }
        }
        return count;
    }

    static boolean replaceChild(Node parent, Node oldChild, Node newChild, boolean adopt) {
        CompilerAsserts.neverPartOfCompilation("do not replace Node child from compiled code");
        NodeClass nodeClass = parent.getNodeClass();

        /*
         * It is also important to check the old node for replacement, because we exclude non
         * replaceable nodes from the node subtype analysis in TruffleBaseFeature.
         */
        if (!oldChild.getNodeClass().isReplaceAllowed()) {
            throw new IllegalArgumentException(String.format("Replaced node type '%s' does not allow replacement.", oldChild.getClass().getName()));
        }

        if (!newChild.getNodeClass().isReplaceAllowed()) {
            throw new IllegalArgumentException(String.format("Replacing node type '%s' does not allow replacement.", newChild.getClass().getName()));
        }

        for (Object nodeField : nodeClass.getNodeFieldArray()) {
            if (nodeClass.isChildField(nodeField)) {
                if (nodeClass.getFieldObject(nodeField, parent) == oldChild) {
                    if (adopt) {
                        parent.adoptHelper(newChild);
                    }
                    nodeClass.putFieldObject(nodeField, parent, newChild);
                    return true;
                }
            } else if (nodeClass.isChildrenField(nodeField)) {
                Object arrayObject = nodeClass.getFieldObject(nodeField, parent);
                if (arrayObject != null) {
                    Object[] array = (Object[]) arrayObject;
                    for (int i = 0; i < array.length; i++) {
                        if (array[i] == oldChild) {
                            if (adopt) {
                                parent.adoptHelper(newChild);
                            }
                            try {
                                array[i] = newChild;
                            } catch (ArrayStoreException e) {
                                throw replaceChildIllegalArgumentException(nodeField, array.getClass(), newChild);
                            }
                            return true;
                        }
                    }
                }
            } else if (nodeClass.nodeFieldsOrderedByKind()) {
                break;
            }
        }

        return false;
    }

    private static IllegalArgumentException replaceChildIllegalArgumentException(Object nodeField, Class<?> fieldType, Node newChild) {
        return new IllegalArgumentException("Cannot set element of " + fieldType.getName() + " field " + nodeField + " to " + (newChild == null ? "null" : newChild.getClass().getName()));
    }

    /**
     * Finds the field in a parent node and returns its name. Utility for debugging and tracing
     * purposes.
     *
     * @since 22.2
     */
    public static String findChildFieldName(Node parent, Node child) {
        return getNodeFieldName(parent, child, null);
    }

    /**
     * Finds and retrieves all field names of a node class. This is a utility intended for debugging
     * and tracing purposes.
     *
     * @since 22.2
     */
    public static List<String> collectFieldNames(Class<? extends Node> clazz) {
        NodeClass nodeClass = NodeClass.get(clazz);
        Object[] fields = nodeClass.getNodeFieldArray();
        String[] fieldNames = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            fieldNames[i] = nodeClass.getFieldName(fields[i]);
        }
        return Arrays.asList(fieldNames);
    }

    /**
     * Finds and retrieves all {@link Child} and {@link Children} annotated field names and values.
     * This is a utility intended for debugging and tracing purposes.
     *
     * @since 22.2
     */
    public static Map<String, Node> collectNodeChildren(Node node) {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        NodeClass nodeClass = NodeClass.get(node);

        for (Object field : nodeClass.getNodeFieldArray()) {
            if (nodeClass.isChildField(field)) {
                Object value = nodeClass.getFieldObject(field, node);
                if (value != null) {
                    nodes.put(nodeClass.getFieldName(field), (Node) value);
                }
            } else if (nodeClass.isChildrenField(field)) {
                Object value = nodeClass.getFieldObject(field, node);
                if (value != null) {
                    Object[] children = (Object[]) value;
                    for (int i = 0; i < children.length; i++) {
                        if (children[i] != null) {
                            nodes.put(nodeClass.getFieldName(field) + "[" + i + "]", (Node) children[i]);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Finds and retrieves all properties of a node. Properties of a node are all fields not
     * annotated with {@link Child} or {@link Children}. This is a utility intended for debugging
     * and tracing purposes.
     *
     * @since 22.2
     */
    public static Map<String, Object> collectNodeProperties(Node node) {
        LinkedHashMap<String, Object> nodes = new LinkedHashMap<>();
        NodeClass nodeClass = NodeClass.get(node);
        for (Object field : nodeClass.getNodeFieldArray()) {
            if (!nodeClass.isChildField(field) && !nodeClass.isChildrenField(field)) {
                nodes.put(nodeClass.getFieldName(field), nodeClass.getFieldValue(field, node));
            }
        }
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Finds the field in a parent node, if any, that holds a specified child node.
     *
     * @return the field (possibly an array) holding the child, {@code null} if not found.
     * @since 0.8 or earlier
     */
    static Object findChildField(Node parent, Node child) {
        assert child != null;
        NodeClass parentNodeClass = parent.getNodeClass();

        for (Object field : parentNodeClass.getNodeFieldArray()) {
            if (parentNodeClass.isChildField(field)) {
                return field;
            } else if (parentNodeClass.isChildrenField(field)) {
                Object arrayObject = parentNodeClass.getFieldValue(field, child);
                if (arrayObject != null) {
                    Object[] array = (Object[]) arrayObject;
                    for (int i = 0; i < array.length; i++) {
                        if (array[i] == child) {
                            return field;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Determines whether a proposed child replacement would be safe: structurally and type.
     *
     * @since 0.8 or earlier
     */
    public static boolean isReplacementSafe(Node parent, Node oldChild, Node newChild) {
        if (parent != null) {
            if (!parent.isAdoptable()) {
                return false;
            }
            NodeClass nodeClass = parent.getNodeClass();
            for (Object field : nodeClass.getNodeFieldArray()) {
                if (nodeClass.isChildField(field)) {
                    if (nodeClass.getFieldObject(field, parent) == oldChild) {
                        if (!oldChild.getNodeClass().isReplaceAllowed() || !newChild.getNodeClass().isReplaceAllowed()) {
                            return false;
                        }
                        return nodeClass.getFieldType(field).isAssignableFrom(newChild.getClass());
                    }
                } else if (nodeClass.isChildrenField(field)) {
                    Object arrayObject = nodeClass.getFieldObject(field, parent);
                    if (arrayObject != null) {
                        Object[] array = (Object[]) arrayObject;
                        for (int i = 0; i < array.length; i++) {
                            if (array[i] == oldChild) {
                                if (!oldChild.getNodeClass().isReplaceAllowed() || !newChild.getNodeClass().isReplaceAllowed()) {
                                    return false;
                                }
                                return nodeClass.getFieldType(field).getComponentType().isAssignableFrom(newChild.getClass());
                            }
                        }
                    }
                } else if (nodeClass.nodeFieldsOrderedByKind()) {
                    break;
                }
            }
            return true;
        }
        // if a child was not found the replacement can be considered safe.
        return false;
    }

    /**
     * Executes a closure for every non-null child of the parent node.
     *
     * @return {@code true} if all children were visited, {@code false} otherwise
     * @since 0.8 or earlier
     */
    public static boolean forEachChild(Node parent, NodeVisitor visitor) {
        CompilerAsserts.neverPartOfCompilation("do not iterate over Node children from compiled code");
        Objects.requireNonNull(visitor);
        NodeClass nodeClass = parent.getNodeClass();

        for (Object field : nodeClass.getNodeFieldArray()) {
            if (nodeClass.isChildField(field)) {
                Object child = nodeClass.getFieldObject(field, parent);
                if (child != null) {
                    if (!visitor.visit((Node) child)) {
                        return false;
   