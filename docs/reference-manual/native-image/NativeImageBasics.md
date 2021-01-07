---
layout: ni-docs
toc_group: native-image
link_title: Native Image Basics
permalink: /reference-manual/native-image/basics/
---

# Native Image Basics

Native Image is written in Java and takes Java bytecode as input to produce a standalone binary (an **executable**, or a **shared library**).
During the process of producing a binary, Native Image can run user code.
Finally, Native Image links compiled user code, parts of the Java runtime (for example, the garbage collector, threading support), and the results of code execution into the binary.

We refer to this binary as a **native executable**, or a **native image**.
We refer to the utility that produces the binary as the **`native-image` builder**, or the **`native-image` generator**.

To clearly distinguish between code executed during the native image build, and code executed during the native image execution, we refer to the difference between the two as [**build time** and **run time**](#build-time-vs-run-time).

To produce a minimal image, Native Image employs a process called [**static analysis**](#static-analysis).

### Table of Contents

* [Build Time vs Run Time](#build-time-vs-run-time)
* [Native Image Heap](#native-image-heap)
* [Static Analysis](#static-analysis)

## Build Time vs Run Time

Durin