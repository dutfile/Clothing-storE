/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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

package org.graalvm.visualizer.source;

import org.graalvm.visualizer.data.InputGraph;
import org.graalvm.visualizer.data.InputNode;
import static org.graalvm.visualizer.data.KnownPropertyNames.PROPNAME_NODE_SOURCE_POSITION;
import org.graalvm.visualizer.data.serialization.lazy.LazySerDebugUtils;
import org.openide.filesystems.FileObject;
import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 *
 * @author sdedic
 */
public class GraphSourceTest extends GraphSourceTestBase {
    public GraphSourceTest(String name) {
        super(name);
    }

    private void assertContainsSource(Collection<FileObject> files, String path) {
        FileObject s = sourcePath.findResource(path);
        assertNotNull("Source " + path + " must exist", s);
        assertTrue("File " + path + " must be present", files.contains(s));
    }

    private void assertNotContainsSource(Collection<FileObject> files, String path) {
        FileObject s = sourcePath.findResource(path);
        assertNotNull("Source " + path + " must exist", s);
        assertFalse("File " + path + " must NOT be present", files.contains(s));
    }

    /**
     * Loads stacktraces, is able to resolve locations. Checks that files are in
     * place. Checks that each file's location is ordered.
     *
     * @throws Exception
     */
    public void testGetFileLocations() throws Exception {
        PlatformLocationResolver.enabled = true;
        GraphSource src = GraphSource.getGraphSource(magnitudeGraph);
        src.prepare().get();
        Collection<FileObject> files = src.getSourceFiles();
        assertContainsSource(files, "java/lang/StringBuilder.java");
        assertContainsSource(files, "java/lang/Math.java");
        assertContainsSource(files, "java/util/Locale.java");
        assertContainsSource(files, "java/util/Formatter.java");

        for (FileObject f : files) {
            List<Location> locs = src.getFileLocations(f, false);
            Set<Location> uniqueLocs = new HashSet<>(locs);
            assertEquals(locs.size(), uniqueLocs.size());
            assertNotNull(locs);
            assertFalse(locs.isEmpty());
            int lineNo = -1;
            for (Location l : locs) {
                assertTrue(lineNo <= l.getLine());
                lineNo = l.getLine();
            }
        }
    }

    /**
     * Checks that locations are initially unresolved, they are not reported
     * from files, but are recognized by the GraphSource.
     *
     * @throws Exception
     */
    public void testPartiallyResolvedLocations() throws Exception {
        PlatformLocationResolver.enabled = true;
        PlatformLocationResolver.enablePackage("java/util", true);

        GraphSource src = GraphSource.getGraphSource(magnitudeGraph);
        Collection<FileObject> files = src.getSourceFiles();

        assertNotContainsSource(files, "java/lang/StringBuilder.java");
        assertNotContainsSource(files, "java/lang/Math.java");
        assertContainsSource(files, "java/util/Locale.java");
        assertContainsSource(files, "java/util/Formatter.java");

        // java.lang was not resolved, but the locations must be registered
        FileKey langString = src.getFileRegistry().enter(new FileKey("text/x-java", "java/lang/StringBuilder.java"), magnitudeGraph);
        FileKey langMath = src.getFileRegistry().enter(new FileKey("text/x-java", "java/lang/Math.java"), magnitudeGraph);

        assertNotNull(langString);
        assertNotNull(langMath);

        Collection<FileKey> fks = src.getFileKeys();
        assertTrue(fks.contains(langString));
        assertTrue(fks.contains(langMath));
    }

    public void testUnresolvedLocationsBecomeResolved() throws Exception {
        PlatformLocationResolver.enabled = true;
        PlatformLocationResolver.enablePackage("java/util", true);

        GraphSource src = GraphSource.getGraphSource(magnitudeGraph);
        Collection<FileObject> files = src.getSourceFiles();

        assertNotContainsSource(files, "java/lang/StringBuilder.java");
        assertNotContainsSource(files, "java/lang/Math.java");
        assertContainsSource(files, "java/util/Locale.java");
        assertContainsSource(files, "java/util/Formatter.java");

        // java.lang was not resolved, but the locations must be registered
        FileKey langString = src.getFileRegistry().enter(new FileKey("text/x-java", "java/lang/StringBuilder.java"), magnitudeGraph);
        FileKey langMath = src.getFileRegistry().enter(new FileKey("text/x-java", "java/lang/Math.java"), magnitudeGraph);

        Collection<FileKey> fks = src.getFileKeys();
        assertTrue(fks.contains(langString));
        assertTrue(fks.contains(langMath));

        FileObject fMath = sourcePath.findResource("java/lang/Math.java");
        FileObject fString = sourcePath.findResource("java/lang/StringBuilder.java");

        Semaphore lck = new Semaphore(0);
        src.getFileRegistry().addFileRegistryListener((e) -> {
            lck.release();
        });
        // these are tested to fire events elsewhere, so hook at the event
        src.getFileRegistry().resolve(langMath, fMath);
        src.getFileRegistry().resolve(langString, fString);

        lck.acquire();
        // wait for the event task to finish:
        FileRegistry.RP.post(() -> {
        }).waitFinished();
        files = src.getSourceFiles();
        assertContainsSource(files, "java/lang/StringBuilder.java");
        assertContainsSource(files, "java/lang/Math.java");
    }

    public void xtestGraphSourceRetainsGraphContents_noload() throws Exception {
        LazySerDebugUtils.setLargeThreshold(100);

        URL bigv = GraphSourceTest.class.getResource("inlined_source.bgv");
        File f = new File(bigv.toURI());

        LazySerDebugUtils.loadResource(rootDocument, f);
        magnitudeGraph = findElement("3900:/After phase org.graalvm.compiler.phases.common.inlining.InliningPhase");

        GraphSource src = GraphSource.getGraphSource(magnitudeGraph);
        src.prepare().get();
        assertFalse(magnitudeGraph.getNodes().isEmpty());
        Reference<InputNode> ref = new WeakReference<>(magnitudeGraph.getNodes().iterator().next());
        magnitudeGraph = null;
        try {
            assertGC("", ref, Collections.singleton(rootDocument));
        } catch (AssertionError err) {
            // actually OK
            return;
        }
        fail("Graph was released");
    }

    public void testGraphContentsReleased_noload() throws Exception {
        LazySerDebugUtils.setLargeThreshold(100);

        URL b