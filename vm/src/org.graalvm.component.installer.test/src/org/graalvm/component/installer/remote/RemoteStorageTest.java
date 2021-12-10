/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.component.installer.remote;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.graalvm.component.installer.BundleConstants;
import org.graalvm.component.installer.CommonConstants;
import org.graalvm.component.installer.MetadataException;
import org.graalvm.component.installer.SystemUtils;
import org.graalvm.component.installer.TestBase;
import org.graalvm.component.installer.Version;
import org.graalvm.component.installer.commands.MockStorage;
import org.graalvm.component.installer.model.ComponentInfo;
import org.graalvm.component.installer.model.ComponentRegistry;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RemoteStorageTest extends TestBase {
    private static final String TEST_GRAAL_FLAVOUR = "linux_amd64";
    private static final String TEST_BASE_URL_DIR = "https://graalvm.io/";
    private static final String TEST_BASE_URL = TEST_BASE_URL_DIR + "download/catalog";
    private RemotePropertiesStorage remStorage;
    private MockStorage storage;
    private ComponentRegistry localRegistry;
    private Properties catalogProps = new Properties();

    private String graalVersion = "0.33-dev";
    private String graalSelector = TEST_GRAAL_FLAVOUR;

    @Rule public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        storage = new MockStorage();
        remStorage = new RemotePropertiesStorage(this, localRegistry, catalogProps, graalSelector,
                        Version.fromString(graalVersion), SystemUtils.toURL(TEST_BASE_URL));
        try (InputStream is = getClass().getResourceAsStream("catalog.properties")) {
            catalogProps.load(is);
        }
    }

    private void loadCatalog(String s) throws IOException {
        catalogProps.clear();
        localRegistry = new ComponentRegistry(this, storage);
        try (InputStream is = getClass().getResourceAsStream(s)) {
            catalogProps.load(is);
        }
    }

    private void forceLoadCatalog(String s) throws IOException {
        loadCatalog(s);
        remStorage = new RemotePropertiesStorage(this, localRegistry, catalogProps, graalSelector,
                        Version.fromString(graalVersion), SystemUtils.toURL(TEST_BASE_URL));
    }

    @Test
    public void testListIDs() throws Exception {
        Set<String> ids = remStorage.listComponentIDs();

        List<String> l = new ArrayList<>(ids);
        Collections.sort(l);

        assertEquals(Arrays.asList("r", "ruby"), l);
    }

    private Compon