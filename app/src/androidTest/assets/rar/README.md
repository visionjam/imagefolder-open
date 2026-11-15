# RAR test fixtures

These archives come from the libarchive 3.8.11 test suite
(`libarchive/test/test_read_format_rar*.rar.uu`), decoded from their
uuencoded form. The libarchive source distribution is BSD-2-Clause;
the fixtures are used here only as test data.

Source: https://github.com/libarchive/libarchive/releases/tag/v3.8.11

They are intentionally kept small. The RAR4 multi-volume fixture is not
included because its first entry decompresses to ~241 MB, which is not
useful for an in-memory Android test.

| File | Purpose |
| --- | --- |
| `rar5_stored.rar` | RAR5 single-volume, stored entry `helloworld.txt` |
| `rar5_solid.rar` | RAR5 solid archive with four 4 KB entries |
| `rar5_multivolume.part01..08.rar` | RAR5 multi-volume archive with two entries |
| `rar4_normal.rar` | RAR4 non-solid archive with files, directories and a symlink |
| `rar5_encrypted_data.rar` | RAR5 with per-file encryption |
| `rar5_encrypted_header.rar` | RAR5 with an encrypted header |
| `rar4_encrypted_header.rar` | RAR4 with an encrypted header |
