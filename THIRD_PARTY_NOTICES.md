# Third-party notices

This project is licensed under GPL-3.0. The following third-party components
are included in the application or its test assets.

## libarchive

RAR extraction uses libarchive (https://github.com/libarchive/libarchive),
distributed under the BSD 2-Clause license. The RAR readers are
`archive_read_support_format_rar.c` and `archive_read_support_format_rar5.c`.

Copyright (c) 2003-2018 <author(s)>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer
   in this position and unchanged.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## zhanghai libarchive-android

The Android JNI binding is from https://github.com/zhanghai/libarchive-android,
Copyright 2023 Google LLC, licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0).

The AAR in `app/libs/` is repackaged from the official 1.1.6 Java binding and
the 1.1.7 native libraries; see `scripts/repack-libarchive-aar.sh`.

## Test fixtures

The RAR archives under `app/src/androidTest/assets/rar/` come from the
libarchive 3.8.11 test suite (BSD-2-Clause) and are used only as test data.
