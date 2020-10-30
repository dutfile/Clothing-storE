
suite = {
  "mxversion": "6.15.3",
  "name" : "sulong",
  "versionConflictResolution" : "latest",

  "imports" : {
    "suites" : [
      {
        "name" : "truffle",
        "subdir" : True,
        "urls" : [
          {"url" : "https://curio.ssw.jku.at/nexus/content/repositories/snapshots", "kind" : "binary"},
        ]
      },
    ],
  },

  "libraries" : {
    "LLVM_TEST_SUITE" : {
      "packedResource" : True,
      "urls" : [
        "https://lafo.ssw.uni-linz.ac.at/pub/sulong-deps/test-suite-3.2.src.tar.gz",
        "https://llvm.org/releases/3.2/test-suite-3.2.src.tar.gz",
      ],
      "digest" : "sha512:8cc9b4fc97d87a16a5f5b0bd91ebc8a4e7865a50dbfd98f1456f5830fa121860145b6b9aaabf624d9fd5eb5164e2a909e7ccb21375daf54dc24e990db7d716ba",
    },
    "GCC_SOURCE" : {
      "packedResource" : True,
      # original: https://mirrors-usa.go-parts.com/gcc/releases/gcc-5.2.0/gcc-5.2.0.tar.gz
      "urls" : ["https://lafo.ssw.uni-linz.ac.at/pub/sulong-deps/gcc-5.2.0.tar.gz"],
      "digest" : "sha512:d2cf088c08754af0f06cd36cef83544a05bf75c2fa5d9486eec4babece8b32258449f04bcb6506bf3ea6681948574ba56812bc9881497ba0f5460f8358e8fce5",
    },
    "SHOOTOUT_SUITE" : {
      "packedResource" : True,
      "urls" : ["https://lafo.ssw.uni-linz.ac.at/pub/sulong-deps/benchmarksgame-scm-latest.tar.gz"],
      "digest" : "sha512:1a94a02b1633320c2078f6adbe33b31052676fa1c07217f2fb3b3792bfb6a94410812ac6382295a9f4d8828cdb19dd31d1485f264535e01beb0230b79acc7068",
    },
    "NWCC_SUITE" : {
      "packedResource" : True,
      "urls" : ["https://lafo.ssw.uni-linz.ac.at/pub/sulong-deps/nwcc_0.8.3.tar.gz"],
      "digest" : "sha512:f6af50bd18e13070b512bfac6659f49d10d3ad65ea2c4c5ca3f199c8b87540ec145c7dbbe97272f48903ca1c8afaf58c146ec763c851da0b352d5980746f94f6",
    },
    # Support Libraries.
    # Projects depending on these will *not be built* if the 'optional' is 'True' for the given OS/architecture.
    # This is a dummy library for dragonegg support.
    "DRAGONEGG_SUPPORT" : {
      "os_arch" : {
        "linux" : {
          "amd64" : {
            "path": "tests/support.txt",
            "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
          },
          "<others>": {"optional": True},
        },
        "<others>": {"<others>" : {"optional": True}},
      },
    },
    # This is a dummy library for malloc.h support.
    "MALLOC_H_SUPPORT" : {
      "os_arch" : {
        "linux" : {
          "amd64" : {
            "path": "tests/support.txt",
            "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
          },
          "<others>": {"optional": True},
        },
        "<others>": {"<others>" : {"optional": True}},
      },
    },
    # This is a dummy library for alias() support.
    "ALIAS_SUPPORT" : {
      "os_arch" : {
        "linux" : {
          "amd64" : {
            "path": "tests/support.txt",
            "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
          },
          "<others>": {"optional": True},
        },
        "windows": {
          "<others>" : {
            "path": "tests/support.txt",
            "sha1": "9b3f44dd60da58735fce6b7346b4b3ef571b768e",
          },
        },
        "<others>": {"<others>" : {"optional": True}},
      },
    },
    # This is a dummy library for linux amd64 support.
    "LINUX_AMD64_SUPPORT" : {
      "os_arch" : {
        "linux" : {
          "amd64" : {
            "path": "tests/support.txt",
            "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
          },
          "<others>": {"optional": True},
        },
        "<others>" : {"<others>": {"optional": True}},
      },
    },
    # This is a dummy library for amd64 support.
    "AMD64_SUPPORT" : {
      "arch" : {
        "amd64" : {
          "path": "tests/support.txt",
          "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
        },
        "<others>": {"optional": True},
      },
    },
    # This is a dummy library for amd64 support.
    "AARCH64_SUPPORT" : {
      "arch" : {
        "aarch64" : {
          "path": "tests/support.txt",
          "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
        },
        "<others>": {"optional": True},
      },
    },
    # This is a dummy library for marking sulong native mode support.
    "NATIVE_MODE_SUPPORT" : {
      "os" : {
        "windows" : {"optional": True},
        "<others>" : {
          "path": "tests/support.txt",
          "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
        },
      },
    },
    # This is a dummy library for disabling tests that won't compile because of missing GNU make.
    "UNIX_SUPPORT" : {
      "os" : {
        "windows" : {"optional": True},
        "<others>" : {
          "path": "tests/support.txt",
          "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
        },
      },
    },
    # This is a dummy library for projects that are only compiled on windows
    "WINDOWS_SUPPORT" : {
      "os" : {
        "windows" : {
          "path": "tests/support.txt",
          "digest": "sha512:c02b248975b267f4200603ff2ae40b9d0cdefad4a792f386d610f2b14fb4e67e288c235fd11ed596dd8c91a3dae62fdd741bf97b5c01b5f085485f221702f0a1",
        },
        "<others>" : {"optional": True},
      },
    },
  },

  "projects" : {
    "com.oracle.truffle.llvm.docs" : {
      "class" : "DocumentationProject",
      "subDir" : "docs",
      "dir" : "docs",
      "sourceDirs" : ["src"],
      "license" : "BSD-new",
      "defaultBuild" : False,
    },
    "com.oracle.truffle.llvm.tests" : {
      "subDir" : "tests",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm",
        "com.oracle.truffle.llvm.tests.pipe",
        "com.oracle.truffle.llvm.tests.harness",
        "truffle:TRUFFLE_TCK",
        "mx:JUNIT",
      ],
      "requires" : [
        "java.logging",
        "jdk.unsupported", # sun.misc.Unsafe
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "17+",
      "javaProperties" : {
        "test.sulongtest.harness" : "<path:com.oracle.truffle.llvm.tests.harness>/TestHarness/bin",
        "test.sulongtest.lib" : "<path:SULONG_TEST_NATIVE>/<lib:sulongtest>",
        "test.sulongtest.lib.path" : "<path:SULONG_TEST_NATIVE>",
        "sulongtest.projectRoot" : "<path:com.oracle.truffle.llvm>/../",
        "sulongtest.source.GCC_SOURCE" : "<path:GCC_SOURCE>",
        "sulongtest.source.LLVM_TEST_SUITE" : "<path:LLVM_TEST_SUITE>",
        "sulongtest.source.NWCC_SUITE" : "<path:NWCC_SUITE>",
      },
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.tests.harness" : {
      "subDir" : "tests",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm",
        "com.oracle.truffle.llvm.tests.pipe",
        "truffle:TRUFFLE_TCK",
        "mx:JUNIT",
      ],
      "requires" : [
        "java.logging",
        "jdk.unsupported", # sun.misc.Unsafe
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.tests.native" : {
      "class" : "CMakeNinjaProject",
      "toolchain" : "SULONG_BOOTSTRAP_TOOLCHAIN",
      "subDir" : "tests",
      "ninja_targets" : [
        "default",
      ],
      "results" : ["<lib:sulongtest>"],
      "os" : {
        "windows" : {"results" : ["<staticlib:sulongtest>"]},
        "<others>" : {},
      },
      "buildDependencies" : ["SULONG_BOOTSTRAP_TOOLCHAIN"],
      "license" : "BSD-new",
      "testProject" : True,
    },
    "com.oracle.truffle.llvm.tests.types" : {
      "subDir" : "tests",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.runtime",
        "mx:JUNIT",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.tests.tck" : {
      "subDir" : "tests",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.runtime",
        "mx:JUNIT",
        "sdk:POLYGLOT_TCK",
      ],
      "buildDependencies" : [
        "NATIVE_MODE_SUPPORT",
        "SULONG_TCK_NATIVE",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.tests.tck.native" : {
      "subDir" : "tests",
      "native" : True,
      "vpath" : True,
      "defaultBuild" : False,
      "results" : ["bin/"],
      "buildDependencies" : [
        "SULONG_BOOTSTRAP_TOOLCHAIN",
        "SULONG_HOME",
        "NATIVE_MODE_SUPPORT",
      ],
      "buildEnv" : {
        "SULONGTCKTEST" : "<lib:sulongtck>",
        "CLANG" : "<toolchainGetToolPath:native,CC>",
      },
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.toolchain.config" : {
      "description" : "Provide constants from llvm-config",
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.api" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : ["truffle:TRUFFLE_API"],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },
    "com.oracle.truffle.llvm.spi" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : ["truffle:TRUFFLE_API"],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm.nfi" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "truffle:TRUFFLE_API",
        "truffle:TRUFFLE_NFI",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm.nfi.test.native" : {
      "subDir" : "projects",
      "class" : "CopiedNativeProject",
      "srcFrom" : "truffle:com.oracle.truffle.nfi.test.native",
      "toolchain" : "SULONG_BOOTSTRAP_TOOLCHAIN",
      "buildDependencies" : [
        "SULONG_BOOTSTRAP_TOOLCHAIN",
        "truffle:TRUFFLE_NFI_NATIVE",
      ],
      "workingSets" : "Truffle, LLVM",
      "testProject" : True,
      "defaultBuild" : False,
      "jacoco" : "exclude",
      "os_arch" : {
        "windows" : {
          "<others>" : {
            "ldflags" : ["-shared"]
          },
        },
        "<others>" : {"<others>" : {}},
      },
    },

    "com.oracle.truffle.llvm.nfi.test.native.isolation" : {
      "subDir" : "projects",
      "class" : "CopiedNativeProject",
      "srcFrom" : "truffle:com.oracle.truffle.nfi.test.native.isolation",
      "toolchain" : "SULONG_BOOTSTRAP_TOOLCHAIN",
      "buildDependencies" : [
        "SULONG_BOOTSTRAP_TOOLCHAIN",
        "truffle:TRUFFLE_NFI_NATIVE",
      ],
      "workingSets" : "Truffle, LLVM",
      "testProject" : True,
      "defaultBuild" : False,
      "jacoco" : "exclude",
      "os_arch" : {
        "windows" : {
          "<others>" : {
            "ldflags" : ["-shared"]
          },
        },
        "<others>" : {"<others>" : {}},
      },
    },

    "com.oracle.truffle.llvm.nativemode" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "truffle:TRUFFLE_NFI",
        "SULONG_CORE"
      ],
      "requires" : [
        "jdk.unsupported", # sun.misc.Unsafe
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm.runtime" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "truffle:TRUFFLE_API",
        "truffle:TRUFFLE_NFI",
        "com.oracle.truffle.llvm.api",
        "com.oracle.truffle.llvm.spi",
        "com.oracle.truffle.llvm.toolchain.config",
        "truffle:ANTLR4",
      ],
      "requires" : [
        "java.logging",
        "jdk.unsupported", # sun.misc.Signal
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "checkstyleVersion" : "10.7.0",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "javaCompliance" : "17+",
      "spotbugsIgnoresGenerated" : True,
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
      # Using finalizer in signals implementation. GR-7018
      "javac.lint.overrides" : "-deprecation",
    },

    "com.oracle.truffle.llvm.parser" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.runtime",
       ],
      "requires" : [
        "java.logging",
        "java.xml",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.parser",
        "SULONG_API",
       ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "javaProperties" : {
        "llvm.toolchainRoot" : "<nativeToolchainRoot>",
      },
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm.launcher" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "sdk:LAUNCHER_COMMON",
       ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm.tools" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.parser",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "exclude",
    },

    "com.oracle.truffle.llvm.toolchain.launchers" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "sdk:LAUNCHER_COMMON",
      ],
      "javaProperties" : {
        "llvm.bin.dir" : "<path:LLVM_TOOLCHAIN>/bin",
        "org.graalvm.language.llvm.home": "<path:SULONG_HOME>",
      },
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "bootstrap-toolchain-launchers": {
      "subDir": "projects",
      "class" : "BootstrapToolchainLauncherProject",
      "buildDependencies" : [
        "sdk:LLVM_TOOLCHAIN",
        "com.oracle.truffle.llvm.toolchain.launchers",
      ],
      "license" : "BSD-new",
    },

    "bootstrap-toolchain-launchers-no-home": {
      "subDir": "projects",
      "class" : "BootstrapToolchainLauncherProject",
      "buildDependencies" : [
        "sdk:LLVM_TOOLCHAIN",
        "com.oracle.truffle.llvm.toolchain.launchers",
      ],
      "javaProperties" : {
        # we intentionally set llvm home to a non-existent location to avoid picking up outdated files
        "org.graalvm.language.llvm.home" : "<path:SULONG_BOOTSTRAP_TOOLCHAIN_NO_HOME>/nonexistent",
      },
      "license" : "BSD-new",
    },

    "toolchain-launchers-tests": {
      "class" : "CMakeNinjaProject",
      "subDir": "tests",
      "vpath": True,
      "platformDependent": True,
      "ninja_targets" : ["all"],
      "ninja_install_targets" : ["test"],
      "results" : ["main.out"],
      "cmakeConfig" : {
        "SULONG_EXE" : "<mx_exe> lli",
        "CMAKE_C_COMPILER": "<toolchainGetToolPath:native,CC>",
        "CMAKE_CXX_COMPILER": "<toolchainGetToolPath:native,CXX>",
        "SULONG_C_COMPILER": "<toolchainGetToolPath:native,CC>",
        "SULONG_CXX_COMPILER": "<toolchainGetToolPath:native,CXX>",
        "SULONG_LINKER": "<toolchainGetToolPath:native,LD>",
        "SULONG_LIB" : "<path:SULONG_HOME>/native/lib",
        "SULONG_OBJDUMP" : "<path:LLVM_TOOLCHAIN>/bin/<exe:llvm-objdump>",
        "SULONG_NATIVE_BUILD" : "True",
      },
      "buildEnv" : {
        "CTEST_PARALLEL_LEVEL" : "16",
      },
      "buildDependencies" : [
        "SULONG_CORE",
        "SULONG_NATIVE",
        "SULONG_LAUNCHER",
        "SULONG_TOOLCHAIN_LAUNCHERS",
        "SULONG_BOOTSTRAP_TOOLCHAIN",
      ],
      "testProject" : True,
      "defaultBuild" : False,
    },

    "com.oracle.truffle.llvm.asm.amd64" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.runtime",
        "truffle:ANTLR4",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
      # warnings in generated code
      "javac.lint.overrides" : "none",
    },

    "com.oracle.truffle.llvm.parser.factories" : {
      "subDir" : "projects",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.asm.amd64",
        "com.oracle.truffle.llvm.parser",
       ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "annotationProcessors" : ["truffle:TRUFFLE_DSL_PROCESSOR"],
      "workingSets" : "Truffle, LLVM",
      "license" : "BSD-new",
      "jacoco" : "include",
    },

    "com.oracle.truffle.llvm.tools.fuzzing.native" : {
      "subDir" : "projects",
      "native" : True,
      "vpath" : True,
      "headers" : ["src/fuzzmain.c"],
      "results" : [
        "bin/<exe:llvm-reduce>",
        "bin/<exe:llvm-stress>",
      ],
      "buildDependencies" : [
        "sdk:LLVM_TOOLCHAIN_FULL",
        "NATIVE_MODE_SUPPORT",
      ],
      "buildEnv" : {
        "LLVM_CONFIG" : "<path:LLVM_TOOLCHAIN_FULL>/bin/llvm-config",
        "CXX" : "<path:LLVM_TOOLCHAIN_FULL>/bin/clang++",
        "LLVM_REDUCE" :"bin/<exe:llvm-reduce>",
        "LLVM_STRESS" :"bin/<exe:llvm-stress>",
        "LLVM_ORG_SRC" : "<path:LLVM_ORG_SRC>",
        "OS" : "<os>",
      },
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "license" : "BSD-new",
      "testProject" : True,
      "defaultBuild" : False,
    },

    "com.oracle.truffle.llvm.tests.pipe" : {
      "subDir" : "tests",
      "sourceDirs" : ["src"],
      "jniHeaders" : True,
      "javaProperties" : {
        "test.pipe.lib" : "<path:SULONG_TEST_NATIVE>/<lib:pipe>",
      },
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },

    "com.oracle.truffle.llvm.tests.llirtestgen" : {
      "subDir" : "tests",
      "sourceDirs" : ["src"],
      "dependencies" : [
        "com.oracle.truffle.llvm.tests",
      ],
      "checkstyle" : "com.oracle.truffle.llvm.runtime",
      "javaCompliance" : "17+",
      "license" : "BSD-new",
      "testProject" : True,
      "jacoco" : "exclude",
    },
    "com.oracle.truffle.llvm.tests.llirtestgen.generated" : {
      "subDir" : "tests",
      "native" : True,
      "vpath" : True,
      "bundledLLVMOnly" : True,
      "results" : ["gen"],
      "buildDependencies" : [
        "LLIR_TEST_GEN",
        "SULONG_HOME",
        "sdk:LLVM_TOOLCHAIN",
        "SULONG_BOOTSTRAP_TOOLCHAIN",
        "LINUX_AMD64_SUPPORT",
      ],
      "buildEnv": {
        "LLIRTESTGEN_CMD" : "<get_jvm_cmd_line:LLIR_TEST_GEN>",
      },
      "license" : "BSD-new",
      "testProject" : True,
      "defaultBuild" : False,
    },
    "com.oracle.truffle.llvm.tests.llirtestgen.native" : {
      "class": "ExternalCMakeTestSuite",
      "subDir" : "tests",
      "testSourceDir" : "<path:LLIR_TEST_GEN_SOURCES>",
      "native" : True,
      "vpath" : True,
      "bundledLLVMOnly" : True,
      "variants" : ["bitcode-O0"],
      "fileExts" : [".ll"],
      "buildDependencies" : [
        "LLIR_TEST_GEN_SOURCES",
        "LINUX_AMD64_SUPPORT",
      ],
      "cmakeConfig": {
        "CMAKE_C_LINK_FLAGS" : "-lm",
      },
      "license" : "BSD-new",
      "testProject" : True,
      "defaultBuild" : False,
    },

    "com.oracle.truffle.llvm.tests.pipe.native" : {
      "subDir" : "tests",
      "native" : "shared_lib",
      "deliverable" : "pipe",
      "use_jdk_headers" : True,
      "buildDependencies" : [
        "com.oracle.truffle.llvm.tests.pipe",
      ],
      "license" : "BSD-new",
      "testProject" : True,
      "os" : {
        "windows" : {},
        "solaris" : {
          "cflags" : ["-g", "-Wall", "-Werror", "-m64"],
          "ldflags" : ["-m64"],
        },
        "<others>" : {
          "cflags" : ["-g", "-Wall", "-Werror"],
        },
      },
    },
    "com.oracle.truffle.llvm.libraries.bitcode" : {
      "subDir" : "projects",
      "class" : "CMakeNinjaProject",
      "toolchain" : "SULONG_BOOTSTRAP_TOOLCHAIN_NO_HOME",
      # NinjaBuildTask uses only 1 job otherwise
      "max_jobs" : "8",
      "vpath" : True,
      "ninja_targets" : [
        "<lib:sulong>",
        "<lib:sulong++>",
      ],
      "results" : [
        "bin/<lib:sulong>",
        "bin/<lib:sulong++>",
      ],
      "ninja_install_targets" : ["install"],
      "buildDependencies" : [
        "sdk:LLVM_TOOLCHAIN",
        "sdk:LLVM_ORG_SRC",
        "SULONG_BOOTSTRAP_TOOLCHAIN_NO_HOME",
        "SULONG_NATIVE_HOME",
      ],
      "cmakeConfig" : {
        "CMAKE_OSX_DEPLOYMENT_TARGET" : "10.13",
        "GRAALVM_LLVM_INCLUDE_DIR" : "<path:com.oracle.truffle.llvm.libraries.graalvm.llvm>/include",
        "GRAALVM_LLVM_LIBS_INCLUDE_DIR" : "<path:com.oracle.truffle.llvm.libraries.graalvm.llvm.libs>/include",
        "GRAALVM_LLVM_LIB_DIR" : "<path:SULONG_NATIVE_HOME>/native/lib",
        "LIBCXX_ISYSTEM" : "<path:SULONG_NATIVE_HOME>/include/c++/v1",
        "LIBCXX_SRC" : "<path:sdk:LLVM_ORG_SRC>",
        "MX_OS" : "<os>",
        "MX_ARCH" : "<arch>",
      },
      "os_arch" : {
        "windows" : {
          "<others>" : {
            "cmakeConfig" : {
              "GRAALVM_PTHREAD_INCLUDE_DIR" : "<path:com.oracle.truffle.llvm.libraries.pthread>/include",
            },
          },
        },
        "<others>" : {"<others>" : {}},
      },
      "license" : "BSD-new",
    },
    "com.oracle.truffle.llvm.libraries.graalvm.llvm" : {
      "class" : "HeaderProject",
      "subDir" : "projects",
      "native" : True,
      "vpath" : True,
      "results" : [],
      "headers" : [
        "include/graalvm/llvm/handles.h",
        "include/graalvm/llvm/polyglot.h",
        "include/graalvm/llvm/polyglot-buffer.h",
        "include/graalvm/llvm/polyglot-time.h",
        "include/graalvm/llvm/toolchain-api.h",
        "include/graalvm/llvm/internal/handles-impl.h",
        "include/graalvm/llvm/internal/polyglot-impl.h",
        "include/graalvm/llvm/internal/polyglot-time-impl.h",
        # for source compatibility
        "include/polyglot.h",
        "include/llvm/api/toolchain.h",
      ],
      "license" : "BSD-new",
    },
    "com.oracle.truffle.llvm.libraries.graalvm.llvm.libs" : {
      "subDir" : "projects",
      "class" : "CMakeNinjaProject",
      "toolchain" : "SULONG_BOOTSTRAP_TOOLCHAIN_NO_HOME",
      # NinjaBuildTask uses only 1 job otherwise
      "max_jobs" : "8",
      "vpath" : True,
      "ninja_install_targets" : ["install"],
      "ninja_targets" : ["<libv:graalvm-llvm.1>"],
      # We on purpose exclude the symlink from the results because the layout
      # distribution would dereference it and create a copy instead of keeping
      # the symlink. The symlink is added manually in the layout definition of
      # the distribution.
      "results" : ["bin/<libv:graalvm-llvm.1>"],
      "os" : {
        "windows" : {
          "ninja_targets" : ["<staticlib:graalvm-llvm>"],
          "results" : ["bin/<staticlib:graalvm-llvm>"],
        },
        "<others>" : {},
      },
      "buildDependencies" : [
        "SULONG_BOOTSTRAP_TOOLCHAIN_NO_HOME",
        "com.oracle.truffle.llvm.libraries.graalvm.llvm",
      ],
      "cmakeConfig" : {
        "CMAKE_OSX_DEPLOYMENT_TARGET" : "10.13",