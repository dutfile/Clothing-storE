"""A minimal subset of the locale module used at interpreter startup
(imported by the _io module), in order to reduce startup time.

Don't import directly from third-party code; use the `locale` module instead!
"""

import sys
import _locale

if sys.platform.startswith("win"):
    def getpreferredencoding(do_setlocale=True):
        if sys.flags.utf8_mode:
            return 'UTF-8'
        return _locale._getdefaultlocale()[1]
else:
    try:
        _locale.CODESET
    except AttributeError:
        if hasattr(sys, 'getandroidapilevel'):
            # On Android langinfo.h and CODESET are missing, and UTF-8 is
            # always used in mbstowcs() and wcstombs().
            def getpreferredencoding(do_setlocale=True):
                return 'UTF-8'
      