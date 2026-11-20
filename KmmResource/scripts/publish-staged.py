"""Publish one staged Gradle Maven release directly to both package repositories."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as XML

CHECKSUMS = (".md5", ".sha1", ".sha256", ".sha512")
SEGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]*")
METADATA = "maven-metadata.xml"


def require(condition, message):
    if not condition:
        raise ValueError(message)


class DownloadRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        source, target = map(urllib.parse.urlsplit, (request.full_url, url))
        if (request.get_method() == "GET" and source.netloc == "maven.pkg.github.com"
                and target.scheme == "https" and target.netloc == "github-registry-files.githubusercontent.com"):
            return urllib.request.Request(url)  # Signed download URL needs no registry credential.
        raise ValueError("Unexpected Maven registry redirect")


class Repository:
    def __init__(self, name, url, username=None, password=None, public_reads=False):
        self.name, self.url, self.public_reads = name, url, public_reads
        self.auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode() if username and password else None
        self.http = urllib.request.build_opener(DownloadRedirect())

    def request(self, key, data=None):
        method = "GET" if data is None else "PUT"
        headers = {"User-Agent": "KmmResource-Publisher"}
        if method == "PUT" or not self.public_reads:
            require(self.auth, f"Missing {self.name} credentials for {method}")
            headers["Authorization"] = self.auth
        if method == "PUT":
            suffix = Path(key).suffix
            types = {".pom": "application/xml", ".xml": "application/xml", ".module": "application/json",
                     ".json": "application/json"}
            headers["Content-Type"] = "text/plain" if suffix in CHECKSUMS else types.get(suffix, "application/octet-stream")
        request = urllib.request.Request(self.url + "/" + urllib.parse.quote(key, safe="/._-+"),
                                         data=data, method=method, headers=headers)
        try:
            with self.http.open(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if method == "GET" and error.code == 404:
                return None
            raise ValueError(f"{self.name} {method} {key}: HTTP {error.code}") from None
        except urllib.error.URLError:
            raise ValueError(f"{self.name} {method} {key}: network failure") from None


def staged_release(directories, version):
    require(SEGMENT.fullmatch(version), "Invalid Maven version")
    files, indexes = {}, {}
    for directory in directories:
        for path in sorted(directory.rglob("*")):
            if not path.is_file():
                continue
            key = path.relative_to(directory).as_posix()
            parts = key.split("/")
            require(all(SEGMENT.fullmatch(part) for part in parts) and not path.is_symlink()
                    and key.startswith("com/tencent/kuiklybase/"), f"Unexpected staged path: {key}")
            suffix = path.suffix if path.suffix in CHECKSUMS else ""
            primary = key[:-len(suffix)] if suffix else key
            if primary.endswith("/" + METADATA):
                if not suffix:
                    indexes[primary] = path
                continue
            require(parts[-2] == version, f"Unexpected staged version: {key}")
            if key in files:
                require(files[key].read_bytes() == path.read_bytes(), f"Build jobs disagree on {key}")
            files[key] = path

    require(files and indexes, "No staged Maven release found")
    # Gradle module metadata keeps logical SNAPSHOT URLs. Maven resolves each
    # classifier and extension to its timestamped file using the version index.
    snapshot_files = {}
    for key, path in indexes.items():
        folder = key.rsplit("/", 1)[0]
        if not folder.endswith("/" + version):
            continue
        doc = XML.fromstring(path.read_bytes())
        artifact = doc.findtext("artifactId")
        for entry in doc.findall("versioning/snapshotVersions/snapshotVersion"):
            value = entry.findtext("value", "")
            ext = entry.findtext("extension", "")
            classifier = entry.findtext("classifier")
            tail = ("-" + classifier if classifier else "") + "." + ext
            actual = f"{folder}/{artifact}-{value}{tail}"
            require(SEGMENT.fullmatch(value) and SEGMENT.fullmatch(ext) and actual in files,
                    f"Missing timestamped file in {key}")
            snapshot_files[f"{folder}/{artifact}-{version}{tail}"] = files[actual]
    for key, path in files.items():
        suffix = path.suffix
        if suffix in CHECKSUMS:
            primary = files.get(key[:-len(suffix)])
            require(primary and path.read_bytes().strip().lower() == checksum(primary.read_bytes(), suffix),
                    f"Staged checksum disagrees: {key}")
        elif suffix == ".module":
            for variant in json.loads(path.read_bytes())["variants"]:
                for item in variant.get("files", []):
                    filename = item["url"]
                    require(SEGMENT.fullmatch(filename), f"Unexpected variant URL in {key}")
                    relative = key.rsplit("/", 1)[0] + "/" + filename
                    source = files.get(relative) or snapshot_files.get(relative)
                    require(source and source.stat().st_size == item["size"]
                            and hashlib.sha256(source.read_bytes()).hexdigest() == item["sha256"],
                            f"Missing or mismatched variant in {key}: {filename}")
        elif suffix == ".pom":
            pom = XML.fromstring(path.read_bytes())
            group, artifact = pom.findtext("{*}groupId"), pom.findtext("{*}artifactId")
            require(group and artifact and pom.findtext("{*}version") == version
                    and key.startswith(f"{group.replace('.', '/')}/{artifact}/{version}/"),
                    f"POM coordinate differs from its path: {key}")
    for prefix in {key.rsplit("/", 1)[0] for key in files}:
        require(any(key.startswith(prefix + "/") and key.endswith(".pom") for key in files),
                f"Missing POM for {prefix}")
        require(prefix.rsplit("/", 1)[0] + "/" + METADATA in indexes, f"Missing Maven index for {prefix}")
    return files, indexes


def checksum(data, suffix):
    return hashlib.new(suffix[1:], data).hexdigest().encode()


def merged_index(key, previous, staged, version):
    fresh = XML.fromstring(staged)
    location = key.rsplit("/", 1)[0]
    group, artifact = fresh.findtext("groupId"), fresh.findtext("artifactId")
    require(group and artifact, f"Missing coordinate in {key}")
    core = group == "com.tencent.kuiklybase" and re.fullmatch(r"resource-(?:core|compose)(?:-[a-z0-9]+)?|resource-generator", artifact)
    marker = group == "com.tencent.kuiklybase.resource.generator" and artifact == group + ".gradle.plugin"
    require(core or marker, f"Unexpected KmmResource coordinate in {key}")
    prefix = group.replace(".", "/") + "/" + artifact
    snapshot = fresh.findtext("version")
    require(location == (f"{prefix}/{version}" if snapshot else prefix), f"Maven index path mismatch: {key}")
    if snapshot:
        require(snapshot == version and version.endswith("-SNAPSHOT"), f"Bad snapshot index: {key}")
    else:
        require(version in [node.text for node in fresh.findall("versioning/versions/version")],
                f"Version missing in staged {key}")
    if previous is None:
        return staged
    old = XML.fromstring(previous)
    require((old.findtext("groupId"), old.findtext("artifactId"), old.findtext("version"))
            == (group, artifact, snapshot), f"Remote index belongs to another coordinate: {key}")
    if snapshot:
        return previous if old.findtext("versioning/lastUpdated", "") > fresh.findtext("versioning/lastUpdated", "") else staged
    versions = old.find("versioning/versions")
    require(versions is not None, f"Remote versions missing: {key}")
    if version in [node.text for node in versions.findall("version")]:
        return previous
    XML.SubElement(versions, "version").text = version
    for name in ("latest", "release", "lastUpdated"):
        value = fresh.findtext("versioning/" + name)
        if value:
            element = old.find("versioning/" + name)
            if element is None:
                element = XML.SubElement(old.find("versioning"), name)
            element.text = value
    return XML.tostring(old, encoding="utf-8", xml_declaration=True)


def matches(key, actual, expected):
    if actual is None:
        return False
    if key.endswith(CHECKSUMS):
        return actual.strip().lower() == expected.strip().lower()
    return actual == expected


def upload_order(key):
    primary = key.rsplit(".", 1)[0] if key.endswith(CHECKSUMS) else key
    return primary.endswith((".module", ".pom")), primary, key


def publish(directories, version, destinations):
    files, indexes = staged_release(directories, version)
    missing = {repo: [] for repo in destinations}
    # Check the complete release at both destinations before uploading any missing file.
    for repo in destinations:
        for key, path in files.items():
            current = repo.request(key)
            if current is None:
                missing[repo].append(key)
            else:
                require(matches(key, current, path.read_bytes()), f"{repo.name} already contains different bytes: {key}")
        for key, path in indexes.items():
            merged_index(key, repo.request(key), path.read_bytes(), version)

    uploaded = {}
    for repo in destinations:
        count = 0
        for key in sorted(missing[repo], key=upload_order):
            data = files[key].read_bytes()
            # Some Maven repositories materialize checksums on the primary PUT.
            if key.endswith(CHECKSUMS):
                current = repo.request(key)
                if current is not None:
                    require(matches(key, current, data), f"{repo.name} generated a different checksum: {key}")
                    continue
            repo.request(key, data)
            count += 1
        uploaded[repo.name] = count
        for key, path in files.items():
            require(matches(key, repo.request(key), path.read_bytes()), f"{repo.name} readback failed: {key}")

    # Discovery follows release bytes. Merge this version with each registry's own history.
    for repo in destinations:
        for key, path in sorted(indexes.items(), key=lambda item: -item[0].count("/")):
            current = repo.request(key)
            body = merged_index(key, current, path.read_bytes(), version)
            if body != current:
                repo.request(key, body)
            require(repo.request(key) == body, f"{repo.name} index readback failed: {key}")
            for suffix in CHECKSUMS:
                if not path.with_name(path.name + suffix).is_file():
                    continue
                companion = key + suffix
                digest = checksum(body, suffix)
                if not matches(companion, repo.request(companion), digest):
                    repo.request(companion, digest)
                    require(matches(companion, repo.request(companion), digest), f"{repo.name} readback failed: {companion}")

    return {"version": version, "uploaded": uploaded,
            "files": {key: {"size": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                      for key, path in sorted(files.items()) if not key.endswith(CHECKSUMS)}}


def build_directories(root):
    directories = sorted(root.iterdir())
    lanes = []
    for directory in directories:
        match = re.fullmatch(r"kmmresource-(linux|ios|metadata)-[1-9][0-9]*", directory.name)
        require(directory.is_dir() and match, f"Unexpected build artifact: {directory.name}")
        lanes.append(match[1])
    require(sorted(lanes) == ["ios", "linux", "metadata"], "Expected one build artifact from each of the three jobs")
    return directories


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("version")
    parser.add_argument("--receipt", required=True, type=Path)
    args = parser.parse_args()
    repository = os.environ["GITHUB_REPOSITORY"]
    require(repository.lower() == "botiverse/kuiklybase-components", "Unexpected package repository")
    require(os.environ.get("RAFT_ARTIFACTS_PUBLISH_TOKEN"), "Missing Raft publishing credentials")
    destinations = [Repository("GitHub Packages", f"https://maven.pkg.github.com/{repository}",
                               os.environ["GITHUB_ACTOR"], os.environ["GITHUB_TOKEN"]),
                    Repository("Raft", "https://maven.artifacts.botiverse.dev", "raft-ci",
                               os.environ["RAFT_ARTIFACTS_PUBLISH_TOKEN"], public_reads=True)]
    receipt = publish(build_directories(args.artifacts), args.version, destinations)
    receipt.update(source=os.environ["GITHUB_SHA"], run=os.environ["GITHUB_RUN_ID"])
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Verified {len(receipt['files'])} Maven files at both destinations for {args.version}.")
    print("Uploaded version files and checksum companions:", receipt["uploaded"])


if __name__ == "__main__":
    main()
