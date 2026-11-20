"""Exercise release retry and readback using the actual staged Maven files and two local HTTP repositories."""
import argparse
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import os
from pathlib import Path
import tempfile
import threading
import xml.etree.ElementTree as XML

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-staged.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


@contextmanager
def serve(directory, name, public_reads=False):
    writes, faults = [], {}

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
            data = self.rfile.read(int(self.headers["Content-Length"]))
            if faults.get(key) == "before":
                self.send_error(503)
                return
            path = directory / key
            if not public_reads and key.endswith(".sha1") and path.exists():
                self.send_error(409)
                return
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            writes.append(key)
            if not public_reads and not key.endswith(publisher.CHECKSUMS):
                path.with_name(path.name + ".sha1").write_bytes(publisher.checksum(data, ".sha1"))
            if faults.get(key) == "after":
                self.send_error(503)
                return
            self.send_response(201)
            self.end_headers()

        def log_message(self, *_args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as http:
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        try:
            address = f"http://127.0.0.1:{http.server_port}"
            yield publisher.Repository(name, address, "test", "fixture", public_reads), writes, faults
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
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        for name in ("linux-1", "ios-1"):
            (root / ("kmmresource-" + name)).mkdir()
        expect_failure(lambda: publisher.build_directories(root), "each of the three jobs")
        (root / "kmmresource-metadata-2").mkdir()
        assert set(publisher.build_directories(root)) == set(root.iterdir())
        (root / "kmmresource-linux-2").mkdir()
        expect_failure(lambda: publisher.build_directories(root), "each of the three jobs")
    directories = publisher.build_directories(artifacts) if require_standard else sorted(artifacts.iterdir())
    files, indexes = publisher.staged_release(directories, version)
    primaries = {k: v for k, v in files.items() if not k.endswith(publisher.CHECKSUMS)}
    if require_standard:
        modules = {key.split("/")[-3] for key in primaries if key.endswith(".pom")}
        expected = {"resource-core", "resource-core-android", "resource-core-ohosarm64", "resource-core-iosarm64",
                    "resource-core-iossimulatorarm64", "resource-core-iosx64", "resource-generator",
                    "com.tencent.kuiklybase.resource.generator.gradle.plugin"}
        assert expected <= modules, (expected - modules)
    key = min(primaries, key=publisher.upload_order)
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
            faults[key] = "before"
            expect_failure(run, "HTTP 503")
            assert set(first_writes) == {k for k in files if not k.endswith(".sha1")}
            assert index not in second_writes
            first_writes.clear()
            faults[key] = "after"
            expect_failure(run, "HTTP 503")
            assert not first_writes and second_writes == [key] and (b / key).read_bytes() == files[key].read_bytes()
            faults.clear()
            second_writes.clear()
            receipt = run()
            assert key not in second_writes
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


def check_downloads(version):
    assert publisher.SEGMENT.fullmatch(version)
    github = publisher.Repository("GitHub Packages", "https://maven.pkg.github.com/botiverse/KuiklyBase-components",
                                  os.environ["GITHUB_ACTOR"], os.environ["GITHUB_TOKEN"])
    raft = publisher.Repository("Raft", "https://maven.artifacts.botiverse.dev", public_reads=True)
    prefix = f"com/tencent/kuiklybase/resource-core/{version}/resource-core-{version}"
    for ext in (".pom", ".module"):
        key = prefix + ext
        a, b = github.request(key), raft.request(key)
        if a is None and b is None:
            print(f"{version} has no published {ext} to compare.")
            continue
        assert a is not None and a == b, f"Published files differ at {key}"
        for repo in (github, raft):
            available = []
            for suffix in publisher.CHECKSUMS:
                digest = repo.request(key + suffix)
                if digest is not None:
                    assert publisher.matches(key + suffix, digest, publisher.checksum(a, suffix)), key + suffix
                    available.append(suffix)
            print(f"{repo.name} {ext} verified with its published checksum companions {available}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("staged")
    stage.add_argument("artifacts", type=Path)
    stage.add_argument("version")
    stage.add_argument("--require-standard", action="store_true")
    commands.add_parser("downloads").add_argument("version")
    args = parser.parse_args()
    if args.command == "downloads":
        check_downloads(args.version)
    else:
        test_release(args.artifacts, args.version, args.require_standard)
