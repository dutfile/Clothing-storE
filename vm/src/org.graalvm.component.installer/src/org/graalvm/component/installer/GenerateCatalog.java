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
package org.graalvm.component.installer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import static org.graalvm.component.installer.BundleConstants.GRAALVM_CAPABILITY;
import org.graalvm.component.installer.jar.JarMetaLoader;
import org.graalvm.component.installer.model.ComponentInfo;
import org.graalvm.component.installer.persist.ComponentPackageLoader;
import org.graalvm.component.installer.remote.FileDownloader;

/**
 *
 * @author sdedic
 */
public final class GenerateCatalog {
    private List<String> params = new ArrayList<>();
    private List<String> locations = new ArrayList<>();
    private String graalVersionPrefix;
    private String graalVersionName;
    private String forceVersion;
    private String forceOS;
    private String forceVariant;
    private String forceArch;
    private String urlPrefix;
    private final StringBuilder catalogContents = new StringBuilder();
    private final StringBuilder catalogHeader = new StringBuilder();
    private Environment env;
    private String graalNameFormatString = "GraalVM %s %s%s/%s";
    private String graalVersionFormatString;

    private static final Map<String, String> OPTIONS = new HashMap<>();

    private static final String OPT_FORMAT_1 = "1"; // NOI18N
    private static final String OPT_FORMAT_2 = "2"; // NOI18N
    private static final String OPT_VERBOSE = "v"; // NOI18N
    private static final String OPT_GRAAL_PREFIX = "g"; // NOI18N
    private static final String OPT_GRAAL_NAME = "n"; // NOI18N
    private static final String OPT_GRAAL_NAME_FORMAT = "f"; // NOI18N
    private static final String OPT_URL_BASE = "b"; // NOI18N
    private static final String OPT_PATH_BASE = "p"; // NOI18N
    private static final String OPT_FORCE_VERSION = "e"; // NO18N
    private static final String OPT_FORCE_OS = "o"; // NO18N
    private static final String OPT_FORCE_VARIANT = "V"; // NO18N
    private static final String OPT_FORCE_ARCH = "a"; // NO18N
    private static final String OPT_SEARCH_LOCATION = "l"; // NOI18N

    static {
        OPTIONS.put(OPT_FORMAT_1, "");  // format v1 < GraalVM 1.0.0-rc16+
        OPTIONS.put(OPT_FORMAT_2, "");  // format v2 = GraalVM 1.0.0-rc16+
        OPTIONS.put(OPT_VERBOSE, "");  // verbose
        OPTIONS.put(OPT_GRAAL_PREFIX, "s");
        OPTIONS.put(OPT_FORCE_VERSION, "s");
        OPTIONS.put(OPT_FORCE_OS, "s");
        OPTIONS.put(OPT_FORCE_VARIANT, "s");
        OPTIONS.put(OPT_GRAAL_NAME_FORMAT, "s");
        OPTIONS.put(OPT_GRAAL_NAME, "s");
        OPTIONS.put(OPT_FORCE_ARCH, "s");
        OPTIONS.put(OPT_GRAAL_NAME, "");   // GraalVM release name
        OPTIONS.put(OPT_URL_BASE, "s");  // URL Base
        OPTIONS.put(OPT_PATH_BASE, "s");  // fileName base
        OPTIONS.put(OPT_SEARCH_LOCATION, "s");   // list files
    }

    private Map<String, GraalVersion> graalVMReleases = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        new GenerateCatalog(args).run();
        System.exit(0);
    }

    private GenerateCatalog(String[] args) {
        this.params = new ArrayList<>(Arrays.asList(args));
    }

    private static byte[] computeHash(File f) throws IOException {
        MessageDigest fileDigest;
        try {
            fileDigest = MessageDigest.getInstance("SHA-256"); // NOI18N
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("Cannot compute digest " + ex.getLocalizedMessage(), ex);
        }
        ByteBuffer bb = ByteBuffer.allocate(2048);
        boolean updated = false;
        try (
                        InputStream is = new FileInputStream(f);
                        ReadableByteChannel bch = Channels.newChannel(is)) {
            int read;
            while (true) {
                read = bch.read(bb);
                if (read < 0) {
                    break;
                }
                bb.flip();
                fileDigest.update(bb);
                bb.clear();
                updated = true;
            }
        }
        if (!updated) {
            fileDigest.update(new byte[0]);
        }

        return fileDigest.digest();
    }

    static String digest2String(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            sb.append(String.format("%02x", (digest[i] & 0xff)));
        }
        return sb.toString();
    }

    static class Spec {
        File f;
        String u;
        String relativePath;

        Spec(File f, String u) {
            this.f = f;
            this.u = u;
        }
    }

    static class GraalVersion {
        String version;
        String os;
        String variant;
        String arch;

        GraalVersion(String version, String os, String variant, String arch) {
            this.version = version;
            this.os = os;
            this.variant = variant;
            this.arch = arch;
        }

    }

    private List<Spec> componentSpecs = new ArrayList<>();

    private Path pathBase = null;

    public void run() throws IOException {
        readCommandLine();
        downloadFiles();
        generateCatalog();
        generateReleases();

        System.out.println(catalogHeader);
        System.out.println(catalogContents);
    }

    private void readCommandLine() throws IOException {
        SimpleGetopt getopt = new SimpleGetopt(OPTIONS) {
            @Override
            public RuntimeException err(String messageKey, Object... args) {
                ComponentInstaller.printErr(messageKey, args);
                System.exit(1);
                return null;
            }
        }.ignoreUnknownCommands(true);
        getopt.setParameters(new LinkedList<>(params));
        getopt.process();
        this.env = new Environment(null, getopt.getPositionalParameters(), getopt.getOptValues());
        this.env.setAllOutputToErr(true);

        String pb = env.optValue(OPT_PATH_BASE);
        if (pb != null) {
            pathBase = SystemUtils.fromUserString(pb).toAbsolutePath();
        }
        urlPrefix = env.optValue(OPT_URL_BASE);
        graalVersionPrefix = env.optValue(OPT_GR