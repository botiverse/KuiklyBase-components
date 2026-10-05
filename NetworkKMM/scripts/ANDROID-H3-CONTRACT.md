# Android H3 runtime contract

`networkkmm-tests.yml` has two mandatory Android jobs, with distinct emulators:

- Ordinary system DNS: original runtime gates plus three separate JUnit methods
  for fallback-allowed H3 enablement, a known H2-only origin, and total connection
  failure. A failure in another method does not skip these methods.
- Controlled DNS: a loopback dnsmasq fixture returns one freshly resolved IPv4
  A record and no AAAA for `cloudflare-quic.com`. The emulator explicitly opts in
  using `-dns-server 127.0.0.1`. The App verifies the resolved address and executes
  through NetworkClient, Android engine, JNI and the committed library, retaining
  the HTTPS hostname, SNI and pinned CA verification. It must get HTTP3+200, then
  HTTP2+200 from the same origin with H3 disabled. Missing fixture/feature/H3 is a
  test failure, never a skip. No proxy, CURLOPT override or library rebuild.

This DNS listener is for a disposable Hosted runner only. It does not edit the
host's resolver or a developer device. dnsmasq uses its upstream resolver for
other names. Private DNS is disabled only on the controlled test emulator;
ordinary DNS runs in the other job. The cleanup step stops the listener.

[Android documents the emulator DNS override](https://developer.android.com/studio/run/emulator-commandline).
[dnsmasq documents host-record and local-domain responses](https://thekelleys.org.uk/dnsmasq/docs/dnsmasq-man.html).

The helper retains installed APK hash, embedded/source library equality,
instrumentation logcat, raw result/exit code and XML. Both jobs retain evidence
on success and failure. `android_h3_contract_only=true` is a targeted dispatch:
it runs only the three negotiation methods and the mandatory positive/isolation
method, skips unrelated jobs, and is NOT full SDK acceptance. Default dispatch
and pull requests execute the original runtime gate and full workflow as well.

## Historical failures remain

The earlier exact runs are not reclassified by this patch:
- 37366064750 / 4ce86f3: original H3 expectedHTTP3/actualHTTP2.
- 37368304800 / 59144ac and 37370430789 / 19e230b: buffered-multi
  timeout/Broken pipe; the latter isolated H3 test also failed.
- 37370795636 / 0f56658: buffered-multi passed once with four async arrivals;
  original and isolated H3 assertions still failed. This does not resolve the
  preceding concurrency failures.

The original wrapper selects CURL_HTTP_VERSION_3, allowing H2/H1.1 fallback.
Changing its ordinary-DNS expectation does not replace the mandatory real App
H3 positive, waive other gates, or authorize SDK publication/mobile consumption.
