#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""光遇公告 App 图标生成器（纯标准库，无 PIL）

设计：
  深蓝夜空渐变 + 星点 + 底部云光
  正中一张米白卷轴（金色卷头）+ 三行文字线 + 金色火漆印章
  右上角一枚四角星（光遇的「星」），带光晕
输出：
  art/icon_1024.png        完整方图（预览/商店用）
  app/res/mipmap-*/        各密度 ic_launcher.png / ic_launcher_round.png
  app/res/mipmap-*/        ic_launcher_fg.png（自适应前景，内容缩到 72% 安全区）
"""
import math, os, struct, zlib

W = 1024
OUT_ART = os.path.join(os.path.dirname(__file__), "..", "art")
RES = os.path.join(os.path.dirname(__file__), "..", "app", "res")

# ---------- 基础工具 ----------
def clamp(v, a=0.0, b=1.0):
    return a if v < a else (b if v > b else v)

def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)

def mix(c1, c2, t):
    return (c1[0] + (c2[0] - c1[0]) * t,
            c1[1] + (c2[1] - c1[1]) * t,
            c1[2] + (c2[2] - c1[2]) * t)

def hsv_rgb(h, s, v):
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    return [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]

def sd_round_rect(px, py, cx, cy, hw, hh, r):
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    ax, ay = max(qx, 0.0), max(qy, 0.0)
    return math.hypot(ax, ay) + min(max(qx, qy), 0.0) - r

def sd_circle(px, py, cx, cy, r):
    return math.hypot(px - cx, py - cy) - r

def cov(sd, aa=1.2):
    """SDF → 覆盖率（边缘 1.2px 抗锯齿）"""
    return clamp(0.5 - sd / aa)

def over(dst, src, a):
    return (dst[0] + (src[0] - dst[0]) * a,
            dst[1] + (src[1] - dst[1]) * a,
            dst[2] + (src[2] - dst[2]) * a)

def hash01(i, j, k=0):
    n = (i * 374761393 + j * 668265263 + k * 2147483647) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFFFF) / 16777216.0

# ---------- 调色板 ----------
BG_TOP = (0.043, 0.098, 0.176)       # #0B192E
BG_BOT = (0.098, 0.208, 0.322)       # #19354F
GLOW = (0.19, 0.34, 0.50)
PAPER = (0.973, 0.949, 0.886)        # #F8F2E2
PAPER_SH = (0.84, 0.80, 0.71)
GOLD = (0.918, 0.769, 0.478)         # #EAC47A
GOLD_D = (0.796, 0.616, 0.318)
INK = (0.106, 0.165, 0.243)          # #1B2A3E
STAR = (1.0, 0.925, 0.72)            # #FFECB8

# ---------- 主绘制 ----------
def shade(x, y, with_bg=True):
    """返回该像素颜色 (r,g,b,a)；with_bg=False 时只画内容，alpha 由各图层覆盖率累加"""
    px, py = x + 0.5, y + 0.5
    st = {"r": 0.0, "g": 0.0, "b": 0.0, "a": 0.0}

    def put(color, ca):
        ca = clamp(ca)
        if ca <= 0:
            return
        na = ca + st["a"] * (1 - ca)
        if na <= 0:
            return
        w0 = st["a"] * (1 - ca) / na
        w1 = ca / na
        st["r"] = st["r"] * w0 + color[0] * w1
        st["g"] = st["g"] * w0 + color[1] * w1
        st["b"] = st["b"] * w0 + color[2] * w1
        st["a"] = na

    # 1) 背景渐变（对角）+ 顶部暖光
    if with_bg:
        t = clamp((px * 0.35 + py * 0.65) / W)
        bg = mix(BG_TOP, BG_BOT, t)
        d = math.hypot((px - W * 0.5) / W, (py - W * 0.30) / W)
        bg = mix(bg, GLOW, 0.42 * (1 - smoothstep(0.0, 0.62, d)))
        d2 = math.hypot((px - W * 0.5) / W * 0.8, (py - W * 1.02) / W * 0.5)
        bg = mix(bg, (0.16, 0.31, 0.44), 0.34 * (1 - smoothstep(0.0, 0.5, d2)))
        n = (hash01(x, y, 7) - 0.5) * 0.018
        put((bg[0] + n, bg[1] + n, bg[2] + n), 1.0)

        # 2) 星点（网格近似）
        cell = 26
        ci, cj = int(px // cell), int(py // cell)
        for di in (-1, 0, 1):
            for dj in (-1, 0, 1):
                hx, hy = ci + di, cj + dj
                if hash01(hx, hy, 11) > 0.55:
                    sx = (hx + hash01(hx, hy, 12)) * cell
                    sy = (hy + hash01(hx, hy, 13)) * cell
                    if sy > W * 0.78:
                        continue
                    dd = math.hypot(px - sx, py - sy)
                    sz = 1.1 + hash01(hx, hy, 14) * 1.9
                    al = (1 - smoothstep(0.0, sz, dd)) * (0.25 + 0.6 * hash01(hx, hy, 15))
                    al *= clamp((W * 0.72 - sy) / (W * 0.5))
                    put((0.88, 0.93, 1.0), al * 0.9)

    scx, scy = W * 0.5, W * 0.585
    shw, shh, sr = W * 0.235, W * 0.166, 30

    # 3) 卷轴投影
    for i in range(7):
        d = sd_round_rect(px, py - 10 - i * 2.2, scx, scy, shw, shh, sr)
        put((0.02, 0.05, 0.09), cov(d, 3.0) * (0.075 - i * 0.009))

    # 4) 纸面
    d = sd_round_rect(px, py, scx, scy, shw, shh, sr)
    ca = cov(d)
    if ca > 0:
        tt = clamp((py - (scy - shh)) / (2 * shh))
        paper = mix(mix(PAPER, PAPER_SH, 0.10), PAPER, 1 - tt)
        put(paper, ca)
        edge = cov(abs(px - scx) - shw + 9, 1.0)
        if edge > 0:
            put((1.0, 0.99, 0.96), edge * 0.25 * ca)

    # 5) 金色卷头
    hcy = scy - shh + 34
    ch = cov(sd_round_rect(px, py, scx, hcy, shw, 30, 14))
    if ch > 0:
        tg = clamp((px - (scx - shw)) / (2 * shw))
        put(mix(GOLD, GOLD_D, tg * 0.55), ch)
        put(GOLD_D, cov(sd_round_rect(px, py, scx, hcy + 34, shw * 0.9, 1.6, 1.0)) * 0.55)

    # 6) 三行“文字”
    for frac, hh, cy in [(0.62, 22, 545), (0.55, 18, 596), (0.44, 18, 638)]:
        dl = sd_round_rect(px, py, scx - shw * (1 - frac) / 2, cy, shw * frac, hh / 2, hh / 2)
        put(INK, cov(dl) * 0.72)

    # 7) 火漆印章
    scx2, scy2 = scx + shw * 0.60, scy + shh * 0.52
    cs = cov(sd_circle(px, py, scx2, scy2, 44))
    if cs > 0:
        tg = clamp((py - (scy2 - 44)) / 88)
        put(mix(GOLD, GOLD_D, tg * 0.6), cs)
        put(GOLD_D, cov(sd_circle(px, py, scx2, scy2, 33)) * 0.85)
        put(mix(GOLD, (1.0, 0.95, 0.8), 0.5), cov(sd_circle(px, py, scx2, scy2, 27)) * 0.9)

    # 8) 四角星 + 光晕
    stx, sty = W * 0.735, W * 0.300
    dd = math.hypot(px - stx, py - sty)
    halo = (1 - smoothstep(0.0, 190, dd)) ** 2 * 0.55
    if halo > 0:
        put(STAR, halo * 0.55)
    u = abs(px - stx) / 92.0
    v = abs(py - sty) / 92.0
    if u < 1.2 and v < 1.2:
        cst = clamp((1.0 - (u ** 0.62 + v ** 0.62)) * 6.0)
        if cst > 0:
            put((1.0, 0.97, 0.86), cst)
            put((1.0, 0.99, 0.95), cst * 0.6)

    # 9) 暗角（只影响背景版）
    if with_bg:
        dv = math.hypot((px - W / 2) / (W / 2), (py - W / 2) / (W / 2))
        vg = 1 - 0.42 * smoothstep(0.55, 1.45, dv)
        st["r"] *= vg; st["g"] *= vg; st["b"] *= vg

    return clamp(st["r"]), clamp(st["g"]), clamp(st["b"]), clamp(st["a"])


def render(size=W, with_bg=True, scale=1.0):
    """渲染 RGBA 字节。scale<1 用于自适应前景（内容缩到安全区）"""
    buf = bytearray(size * size * 4)
    cx = size / 2.0
    for y in range(size):
        for x in range(size):
            if scale != 1.0:
                # 内容坐标映射：围绕中心缩放
                sx = (x - cx) / scale + cx
                sy = (y - cx) / scale + cx
            else:
                sx, sy = x, y
            if 0 <= sx < W and 0 <= sy < W:
                r, g, b, a = shade(int(sx), int(sy), with_bg)
            else:
                r = g = b = 0.0
                a = 0.0
            i = (y * size + x) * 4
            buf[i] = int(r * 255 + 0.5)
            buf[i + 1] = int(g * 255 + 0.5)
            buf[i + 2] = int(b * 255 + 0.5)
            buf[i + 3] = int(a * 255 + 0.5)
    return bytes(buf)


def write_png(path, size, rgba):
    raw = bytearray()
    stride = size * 4
    for y in range(size):
        raw.append(0)
        raw.extend(rgba[y * stride:(y + 1) * stride])
    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    hdr = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', hdr)
           + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
           + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(png)
    return len(png)


def downscale(rgba, src, dst):
    out = bytearray(dst * dst * 4)
    ratio = src / dst
    for y in range(dst):
        y0 = int(y * ratio)
        y1 = max(y0 + 1, int((y + 1) * ratio))
        for x in range(dst):
            x0 = int(x * ratio)
            x1 = max(x0 + 1, int((x + 1) * ratio))
            r = g = b = a = 0
            n = 0
            for yy in range(y0, min(y1, src)):
                base = (yy * src) * 4
                for xx in range(x0, min(x1, src)):
                    i = base + xx * 4
                    r += rgba[i]; g += rgba[i + 1]; b += rgba[i + 2]; a += rgba[i + 3]
                    n += 1
            i = (y * dst + x) * 4
            out[i] = r // n; out[i + 1] = g // n; out[i + 2] = b // n; out[i + 3] = a // n
    return bytes(out)


DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def main():
    print("渲染 1024x1024 主图…")
    full = render(W, True, 1.0)
    n = write_png(os.path.join(OUT_ART, "icon_1024.png"), W, full)
    print(f"  art/icon_1024.png  {n/1024:.0f} KB")

    print("生成各密度 launcher 图标…")
    for dens, k in DENS.items():
        px = int(48 * k)
        small = downscale(full, W, px)
        d = os.path.join(RES, f"mipmap-{dens}")
        write_png(os.path.join(d, "ic_launcher.png"), px, small)
        write_png(os.path.join(d, "ic_launcher_round.png"), px, small)
        print(f"  {dens}: {px}px")

    print("渲染自适应前景（内容 72% 安全区，透明底）…")
    fg = render(1024, False, 0.86)
    for dens, k in DENS.items():
        px = int(108 * k)
        small = downscale(fg, W, px)
        d = os.path.join(RES, f"mipmap-{dens}")
        write_png(os.path.join(d, "ic_launcher_fg.png"), px, small)
    # 合成一张“自适应图标实际效果”预览（圆形遮罩）
    print("合成自适应预览…")
    small_fg = downscale(fg, W, 512)
    prev = bytearray(512 * 512 * 4)
    for y in range(512):
        for x in range(512):
            # 背景：与 ic_launcher_bg 相同的竖向渐变
            t = y / 511.0
            if t < 0.5:
                k = t / 0.5
                bg = mix((0.043, 0.098, 0.176), (0.090, 0.196, 0.310), k)
            else:
                k = (t - 0.5) / 0.5
                bg = mix((0.090, 0.196, 0.310), (0.043, 0.098, 0.176), k)
            r, g, b = bg
            i = (y * 512 + x) * 4
            a = small_fg[i + 3] / 255.0
            if a > 0:
                r = r + (small_fg[i] / 255.0 - r) * a
                g = g + (small_fg[i + 1] / 255.0 - g) * a
                b = b + (small_fg[i + 2] / 255.0 - b) * a
            # 圆形遮罩（launcher 常见形状）
            dd = math.hypot(x - 256, y - 256)
            m = clamp((232 - dd) / 1.5)
            prev[i] = int(r * 255 * m); prev[i + 1] = int(g * 255 * m)
            prev[i + 2] = int(b * 255 * m); prev[i + 3] = int(255 * m)
    write_png(os.path.join(OUT_ART, "preview_adaptive.png"), 512, bytes(prev))
    print("完成")


if __name__ == "__main__":
    main()
