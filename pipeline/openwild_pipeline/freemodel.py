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
    import ai_edge_torch
    import torch

    model = build()
    sample = (torch.rand(1, config.INPUT_SIZE, config.INPUT_SIZE, 3),)
    edge = None
    try:  # dynamic-range int8 weights: ~4x smaller
        from ai_edge_torch.quantize.pt2e_quantizer import PT2EQuantizer, get_symmetric_quantization_config
        from ai_edge_torch.quantize.quant_config import QuantConfig
        from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e

        q = PT2EQuantizer().set_global(get_symmetric_quantization_config(is_per_channel=True, is_dynamic=True))
        g = torch.export.export_for_training(model, sample).module()
        g = prepare_pt2e(g, q)
        g(*sample)
        g = convert_pt2e(g, fold_quantize=False)
        edge = ai_edge_torch.convert(g, sample, quant_config=QuantConfig(pt2e_quantizer=q))
    except Exception as e:  # noqa: BLE001
        print(f"int8 export failed ({e!r}); exporting float32")
    if edge is None:
        edge = ai_edge_torch.convert(model, sample)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / "embedder.tflite"
    edge.export(str(out))
    (out_dir / "embedder.json").write_text(json.dumps(
        {"model_id": config.MODEL_ID, "embedding_dim": config.EMBED_DIM, "input_size": config.INPUT_SIZE,
         "source": f"timm/{TIMM_NAME} (Apache-2.0)"}))
    print(out, f"{out.stat().st_size / 1e6:.1f} MB")
    return out


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
