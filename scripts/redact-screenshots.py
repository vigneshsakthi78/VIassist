"""Blur client, company, username, and link regions on DMS screenshots."""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOTS = [
    Path(r"C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular\backend\src\main\resources\static\screenshots\dms-tmp-shore"),
    Path(r"C:\Users\vignesh.sakthirajan\Documents\langchain4j-rag-spring-angular\backend\src\main\resources\static\screenshots\dms-shore-v43"),
]
LIVE_TMP = {
    "shot-01.png", "shot-03.png", "shot-04.png", "shot-05.png", "shot-06.png",
    "shot-08.png", "shot-10.png", "shot-13.png", "shot-14.png", "shot-15.png",
    "shot-16.png", "shot-17.png", "shot-18.png", "shot-24.png", "shot-25.png",
    "shot-30.png", "shot-31.png", "shot-32.png", "shot-37.png", "shot-38.png",
}


def blur_box(im: Image.Image, box, radius: int = 18) -> None:
    x1, y1, x2, y2 = [int(v) for v in box]
    x1 = max(0, x1)
    y1 = max(0, y1)
    x2 = min(im.width, x2)
    y2 = min(im.height, y2)
    if x2 - x1 < 4 or y2 - y1 < 4:
        return
    crop = im.crop((x1, y1, x2, y2))
    small = crop.resize((max(4, crop.width // 18), max(4, crop.height // 18)), Image.BILINEAR)
    pixel = small.resize(crop.size, Image.NEAREST)
    pixel = pixel.filter(ImageFilter.GaussianBlur(radius=max(8, radius)))
    im.paste(pixel, (x1, y1))


def orange_boxes(im: Image.Image):
    arr = np.asarray(im.convert("RGB"))
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    mask = (r > 180) & (g > 90) & (g < 190) & (b < 90) & (r > g + 20)
    ys, xs = np.where(mask)
    if len(xs) < 400:
        return []
    x1, x2 = int(xs.min()), int(xs.max())
    y1, y2 = int(ys.min()), int(ys.max())
    # toast is usually a wide short band
    if (x2 - x1) < 180 or (y2 - y1) > 160:
        return []
    return [(x1 - 8, y1 - 6, x2 + 8, y2 + 6)]


def redact_login(im: Image.Image) -> None:
    w, h = im.size
    blur_box(im, (w * 0.50, h * 0.08, w * 0.99, h * 0.42), 24)
    blur_box(im, (w * 0.50, h * 0.68, w * 0.99, h * 0.86), 16)
    blur_box(im, (w * 0.45, h * 0.86, w, h), 16)


def redact_mack_home(im: Image.Image) -> None:
    w, h = im.size
    blur_box(im, (w * 0.62, 0, w, 90), 18)
    blur_box(im, (0, 0, 260, 160), 18)
    blur_box(im, (w * 0.32, 0, w * 0.70, 78), 16)
    blur_box(im, (w * 0.18, 140, w * 0.82, 460), 22)
    for box in orange_boxes(im):
        blur_box(im, box, 18)


def redact_dms(im: Image.Image) -> None:
    w, h = im.size
    blur_box(im, (0, 0, 360, 60), 18)
    blur_box(im, (w - 460, 0, w, 62), 18)
    # company-variable toast sits under the header on the right
    blur_box(im, (w * 0.38, 48, w, 175), 22)
    # growl toast is lower (toolbar / breadcrumb row)
    blur_box(im, (w * 0.42, 230, w, 340), 24)
    for box in orange_boxes(im):
        blur_box(im, box, 18)


def process(path: Path) -> None:
    im = Image.open(path).convert("RGB")
    name = path.name
    if name == "shot-31.png":
        redact_login(im)
    elif name == "shot-04.png":
        redact_mack_home(im)
    else:
        redact_dms(im)
    im.save(path, "PNG")


def main() -> None:
    count = 0
    for root in ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.glob("shot-*.png")):
            if root.name == "dms-shore-v43" and path.name not in LIVE_TMP:
                continue
            process(path)
            count += 1
            print("redacted", path)
    print("done", count)


if __name__ == "__main__":
    main()
