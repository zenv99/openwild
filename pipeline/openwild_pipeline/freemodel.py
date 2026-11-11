"""Free on-phone fingerprint model: DINOv2 ViT-S/14 (Meta, Apache-2.0). No training needed.

Runs entirely on CPU (GitHub Actions). Contract with the app (same as before):
  input  float32 NHWC [1, 224, 224, 3], RGB in [0, 1], centre-cropped square
  output float32 [1, 384], L2-normalised
ImageNet normalisation happens inside the exported graph.
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from . import config

TIMM_NAME = "vit_small_patch14_dinov2.lvd142m"
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


def build():
    import timm
    import torch
    from torch import nn

    backbone = timm.create_model(TIMM_NAME, pretrained=True, num_classes=0, img_size=config.INPUT_SIZE)

    class Embedder(nn.Module):
        def __init__(self):
            super().__init__()
            self.b = backbone
            self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
            self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

        def forward(self, x_nhwc):
            x = (x_nhwc.permute(0, 3, 1, 2) - self.mean) / self.std
            return torch.nn.functional.normalize(self.b(x), dim=-1)

    return Embedder().eval()


def export(out_dir: Path = config.ANDROID_ASSETS) -> Path:
    """PyTorch -> LiteRT (.tflite) with litert-torch, then int8 weight quantisation with ai-edge-quantizer."""
    import litert_torch
    import torch

    model = build()
    sample = (torch.rand(1, config.INPUT_SIZE, config.INPUT_SIZE, 3),)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / "embedder.tflite"
    float_path = config.WORK / "embedder_float.tflite"
    float_path.parent.mkdir(parents=True, exist_ok=True)
    litert_torch.convert(model, sample).export(str(float_path))
    print(f"float model {float_path.stat().st_size / 1e6:.1f} MB")
    try:  # dynamic-range int8 weights: ~4x smaller, near-identical embeddings
        from ai_edge_quantizer import quantizer, recipe

        qt = quantizer.Quantizer(str(float_path))
        qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
        qt.quantize().export_model(str(out), overwrite=True)
    except Exception as e:  # noqa: BLE001
        print(f"int8 quantisation failed ({e!r}); shipping float32")
        out.write_bytes(float_path.read_bytes())
    (out_dir / "embedder.json").write_text(json.dumps(
        {"model_id": config.MODEL_ID, "embedding_dim": config.EMBED_DIM, "input_size": config.INPUT_SIZE,
         "source": f"timm/{TIMM_NAME} (Apache-2.0)"}))
    print(out, f"{out.stat().st_size / 1e6:.1f} MB")
    _parity(model, out)
    return out


def _parity(model, tflite: Path) -> None:
    """PyTorch vs LiteRT cosine similarity on random inputs (should be > 0.98)."""
    import torch

    try:
        from ai_edge_litert.interpreter import Interpreter
    except Exception as e:  # noqa: BLE001
        print(f"parity check skipped: {e!r}")
        return
    it = Interpreter(model_path=str(tflite))
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    sims = []
    for _ in range(4):
        x = np.random.rand(1, config.INPUT_SIZE, config.INPUT_SIZE, 3).astype(np.float32)
        with torch.no_grad():
            a = model(torch.from_numpy(x)).numpy()[0]
        it.set_tensor(i["index"], x)
        it.invoke()
        b = it.get_tensor(o["index"])[0]
        sims.append(float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b))))
    print(f"parity cosine PyTorch vs LiteRT: {np.mean(sims):.4f}")


def preprocess(path: Path) -> np.ndarray:
    """Same as the app: shorter side -> 224, centre crop, RGB in [0,1], NHWC."""
    from PIL import Image

    im = Image.open(path).convert("RGB")
    w, h = im.size
    s = config.INPUT_SIZE / min(w, h)
    im = im.resize((max(config.INPUT_SIZE, round(w * s)), max(config.INPUT_SIZE, round(h * s))), Image.BILINEAR)
    w, h = im.size
    l, t = (w - config.INPUT_SIZE) // 2, (h - config.INPUT_SIZE) // 2
    im = im.crop((l, t, l + config.INPUT_SIZE, t + config.INPUT_SIZE))
    return np.asarray(im, dtype=np.float32) / 255.0


def embed_paths(paths: list[Path], batch: int = 32) -> np.ndarray:
    import torch

    torch.set_num_threads(max(1, torch.get_num_threads()))
    model = build()
    out = np.zeros((len(paths), config.EMBED_DIM), np.float32)
    with torch.inference_mode():
        for i in range(0, len(paths), batch):
            x = torch.from_numpy(np.stack([preprocess(p) for p in paths[i : i + batch]]))
            out[i : i + len(x)] = model(x).numpy()
            if (i // batch) % 20 == 0:
                print(f"embedded {i + len(x)}/{len(paths)}", flush=True)
    return out
