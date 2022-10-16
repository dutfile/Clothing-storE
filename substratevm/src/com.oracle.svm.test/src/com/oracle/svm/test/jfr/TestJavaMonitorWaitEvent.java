
/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, 2022, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.test.jfr;

import static java.lang.Math.abs;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oracle.svm.core.jfr.JfrEvent;
import org.junit.Test;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

public class TestJavaMonitorWaitEvent extends JfrTest {
    private static final int MILLIS = 50;
    private static final int COUNT = 10;

    private final Helper helper = new Helper();
    private String producerName;
    private String consumerName;

    @Override
    public String[] getTestedEvents() {
        return new String[]{JfrEvent.JavaMonitorWait.getName()};
    }

    @Override
    public void validateEvents() throws Throwable {
        int prodCount = 0;
        int consCount = 0;
        String lastEventThreadName = null; // should alternate if buffer is 1
        for (RecordedEvent event : getEvents()) {
            String eventThread = event.<RecordedThread> getValue("eventThread").getJavaName();
            String notifThread = event.<RecordedThread> getValue("notifier") != null ? event.<RecordedThread> getValue("notifier").getJavaName() : null;
            assertTrue("No event thread", eventThread != null);
            if ((!eventThread.equals(producerName) && !eventThread.equals(consumerName)) ||
                            !event.<RecordedClass> getValue("monitorClass").getName().equals(Helper.class.getName())) {
                continue;
            }

            assertTrue("Wrong event duration", event.getDuration().toMillis() >= MILLIS);
            assertFalse("Should not have timed out.", event.<Boolean> getValue("timedOut").booleanValue());

            if (lastEventThreadName == null) {
                lastEventThreadName = notifThread;
            }
            assertTrue("Not alternating", lastEventThreadName.equals(notifThread));
            if (eventThread.equals(producerName)) {
                prodCount++;
                assertTrue("Wrong notifier", notifThread.equals(consumerName));
            } else if (eventThread.equals(consumerName)) {
                consCount++;
                assertTrue("Wrong notifier", notifThread.equals(producerName));
            }
            lastEventThreadName = eventThread;
        }
        assertFalse("Wrong number of events: " + prodCount + " " + consCount,
                        abs(prodCount - consCount) > 1 || abs(consCount - COUNT) > 1);
    }

    @Test
    public void test() throws Exception {
        Runnable consumer = () -> {
            try {
                helper.consume();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };

        Runnable producer = () -> {
            try {
                helper.produce();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
        Thread tc = new Thread(consumer);
        Thread tp = new Thread(producer);
        producerName = tp.getName();
        consumerName = tc.getName();

        tp.start();
        tc.start();

        tp.join();
        tc.join();
    }

    private class Helper {
        private int count = 0;
        private final int bufferSize = 1;

        public synchronized void produce() throws InterruptedException {
            for (int i = 0; i < COUNT; i++) {
                while (count >= bufferSize) {
                    wait();
                }
                Thread.sleep(MILLIS);
                count++;
                notify();
            }
        }

        public synchronized void consume() throws InterruptedException {
            for (int i = 0; i < COUNT; i++) {
                while (count == 0) {
                    wait();
                }
                Thread.sleep(MILLIS);
                count--;
                notify();
            }
        }
    }
}