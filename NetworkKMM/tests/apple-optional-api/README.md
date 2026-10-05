# Apple static optional curl API regression

`networkkmm_curl_facts_compat.h` previously used `dlsym(RTLD_DEFAULT)` on
Apple. A symbol can be statically linked into an app and still be absent from
its dynamic export table. In that configuration the client silently selected
the four-thread blocking fallback instead of the multi engine. Completion and
transfer facts and both response-body protection setters were unavailable too.

Run `NetworkKMM/scripts/test-apple-curl-optional-api.sh` on macOS. It compiles
both C and C++ callers against the production header, links real static
archives with dead stripping and only `main` exported, strips local symbols,
and executes the result. Current wrappers must forward all eight APIs and
arguments; old wrappers must link and return the unavailable defaults. The
link options are read from the production `ios_curl.def`, rather than inventing
an independent test-only linkage contract. That definition allows only the
eight optional weak references to remain unresolved; other missing references
continue to be link errors. There is no broad `-undefined dynamic_lookup`.

The fixture archive groups the required ABI function and optional functions in
one member, matching the production `curl_wrapper.cpp` object. The required
ABI call extracts that member. Compatibility does not depend on publishing
all application symbols or disabling dead stripping.

Negative control: set `NETWORKKMM_COMPAT_INCLUDE_DIR` to a directory containing
the old compat header and matching `curl_wrapper.h`; the current-wrapper case
fails at the multi-availability assertion. This catches the original bug even
though every optional function is present in the archive.

This is a linkage/capability test, not a network throughput test or proof that
a user's initial connection timeout has been fixed. The iOS simulator runtime
suite separately exercises the production Kotlin client and native wrapper.

To additionally execute the committed production wrapper on your own already
booted simulator, set `NETWORKKMM_OPTIONAL_API_SIMULATOR_UDID` explicitly. The
script launches a standalone probe and makes no HTTP requests, installs no app,
and changes no app data. It requires all five capability results to be true.
