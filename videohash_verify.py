import sys
import json
import os

def compute_hashes(file_paths):
    from videohash import VideoHash
    results = []
    for path in file_paths:
        try:
            if not os.path.exists(path):
                results.append({"path": path, "error": "File not found"})
                continue
            vh = VideoHash(path=path)
            results.append({
                "path": path,
                "hash": str(vh),
                "hash_hex": vh.hash_hex
            })
        except Exception as e:
            results.append({"path": path, "error": str(e)})
    return results

def parse_input(raw):
    raw = raw.strip()
    if raw.startswith("["):
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            inner = raw[1:-1]
            return [item.strip().strip('"').strip("'") for item in inner.split(",") if item.strip()]
    return [raw]

def main():
    if len(sys.argv) > 1:
        file_paths = sys.argv[1:]
    else:
        raw = sys.stdin.readline()
        file_paths = parse_input(raw)

    results = compute_hashes(file_paths)
    print(json.dumps(results))

if __name__ == "__main__":
    main()
