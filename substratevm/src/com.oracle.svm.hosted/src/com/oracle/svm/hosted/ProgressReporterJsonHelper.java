/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.hosted;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.core.util.json.JsonWriter;

class ProgressReporterJsonHelper {
    protected static final long UNAVAILABLE_METRIC = -1;
    private static final String ANALYSIS_RESULTS_KEY = "analysis_results";
    private static final String GENERAL_INFO_KEY = "general_info";
    private static final String IMAGE_DETAILS_KEY = "image_details";
    private static final String RESOURCE_USAGE_KEY = "resource_usage";

    private final Map<String, Object> statsHolder = new HashMap<>();
    private final Path jsonOutputFile;

    ProgressReporterJsonHelper(Path outFile) {
        this.jsonOutputFile = outFile;
    }

    private void recordSystemFixedValues() {
        putResourceUsage(ResourceUsageKey.CPU_CORES_TOTAL, Runtime.getRuntime().availableProcessors());
        putResourceUsage(ResourceUsageKey.MEMORY_TOTAL, getTotalSystemMemory());
    }

    @SuppressWarnings("deprecation")
    private static long getTotalSystemMemory() {
        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        return ((com.sun.management.OperatingSystemMXBean) osMXBean).getTotalPhysicalMemorySize();
    }

    @SuppressWarnings("unchecked")
    public void putAnalysisResults(AnalysisResults key, long value) {
        Map<String, Object> analysisMap = (Map<String, Object>) statsHolder.computeIfAbsent(ANALYSIS_RESULTS_KEY, k -> new HashMap<>());
        Map<String, Object> bucketMap = (Map<String, Object>) analysisMap.computeIfAbsent(key.bucket(), bk -> new HashMap<>());
        bucketMap.put(key.jsonKey(), value);
    }

    @SuppressWarnings("unchecked")
    public void putGeneralInfo(GeneralInfo info, String value) {
        Map<String, Object> generalInfoMap = (Map<String, Object>) statsHolder.computeIfAbsent(GENERAL_INFO_KEY, gi -> new HashMap<>());
        generalInfoMap.put(info.jsonKey(), value);
    }

    @SuppressWarnings("unchecked")
    public void putImageDetails(ImageDetailKey key, Object value) {
        Map<String, Object> imageDetailsMap = (Map<String, Object>) statsHolder.computeIfAbsent(IMAGE_DETAILS_KEY, id -> new HashMap<>());
        if (key.bucket == null && key.subBucket == null) {
            imageDetailsMap.put(key.jsonKey, value);
        } else if (key.subBucket == null) {
            assert key.bucket != null;
            Map<String, Object> bucketMap = (Map<String, Object>) imageDetailsMap.computeIfAbsent(key.bucket, sb -> new HashMap<>());
            bucketMap.put(key.jsonKey, value);
        } else {
            assert key.subBucket != null;
            Map<String, Object> bucketMap = (Map<String, Object>) imageDetailsMap.computeIfAbsent(key.bucket, sb -> new HashMap<>());
            Map<String, Object> subbucketMap = (Map<String, Object>) bucketMap.computeIfAbsent(key.subBucket, sb -> new HashMap<>());
            subbucketMap.put(key.jsonKey, value);
        }
    }

    @SuppressWarnings("unchecked")
    public void putResourceUsage(ResourceUsageKey key, Object value) {
        Map<String, Object> resUsageMap = (Map<String, Object>) statsHolder.computeIfAbsent(RESOURCE_USAGE_KEY, ru -> new HashMap<>());
        if (key.bucket != null) {
            Map<String, Object> subMap = (Map<String, Object>) resUsageMap.computeIfAbsent(key.bucket, k -> new HashMap<>());
            subMap.put(key.jsonKey, value);
        } else {
            resUsageMap.put(key.jsonKey, value);
        }
    }

    public Path printToFile() {
        recordSystemFixedValues();
        String description = "image statistics in json";
        return ReportUt