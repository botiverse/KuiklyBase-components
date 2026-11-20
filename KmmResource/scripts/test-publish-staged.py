"""Exercise release retry and readback using the actual staged Maven files and two local HTTP repositories."""
import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
from pathlib import Path
import tempfile
import threading
import xml.etree.ElementTree as XML

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-staged.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


@contextmanager
def serve(directory, name, public_reads=False):
    writes, fail_on = [], set()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            assert bool(self.headers.get("Authorization")) == (not public_reads)
            path = directory / self.path.lstrip("/")
            if not path.is_file():
                self.send_error(404)
                return
            self.send_response(200)
            self.end_headers()
            self.wfile.write(path.read_bytes())

        def do_PUT(self):
            assert self.headers.get("Authorization")
            key = self.path.lstrip("/")
            if key in fail_on:
                self.send_error(503)
                return
            path = directory / key
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.rfile.read(int(self.headers["Content-Length"])))
            writes.append(key)
            self.send_response(201)
            self.end_headers()

        def log_message(self, *_args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as http:
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            address = f"http://127.0.0.1:{http.server_port}"
            yield publisher.Repository(name, address, "test", "fixture", public_reads), writes, fail_on
        finally:
            http.shutdown()
            thread.join()


def expect_failure(operation, message):
    try:
        operation()
    except ValueError as error:
        assert message in str(error), error
    else:
        raise AssertionError(f"Expected {message}")


def test_release(artifacts, version, require_standard):
    directories = sorted(artifacts.iterdir())
    files, indexes = publisher.staged_release(directories, version)
    primaries = {k: v for k, v in files.items() if not k.endswith(publisher.CHECKSUMS)}
    if require_standard:
        modules = {key.split("/")[-3] for key in primaries if key.endswith(".pom")}
        expected = {"resource-core", "resource-core-android", "resource-core-ohosarm64", "resource-core-iosarm64",
                    "resource-core-iossimulatorarm64", "resource-core-iosx64", "resource-generator",
                    "com.tencent.kuiklybase.resource.generator.gradle.plugin"}
        assert expected <= modules, (expected - modules)
    key = next(iter(sorted(primaries)))
    index = next(k for k in indexes if XML.fromstring(indexes[k].read_bytes()).find("version") is None)
    with tempfile.TemporaryDirectory() as temp:
        a, b = Path(temp) / "github", Path(temp) / "raft"
        with serve(a, "github") as (first, first_writes, _), serve(b, "raft", True) as (second, second_writes, faults):
            def run():
                return publisher.publish(directories, version, [first, second])

            # A conflict in the second destination must be discovered before writing the first.
            (b / key).parent.mkdir(parents=True)
            (b / key).write_bytes(b"conflict")
            expect_failure(run, "different bytes")
            assert not first_writes and not second_writes
            (b / key).unlink()

            prior = XML.fromstring(indexes[index].read_bytes())
            prior.find("versioning/versions/version").text = "0.0-test-previous"
            for directory in (a, b):
                (directory / index).parent.mkdir(parents=True, exist_ok=True)
                (directory / index).write_bytes(XML.tostring(prior))
            faults.add(key)
            expect_failure(run, "HTTP 503")
            assert set(first_writes) == set(files)
            assert index not in second_writes
            first_writes.clear()
            faults.clear()
            receipt = run()
            assert receipt["uploaded"]["github"] == 0 and receipt["uploaded"]["raft"] > 0
            assert set(receipt["files"]) == set(primaries)
            for directory in (a, b):
                for relative, source in files.items():
                    assert (directory / relative).read_bytes() == source.read_bytes()
                published = XML.parse(directory / index).getroot()
                assert {node.text for node in published.findall("versioning/versions/version")} >= {version, "0.0-test-previous"}
            first_writes.clear()
            second_writes.clear()
            assert run()["uploaded"] == {"github": 0, "raft": 0}
            assert not first_writes and not second_writes
    print(f"{len(primaries)} staged files verified at two destinations, including partial recovery and conflict refusal.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("version")
    parser.add_argument("--require-standard", action="store_true")
    args = parser.parse_args()
    test_release(args.artifacts, args.version, args.require_standard)
