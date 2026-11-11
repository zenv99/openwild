"""Guards against a broken CLI module (every workflow runs through it)."""
import subprocess
import sys


def test_cli_lists_commands():
    out = subprocess.run([sys.executable, "-m", "openwild_pipeline.cli", "--help"], capture_output=True, text=True)
    assert out.returncode == 0, out.stderr
    for cmd in ("photos", "photo-pack", "pack-index", "kew", "buckets", "wikidata", "summary"):
        assert cmd in out.stdout


def test_cli_module_is_sane():
    from pathlib import Path

    src = Path(__file__).resolve().parents[1] / "openwild_pipeline" / "cli.py"
    assert src.stat().st_size < 200_000
