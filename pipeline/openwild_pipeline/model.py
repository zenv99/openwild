"""Teacher (BioCLIP 2) and student (MobileNetV4 + projection) models.

Contract with the Android app (android/.../ml/Embedder.kt):
  input  : float32 NHWC [1, 224, 224, 3], RGB, values in [0, 1], center-cropped square
  output : float32 [1, 768], L2-normalised, in BioCLIP 2's image-embedding space
Normalisation happens inside the exported graph so the app only resizes and scales to [0, 1].
"""
from __future__ import annotations

from . import config

TEACHER_HUB = "hf-hub:imageomics/bioclip-2"
STUDENT_BACKBONE = "mobilenetv4_conv_medium.e500_r256_in1k"

# CLIP / OpenAI normalisation used by BioCLIP 2
CLIP_MEAN = (0.48145466, 0.4578275, 0.40821073)
CLIP_STD = (0.26862954, 0.26130258, 0.27577711)


def load_teacher(device: str = "cuda"):
    import open_clip

    model, _, preprocess = open_clip.create_model_and_transforms(TEACHER_HUB)
    tokenizer = open_clip.get_tokenizer(TEACHER_HUB)
    return model.eval().to(device), preprocess, tokenizer


def build_student(pretrained: bool = True):
    import timm
    import torch
    from torch import nn

    class Student(nn.Module):
        def __init__(self):
            super().__init__()
            self.backbone = timm.create_model(STUDENT_BACKBONE, pretrained=pretrained, num_classes=0)
            self.proj = nn.Sequential(
                nn.Linear(self.backbone.num_features, 1024),
                nn.GELU(),
                nn.Linear(1024, config.EMBED_DIM),
            )

        def forward(self, x):  # x: NCHW, already normalised
            z = self.proj(self.backbone(x))
            return torch.nn.functional.normalize(z, dim=-1)

    return Student()


def export_wrapper(student):
    """Wrap the student so it takes NHWC [0,1] input and normalises internally (for LiteRT export)."""
    import torch
    from torch import nn

    class Exportable(nn.Module):
        def __init__(self, s):
            super().__init__()
            self.s = s
            self.register_buffer("mean", torch.tensor(CLIP_MEAN).view(1, 3, 1, 1))
            self.register_buffer("std", torch.tensor(CLIP_STD).view(1, 3, 1, 1))

        def forward(self, x_nhwc):
            x = x_nhwc.permute(0, 3, 1, 2)
            x = (x - self.mean) / self.std
            return self.s(x)

    return Exportable(student).eval()


def student_transform(train: bool):
    """Student preprocessing for PyTorch-side training/embedding; mirrors the app at eval time."""
    from torchvision import transforms as T

    norm = T.Normalize(CLIP_MEAN, CLIP_STD)
    if train:
        return T.Compose([
            T.RandomResizedCrop(config.INPUT_SIZE, scale=(0.5, 1.0)),
            T.RandomHorizontalFlip(),
            T.ColorJitter(0.3, 0.3, 0.2, 0.02),
            T.ToTensor(),
            norm,
        ])
    return T.Compose([
        T.Resize(config.INPUT_SIZE),         # shorter side -> 224, like the app
        T.CenterCrop(config.INPUT_SIZE),
        T.ToTensor(),
        norm,
    ])
