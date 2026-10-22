#!/usr/bin/env python3
"""Пиксельный спрайт боевого беса с огнём в руке.

Рисует 4 кадра (мерцание пламени, взмах крыла, шаг на месте)
и сохраняет лист, отдельный кадр и GIF с прозрачным фоном.
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "sprites"

# Холст одного кадра. Бес стоит чуть левее центра, огонь — справа сверху.
W, H = 80, 96
OX, OY = 8, 14
FRAMES = 4
SCALE = 6

OUTLINE = (28, 10, 14, 255)

SKIN = (186, 52, 46, 255)
SKIN_D = (122, 28, 32, 255)
SKIN_L = (222, 96, 78, 255)
SKIN_H = (246, 168, 140, 255)
BELLY = (214, 120, 108, 255)

ARMOR = (62, 52, 68, 255)
ARMOR_D = (36, 28, 40, 255)
ARMOR_L = (132, 120, 138, 255)
GOLD = (236, 184, 62, 255)
GOLD_D = (168, 112, 32, 255)
CLOTH = (92, 24, 32, 255)

HORN = (236, 216, 176, 255)
HORN_D = (176, 140, 92, 255)
HORN_L = (255, 244, 214, 255)

WING = (118, 36, 58, 255)
WING_D = (62, 16, 34, 255)
WING_L = (168, 64, 86, 255)
BONE = (74, 22, 36, 255)

EYE = (255, 214, 48, 255)
PUPIL = (42, 12, 14, 255)
MOUTH = (72, 14, 22, 255)
FANG = (255, 246, 228, 255)

HOOF = (42, 24, 28, 255)
CLAW = (255, 236, 210, 255)

FIRE_D = (168, 28, 8, 255)
FIRE_R = (236, 54, 16, 255)
FIRE_O = (255, 132, 24, 255)
FIRE_Y = (255, 208, 48, 255)
FIRE_W = (255, 250, 214, 255)

EMBER = (255, 176, 48, 255)


def new_canvas():
    return Image.new("RGBA", (W, H), (0, 0, 0, 0))


def add_outline(im, color=OUTLINE):
    src = im.copy()
    sp = src.load()
    dp = im.load()
    for y in range(H):
        for x in range(W):
            if sp[x, y][3] != 0:
                continue
            hit = False
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < W and 0 <= ny < H and sp[nx, ny][3] != 0:
                    hit = True
                    break
            if hit:
                dp[x, y] = color


def pix(draw, x, y, color):
    draw.point((int(x + OX), int(y + OY)), fill=color)


def ellipse(draw, cx, cy, rx, ry, color):
    draw.ellipse((cx + OX - rx, cy + OY - ry, cx + OX + rx, cy + OY + ry), fill=color)


def poly(draw, pts, color):
    draw.polygon([(x + OX, y + OY) for x, y in pts], fill=color)


def line(draw, a, b, color, width=1):
    draw.line([(a[0] + OX, a[1] + OY), (b[0] + OX, b[1] + OY)], fill=color, width=width)


def flame(draw, cx, cy, frame):
    """Огонь в поднятой руке. Четыре разных языка пламени."""
    shapes = [
        # высокий язык
        [
            (cx, cy - 16),
            (cx + 7, cy - 6),
            (cx + 5, cy + 2),
            (cx + 8, cy + 6),
            (cx + 2, cy + 8),
            (cx - 6, cy + 5),
            (cx - 7, cy - 2),
            (cx - 3, cy - 8),
        ],
        # крен вправо, шире
        [
            (cx + 2, cy - 14),
            (cx + 9, cy - 4),
            (cx + 8, cy + 4),
            (cx + 4, cy + 8),
            (cx - 4, cy + 6),
            (cx - 8, cy + 1),
            (cx - 5, cy - 7),
            (cx - 1, cy - 11),
        ],
        # раздвоенный
        [
            (cx - 3, cy - 15),
            (cx + 4, cy - 13),
            (cx + 2, cy - 6),
            (cx + 8, cy - 2),
            (cx + 5, cy + 7),
            (cx - 1, cy + 4),
            (cx - 7, cy + 6),
            (cx - 8, cy - 3),
            (cx - 4, cy - 8),
        ],
        # плотный всплеск
        [
            (cx + 1, cy - 12),
            (cx + 8, cy - 5),
            (cx + 6, cy + 5),
            (cx + 1, cy + 8),
            (cx - 6, cy + 4),
            (cx - 8, cy - 3),
            (cx - 4, cy - 9),
        ],
    ]
    outer = shapes[frame % 4]
    poly(draw, outer, FIRE_D)
    # средний слой — чуть меньше к центру
    mid = []
    for x, y in outer:
        mid.append((cx + (x - cx) * 0.62, cy + 1 + (y - cy) * 0.62))
    poly(draw, mid, FIRE_R)
    inner = []
    for x, y in outer:
        inner.append((cx + (x - cx) * 0.34, cy + 2 + (y - cy) * 0.38))
    poly(draw, inner, FIRE_Y)
    ellipse(draw, cx, cy + 2, 2, 3, FIRE_W)

    embers = [
        [(cx + 9, cy - 10), (cx - 9, cy - 4), (cx + 4, cy - 18), (cx + 11, cy + 1)],
        [(cx + 12, cy - 8), (cx - 10, cy + 2), (cx - 2, cy - 16), (cx + 6, cy - 14)],
        [(cx - 11, cy - 8), (cx + 10, cy - 12), (cx + 8, cy + 6), (cx - 4, cy - 18)],
        [(cx + 11, cy - 6), (cx - 8, cy - 12), (cx + 2, cy - 16), (cx - 12, cy + 1)],
    ]
    for ex, ey in embers[frame % 4]:
        pix(draw, ex, ey, EMBER)
        pix(draw, ex, ey + 1, FIRE_O)


def draw_bes(frame):
    im = new_canvas()
    d = ImageDraw.Draw(im)
    bob = 1 if frame in (1, 3) else 0
    flap = -2 if frame % 2 == 0 else 2

    # --- тень ---
    ellipse(d, 32, 76 + bob, 12, 2, (0, 0, 0, 64))

    # --- дальнее крыло ---
    poly(
        d,
        [
            (38, 40 + bob),
            (56, 30 + bob + flap),
            (58, 38 + bob),
            (50, 40 + bob),
            (57, 48 + bob - flap),
            (46, 50 + bob),
            (40, 44 + bob),
        ],
        WING_D,
    )
    line(d, (40, 40 + bob), (56, 32 + bob + flap), BONE, 1)
    line(d, (40, 42 + bob), (56, 47 + bob - flap), BONE, 1)

    # --- хвост: из-за бедра влево, отдельно от когтей, лопата на конце ---
    poly(
        d,
        [
            (18, 60 + bob),
            (10, 64 + bob),
            (6, 70 + bob),
            (11, 71 + bob),
            (16, 66 + bob),
            (21, 62 + bob),
        ],
        SKIN_D,
    )
    poly(
        d,
        [
            (12, 66 + bob),
            (8, 68 + bob),
            (10, 70 + bob),
            (16, 67 + bob),
        ],
        SKIN,
    )
    poly(
        d,
        [
            (6, 69 + bob),
            (0, 64 + bob),
            (1, 72 + bob),
            (7, 76 + bob),
            (13, 70 + bob),
        ],
        SKIN,
    )
    poly(d, [(3, 67 + bob), (2, 71 + bob), (6, 73 + bob), (7, 68 + bob)], SKIN_L)

    # --- ближнее крыло ---
    poly(
        d,
        [
            (24, 38 + bob),
            (8, 18 + bob + flap),
            (3, 28 + bob + flap),
            (12, 30 + bob),
            (4, 42 + bob + flap),
            (14, 38 + bob),
            (7, 54 + bob),
            (18, 52 + bob),
            (24, 44 + bob),
        ],
        WING,
    )
    poly(
        d,
        [
            (22, 38 + bob),
            (10, 24 + bob + flap),
            (8, 30 + bob),
            (14, 32 + bob),
            (10, 40 + bob),
            (16, 40 + bob),
            (14, 50 + bob),
            (20, 46 + bob),
        ],
        WING_L,
    )
    line(d, (24, 38 + bob), (8, 20 + bob + flap), BONE, 1)
    line(d, (22, 40 + bob), (5, 42 + bob + flap), BONE, 1)
    line(d, (22, 42 + bob), (8, 53 + bob), BONE, 1)

    # --- дальняя нога ---
    poly(
        d,
        [(35, 58 + bob), (41, 60 + bob), (42, 68 + bob), (35, 68 + bob), (33, 61 + bob)],
        SKIN_D,
    )
    poly(d, [(34, 67 + bob), (44, 68 + bob), (43, 73 + bob), (33, 72 + bob)], HOOF)
    pix(d, 37, 69 + bob, (90, 70, 74, 255))

    # --- торс ---
    ellipse(d, 30, 50 + bob, 11, 12, SKIN_D)
    ellipse(d, 29, 49 + bob, 10, 11, SKIN)
    # живот
    ellipse(d, 29, 53 + bob, 6, 6, BELLY)
    ellipse(d, 27, 51 + bob, 3, 3, SKIN_H)

    # кираса
    poly(
        d,
        [
            (20, 42 + bob),
            (38, 43 + bob),
            (40, 54 + bob),
            (30, 58 + bob),
            (19, 53 + bob),
        ],
        ARMOR,
    )
    poly(
        d,
        [
            (22, 43 + bob),
            (34, 44 + bob),
            (33, 49 + bob),
            (23, 48 + bob),
        ],
        ARMOR_L,
    )
    poly(
        d,
        [
            (24, 50 + bob),
            (36, 51 + bob),
            (34, 55 + bob),
            (26, 55 + bob),
        ],
        ARMOR_D,
    )
    # золотая пряжка и заклёпки
    ellipse(d, 29, 52 + bob, 2, 2, GOLD)
    pix(d, 29, 52 + bob, GOLD_D)
    pix(d, 23, 46 + bob, GOLD)
    pix(d, 35, 47 + bob, GOLD)

    # ремень
    poly(d, [(19, 56 + bob), (40, 57 + bob), (40, 60 + bob), (19, 59 + bob)], CLOTH)
    ellipse(d, 29, 58 + bob, 2, 2, GOLD_D)

    # --- ближняя нога ---
    poly(
        d,
        [(21, 58 + bob), (29, 59 + bob), (28, 69 + bob), (20, 69 + bob), (19, 61 + bob)],
        SKIN,
    )
    poly(d, [(21, 62 + bob), (25, 63 + bob), (24, 68 + bob), (20, 67 + bob)], SKIN_L)
    poly(d, [(18, 68 + bob), (30, 69 + bob), (29, 74 + bob), (17, 73 + bob)], HOOF)
    pix(d, 22, 70 + bob, (90, 70, 74, 255))

    # --- голова ---
    ellipse(d, 30, 30 + bob, 12, 11, SKIN_D)
    ellipse(d, 29, 29 + bob, 11, 10, SKIN)
    # щека, подсвеченная огнём
    ellipse(d, 35, 32 + bob, 4, 3, SKIN_L)
    ellipse(d, 24, 28 + bob, 3, 2, SKIN_H)

    # уши
    poly(d, [(18, 30 + bob), (10, 26 + bob), (12, 34 + bob), (18, 34 + bob)], SKIN_D)
    poly(d, [(17, 30 + bob), (12, 28 + bob), (13, 33 + bob), (17, 33 + bob)], SKIN_L)
    poly(d, [(40, 30 + bob), (48, 24 + bob), (46, 34 + bob), (40, 35 + bob)], SKIN)
    poly(d, [(41, 30 + bob), (46, 26 + bob), (45, 32 + bob), (41, 33 + bob)], SKIN_L)

    # рога
    poly(
        d,
        [(22, 24 + bob), (14, 14 + bob), (12, 6), (17, 5), (18, 14 + bob), (24, 23 + bob)],
        HORN_D,
    )
    poly(
        d,
        [(22, 23 + bob), (16, 14 + bob), (15, 8), (18, 8), (20, 16 + bob), (24, 22 + bob)],
        HORN,
    )
    line(d, (18, 10), (20, 18 + bob), HORN_L, 1)
    poly(
        d,
        [(36, 24 + bob), (44, 16 + bob), (48, 8), (44, 7), (42, 16 + bob), (34, 24 + bob)],
        HORN_D,
    )
    poly(
        d,
        [(36, 23 + bob), (43, 16 + bob), (45, 10), (43, 11), (40, 18 + bob), (35, 23 + bob)],
        HORN,
    )

    # брови — злые, сведены к переносице
    poly(d, [(20, 26 + bob), (28, 29 + bob), (28, 31 + bob), (20, 29 + bob)], SKIN_D)
    poly(d, [(39, 27 + bob), (31, 29 + bob), (31, 31 + bob), (39, 29 + bob)], SKIN_D)

    # глаза
    ellipse(d, 24, 32 + bob, 3, 2, EYE)
    ellipse(d, 35, 32 + bob, 3, 2, EYE)
    pix(d, 25, 32 + bob, PUPIL)
    pix(d, 26, 33 + bob, PUPIL)
    pix(d, 36, 32 + bob, PUPIL)
    pix(d, 37, 33 + bob, PUPIL)
    pix(d, 23, 31 + bob, FIRE_W)

    # нос и ухмылка с клыком
    pix(d, 30, 35 + bob, SKIN_D)
    pix(d, 29, 36 + bob, SKIN_D)
    poly(
        d,
        [(23, 37 + bob), (36, 37 + bob), (34, 41 + bob), (25, 41 + bob)],
        MOUTH,
    )
    poly(d, [(24, 37 + bob), (27, 37 + bob), (26, 41 + bob)], FANG)
    poly(d, [(33, 37 + bob), (36, 38 + bob), (34, 42 + bob)], FANG)

    # --- рука с когтями: боевой замах влево, выше хвоста ---
    poly(
        d,
        [(20, 46 + bob), (10, 47 + bob), (8, 53 + bob), (16, 55 + bob), (22, 50 + bob)],
        SKIN,
    )
    ellipse(d, 10, 52 + bob, 4, 3, SKIN_L)
    poly(d, [(7, 49 + bob), (1, 46 + bob), (6, 52 + bob)], CLAW)
    poly(d, [(6, 52 + bob), (0, 53 + bob), (6, 55 + bob)], CLAW)
    poly(d, [(8, 55 + bob), (3, 59 + bob), (9, 56 + bob)], CLAW)

    # наплечник боевой
    ellipse(d, 40, 42 + bob, 6, 4, ARMOR_D)
    ellipse(d, 40, 41 + bob, 5, 3, ARMOR)
    ellipse(d, 38, 40 + bob, 2, 1, ARMOR_L)
    pix(d, 43, 41 + bob, GOLD)
    poly(d, [(42, 39 + bob), (45, 33 + bob), (47, 40 + bob)], ARMOR_L)

    # --- рука с огнём ---
    poly(
        d,
        [(40, 44 + bob), (50, 36 + bob), (54, 28 + bob), (58, 28 + bob), (52, 38 + bob), (44, 48 + bob)],
        SKIN,
    )
    # наруч
    poly(d, [(48, 34 + bob), (56, 30 + bob), (57, 34 + bob), (50, 38 + bob)], ARMOR)
    line(d, (49, 35 + bob), (55, 32 + bob), GOLD, 1)
    # кисть
    ellipse(d, 56, 27 + bob, 4, 3, SKIN_L)
    # когти над пламенем — сжимают огонь
    poly(d, [(53, 25 + bob), (52, 21 + bob), (54, 24 + bob)], CLAW)
    poly(d, [(56, 24 + bob), (56, 20 + bob), (58, 24 + bob)], CLAW)
    poly(d, [(59, 26 + bob), (61, 22 + bob), (60, 26 + bob)], CLAW)

    # огонь поверх кисти, но когти остаются у основания
    flame(d, 55, 16 + bob, frame)

    # тёплый отблеск огня на кисти и щеке
    pix(d, 57, 26 + bob, (255, 190, 120, 255))
    pix(d, 55, 25 + bob, (255, 210, 150, 255))
    pix(d, 37, 33 + bob, (255, 170, 110, 255))

    add_outline(im)
    return im


def scale_nn(im, factor):
    return im.resize((im.width * factor, im.height * factor), Image.Resampling.NEAREST)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    frames = [draw_bes(i) for i in range(FRAMES)]

    sheet = Image.new("RGBA", (W * FRAMES, H), (0, 0, 0, 0))
    for i, frame in enumerate(frames):
        sheet.paste(frame, (i * W, 0))

    native = OUT / "bes_combat_sheet.png"
    sheet.save(native)

    # Крупный лист и отдельный кадр — тем же пиксельным масштабом, без сглаживания.
    scale_nn(sheet, SCALE).save(OUT / "bes_combat_sheet_x6.png")
    scale_nn(frames[0], SCALE).save(OUT / "bes_combat.png")
    frames[0].save(OUT / "bes_combat_1x.png")

    gif_frames = []
    for frame in frames:
        big = scale_nn(frame, SCALE)
        gif_frames.append(big.convert("RGBA"))
    gif_path = OUT / "bes_combat.gif"
    gif_frames[0].save(
        gif_path,
        save_all=True,
        append_images=gif_frames[1:],
        duration=140,
        loop=0,
        disposal=2,
        optimize=False,
    )
    print("wrote", native)
    print("frame", frames[0].size, "sheet", sheet.size)


if __name__ == "__main__":
    main()
