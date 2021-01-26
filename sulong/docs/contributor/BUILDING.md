# Building Sulong from Source

Sulong is implemented mostly in Java, with some C/C++ code. It is part of GraalVM.

## Build Dependencies

GraalVM is built using the [mx](https://github.com/graalvm/mx) build tool.
For running mx, a Python runtime is required.

The C/C++ code is built using the LLVM toolchain. GraalVM comes with a bundled LLVM
toolchain that will also be used to build Sulong (see [Toolchain](TOOLCHAIN.md)).

In addition, system tools such as a linker, `make` and `cmake` as well
as system headers are needed.

### Linux

On a Linux-based operating system you can usually use the package
manager to install these requirements. For example, on Debian based system,
installing the `build-essential` and the `cmake` package s