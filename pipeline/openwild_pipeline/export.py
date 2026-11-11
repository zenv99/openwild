"""Export the student to LiteRT (.tflite) for the Android app, and check parity with PyTorch."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from . import config


def export_tflite(ckpt: Path, out: Path = config.ANDROID_ASSETS / "embedder.tflite", int8: bool = True) -> Path:
    import ai_edge_torch
    import torch

    from .distill import load_student
    from .model import export_wrapper

    model = export_wrapper(load_student(ckpt, "cpu"))
    sample = (torch.rand(1, config.INPUT_SIZE, config.INPUT_SIZE, 3),)
    edge = None
    if int8:
        # Dynamic-range int8 (PT2E): ~4x smaller; accuracy loss is usually negligible for embeddings.
        # Always run parity_check afterwards. Falls back to float32 if the quantizer API changes.
        try:
            from ai_edge_torch.quantize.pt2e_quantizer import PT2EQuantizer, get_symmetric_quantization_config
            from ai_edge_torch.quantize.quant_config import QuantConfig
            from torch.ao.quantization.quantize_pt2e import convert_pt2e, prepare_pt2e

            quantizer = PT2EQuantizer().set_global(
                get_symmetric_quantization_config(is_per_channel=True, is_dynamic=True)
            )
            graph = torch.export.export_for_training(model, sample).module()
            graph = prepare_pt2e(graph, quantizer)
            graph(*sample)
            graph = convert_pt2e(graph, fold_quantize=False)
            edge = ai_edge_torch.convert(graph, sample, quant_config=QuantConfig(pt2e_quantizer=quantizer))
        except Exception as e:  # noqa: BLE001
            print(f"int8 export failed ({e!r}); exporting float32 instead")
    if edge is None:
        edge = ai_edge_torch.convert(model, sample)
    out.parent.mkdir(parents=True, exist_ok=True)
    edge.export(str(out))
    (out.parent / "embedder.json").write_text(
        json.dumps({"model_id": config.MODEL_ID, "embedding_dim": config.EMBED_DIM, "input_size": config.INPUT_SIZE})
    )
    return out


def parity_check(ckpt: Path, tflite: Path, n: int = 8) -> float:
    """Mean cosine similarity between PyTorch and LiteRT outputs on random inputs (expect > 0.99)."""
    import torch
    from ai_edge_litert.interpreter import Interpreter

    from .distill import load_student
    from .model import export_wrapper

    model = export_wrapper(load_student(ckpt, "cpu"))
    interp = Interpreter(model_path=str(tflite))
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    sims = []
    for _ in range(n):
        x = np.random.rand(1, config.INPUT_SIZE, config.INPUT_SIZE, 3).astype(np.float32)
        with torch.no_grad():
            a = model(torch.from_numpy(x)).numpy()[0]
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        b = interp.get_tensor(outp["index"])[0]
        sims.append(float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b))))
    return float(np.mean(sims))
