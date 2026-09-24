# -*- coding: utf-8 -*-
"""最终版布局原型图（只用于确认布局，不参与构建、不进仓库）。

输出：check/work/mockups/*.png —— .gitignore 已忽略 check/work/。
配色/字号取自 app/src/main/res/values/colors.xml 与各布局。

最终确定的方案要点：
- 首页 = 壁纸库列表；点行进库、点库名就地改名
- 进入库后 = 两列正方形壁纸网格；手势/返回键回上一层
- 库级四项摆进库列表行右侧（C2）：启用/停用开关 + 齿轮
- 抽屉只留全局项；接管情况标题就是 WallPaper（一个总开关 + 桌面/锁屏两行）
- 长按浮出图标、删除一律红垃圾桶图标、无文字；库删除二次确认
"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))
S = 2  # 1dp = 2px
FR = "C:/Windows/Fonts/msyh.ttc"
FB = "C:/Windows/Fonts/msyhbd.ttc"

BRAND = "#1E88E5"
PAGE = "#F5F6F8"
CARD = "#FFFFFF"
TP = "#1A1C1E"
TS = "#5F6368"
DIV = "#E8EAED"
DANGER = "#D93025"
MUTED = "#BDBDBD"
CHIP_BG = "#F1F3F4"
THUMB = "#DDE1E6"
GLYPH = "#8A9099"
DIM = (0xAD, 0xB0, 0xB4)

_fc = {}


def dp(v):
    return int(round(v * S))


def F(size, bold=False):
    k = (size, bold)
    if k not in _fc:
        _fc[k] = ImageFont.truetype(FB if bold else FR, dp(size))
    return _fc[k]


def canvas(w_dp, h_dp, bg=PAGE):
    img = Image.new("RGBA", (dp(w_dp), dp(h_dp)), bg)
    return img, ImageDraw.Draw(img)


def card(d, x, y, w, h, r=16, fill=CARD, outline=DIV):
    d.rounded_rectangle([x, y, x + dp(w), y + dp(h)], radius=dp(r),
                        fill=fill, outline=outline, width=max(1, dp(0.5)))


def txt(d, x, y, s, size=14, color=TP, bold=False, anchor="lm"):
    d.text((x, y), s, font=F(size, bold), fill=color, anchor=anchor)


def w_of(d, s, size=14, bold=False):
    return d.textlength(s, font=F(size, bold))


def switch(d, x, y, on=True, w=52, h=32):
    W, H = dp(w), dp(h)
    d.rounded_rectangle([x, y, x + W, y + H], radius=H // 2,
                        fill=BRAND if on else MUTED)
    m = dp(4)
    r = H // 2 - m
    cx = x + W - H // 2 if on else x + H // 2
    d.ellipse([cx - r, y + m, cx + r, y + m + 2 * r], fill=CARD)


def chip(d, x, y, w, h, text, size=13, color=TP):
    d.rounded_rectangle([x, y, x + dp(w), y + dp(h)], radius=dp(8), fill=CHIP_BG)
    txt(d, x + dp(10), y + dp(h) // 2, text, size=size, color=color)


def arrow_right(d, x, cy, color=MUTED, size=6):
    s = dp(size)
    d.line([x, cy - s, x + s, cy], fill=color, width=dp(1.5))
    d.line([x, cy + s, x + s, cy], fill=color, width=dp(1.5))


def arrow_back(d, x, cy, color=TP, size=7):
    s = dp(size)
    d.line([x, cy, x + s * 2, cy], fill=color, width=dp(2))
    d.line([x, cy, x + s, cy - s], fill=color, width=dp(2))
    d.line([x, cy, x + s, cy + s], fill=color, width=dp(2))


def dashed_rect(d, x, y, w, h, color=DANGER, dash=6, gap=4):
    x2, y2 = x + dp(w), y + dp(h)
    step = dp(dash + gap)
    xx = x
    while xx < x2:
        d.line([xx, y, min(xx + dp(dash), x2), y], fill=color, width=dp(1))
        d.line([xx, y2, min(xx + dp(dash), x2), y2], fill=color, width=dp(1))
        xx += step
    yy = y
    while yy < y2:
        d.line([x, yy, x, min(yy + dp(dash), y2)], fill=color, width=dp(1))
        d.line([x2, yy, x2, min(yy + dp(dash), y2)], fill=color, width=dp(1))
        yy += step


def glyph_photo(d, cx, cy, size, color=GLYPH):
    s = dp(size)
    d.rounded_rectangle([cx - s, cy - s * 0.8, cx + s, cy + s * 0.8],
                        radius=dp(2), outline=color, width=dp(1))
    d.ellipse([cx - s + dp(3), cy - s * 0.8 + dp(3), cx - s + dp(7), cy - s * 0.8 + dp(7)],
              fill=color)
    d.polygon([(cx - s + dp(2), cy + s * 0.8 - dp(2)),
               (cx - dp(2), cy - dp(1)),
               (cx + dp(2), cy + dp(3)),
               (cx + dp(5), cy),
               (cx + s - dp(2), cy + s * 0.8 - dp(2))], fill=color)


def glyph_trash(d, cx, cy, size, color=DANGER):
    s = dp(size)
    d.rectangle([cx - s * 0.75, cy - s * 0.62, cx + s * 0.75, cy - s * 0.34], fill=color)
    d.rectangle([cx - s * 0.26, cy - s * 0.86, cx + s * 0.26, cy - s * 0.6], fill=color)
    d.polygon([(cx - s * 0.6, cy - s * 0.26), (cx + s * 0.6, cy - s * 0.26),
               (cx + s * 0.44, cy + s * 0.88), (cx - s * 0.44, cy + s * 0.88)], fill=color)


def glyph_pencil(d, cx, cy, size, color="#FFFFFF", tip="#BBDEFB"):
    s = dp(size)
    d.polygon([(cx - s * 0.30, cy + s * 0.16), (cx + s * 0.34, cy - s * 0.48),
               (cx + s * 0.62, cy - s * 0.20), (cx - s * 0.02, cy + s * 0.44)], fill=color)
    d.polygon([(cx - s * 0.30, cy + s * 0.16), (cx - s * 0.02, cy + s * 0.44),
               (cx - s * 0.62, cy + s * 0.78)], fill=tip)


def glyph_gear(d, cx, cy, size, color=TS):
    s = dp(size)
    d.ellipse([cx - s * 0.86, cy - s * 0.86, cx + s * 0.86, cy + s * 0.86], fill=color)
    for k in range(4):
        if k % 2 == 0:
            d.rectangle([cx - s * 0.18, cy - s * 1.2, cx + s * 0.18, cy + s * 1.2], fill=color)
        else:
            d.rectangle([cx - s * 1.2, cy - s * 0.18, cx + s * 1.2, cy + s * 0.18], fill=color)
    d.ellipse([cx - s * 0.34, cy - s * 0.34, cx + s * 0.34, cy + s * 0.34], fill=CARD)


def icon_button(d, cx, cy, r, kind):
    R = dp(r)
    d.ellipse([cx - R, cy - R, cx + R, cy + R],
              fill=DANGER if kind == "trash" else BRAND)
    if kind == "trash":
        glyph_trash(d, cx, cy, r * 0.72, color="#FFFFFF")
    else:
        glyph_pencil(d, cx, cy, r * 0.85)


def square_cell(d, x, y, side, dim=False):
    d.rounded_rectangle([x, y, x + side, y + side], radius=dp(12),
                        fill=DIM if dim else THUMB)
    glyph_photo(d, x + side // 2, y + side // 2, int(side / dp(1) / 7),
                color="#767B80" if dim else GLYPH)


def lib_row(d, y, name, count, h=64, longpress=False):
    """首页的库行（C2）：缩略图 + 库名/张数，右侧 启用开关 + 齿轮。"""
    card(d, dp(12), y, 387, h)
    d.rounded_rectangle([dp(24), y + dp(12), dp(24 + 40), y + dp(52)],
                        radius=dp(8), fill=THUMB)
    glyph_photo(d, dp(44), y + dp(32), 9)
    txt(d, dp(78), y + dp(24), name, size=16, bold=True)
    txt(d, dp(78), y + dp(44), "{0} 张壁纸".format(count), size=12, color=TS)
    if longpress:
        icon_button(d, dp(12 + 387 - 34), y + dp(32), 16, "trash")
    else:
        switch(d, dp(309), y + dp(20), on=True, w=44, h=24)
        glyph_gear(d, dp(12 + 387 - 26), y + dp(32), 10, color=TS)


def takeover_block(d, x, y, on, home_val, lock_val, title="WallPaper", w=214, h=100):
    card(d, x, y, w, h, r=14)
    txt(d, x + dp(14), y + dp(26), title, size=14)
    switch(d, x + dp(w - 14 - 52), y + dp(10), on=on)
    for i, (lab, val) in enumerate((("桌面", home_val), ("锁屏", lock_val))):
        ly = y + dp(66 + i * 20)
        txt(d, x + dp(14), ly, lab, size=12, color=TS)
        txt(d, x + dp(52), ly, val, size=12, color=TS if val == "系统" else BRAND)
    return h


# ================================================ 图 1：首页 = 壁纸库列表
def draw_home():
    W, H = 411, 330
    img, d = canvas(W, H)

    bar_y = dp(32)
    for i in range(3):
        d.rounded_rectangle([dp(20), bar_y - dp(7) + i * dp(7), dp(38),
                             bar_y - dp(5) + i * dp(7)], radius=dp(1), fill=TP)
    txt(d, dp(52), bar_y, "WallPaper", size=20, bold=True)
    txt(d, dp(411 - 26), bar_y, "＋", size=22, color=BRAND, anchor="mm")

    for i, (name, n) in enumerate([("默认库", 12), ("风景", 8), ("人物", 5)]):
        lib_row(d, dp(72 + i * 76), name, n)

    dashed_rect(d, dp(76), dp(78), 122, 32)
    txt(d, dp(11), dp(320), "点库名=就地改名　点行内其他位置=进入该库　开关=启用/停用　齿轮=其余三项",
        size=11, color=DANGER)

    img.convert("RGB").save(os.path.join(OUT, "1_home.png"))


# ================================================ 图 2：进入库后 = 两列正方形网格
def draw_wallpapers():
    W, H = 411, 500
    img, d = canvas(W, H)

    bar_y = dp(32)
    arrow_back(d, dp(20), bar_y, color=TP, size=7)
    txt(d, dp(56), bar_y, "默认库", size=20, bold=True)
    txt(d, dp(411 - 26), bar_y, "＋", size=22, color=BRAND, anchor="mm")

    cell = 187
    for i, name in enumerate(["壁纸1", "壁纸2", "壁纸3", "壁纸4"]):
        col, row = i % 2, i // 2
        x = dp(12 + col * (cell + 12))
        y = dp(72 + row * 216)
        square_cell(d, x, y, dp(cell))
        txt(d, x + dp(2), y + dp(cell) + dp(14), name, size=13)

    img.convert("RGB").save(os.path.join(OUT, "2_wallpapers.png"))


# ================================================ 图 3：设置抽屉（只剩全局项）
def draw_drawer():
    W, H = 300, 590
    img, d = canvas(W, H)

    # 接管情况：标题 WallPaper，一个总开关 + 桌面/锁屏状态
    takeover_block(d, dp(12), dp(12), True, "WallPaper", "WallPaper")

    # 自动切换：只留 通知开关 + 两个时间（模式/间隔已移到库级）
    top = 122
    card(d, dp(12), dp(top), 276, 108)
    txt(d, dp(26), dp(top + 24), "自动切换", size=13, color=BRAND, bold=True)
    txt(d, dp(26), dp(top + 66), "到点通知", size=14)
    switch(d, dp(274 - 52), dp(top + 50), on=True)
    txt(d, dp(26), dp(top + 90), "上次 12:30　下次 12:45", size=11, color=TS)

    # 导出与日志
    top = 240
    card(d, dp(12), dp(top), 276, 166)
    txt(d, dp(26), dp(top + 24), "导出与日志", size=13, color=BRAND, bold=True)
    for i, (lab, val) in enumerate([("导出目录", "Documents/wallswitch"),
                                    ("立即导出全部壁纸", ""),
                                    ("切换日志", "最近 12:30")]):
        ry = dp(top + 46 + i * 40)
        txt(d, dp(26), ry + dp(20), lab, size=14)
        if val:
            txt(d, dp(150), ry + dp(20), val, size=11, color=TS)
        else:
            arrow_right(d, dp(274 - 14), ry + dp(20), color=MUTED, size=6)

    # 桌面图标预览
    top = 416
    card(d, dp(12), dp(top), 276, 74)
    txt(d, dp(26), dp(top + 24), "桌面图标预览", size=13, color=BRAND, bold=True)
    txt(d, dp(26), dp(top + 52), "首页截图", size=14)
    txt(d, dp(150), dp(top + 52), "已设置", size=11, color=TS)

    # 引擎与后台
    top = 500
    card(d, dp(12), dp(top), 276, 74)
    txt(d, dp(26), dp(top + 24), "引擎与后台", size=13, color=BRAND, bold=True)
    txt(d, dp(26), dp(top + 52), "电池优化白名单", size=14)
    arrow_right(d, dp(274 - 14), dp(top + 52), color=MUTED, size=6)

    img.convert("RGB").save(os.path.join(OUT, "3_drawer.png"))


# ================================================ 图 4：长按浮出 + 删除确认
def draw_longpress():
    W, H = 411, 470
    img, d = canvas(W, H)

    txt(d, dp(16), dp(22), "壁纸格子长按 → 浮出两个小图标（无文字）", size=13, bold=True, color=BRAND)
    cell = 150
    x, y = dp(16), dp(40)
    square_cell(d, x, y, dp(cell), dim=True)
    icon_button(d, x + dp(cell) - dp(26), y + dp(cell) - dp(26), 15, "trash")
    icon_button(d, x + dp(cell) - dp(68), y + dp(cell) - dp(26), 15, "pencil")
    d.rounded_rectangle([x, y, x + dp(cell), y + dp(cell)], radius=dp(12),
                        outline=DANGER, width=dp(1))
    txt(d, x + dp(cell) + dp(14), y + dp(30), "左：编辑（铅笔）", size=12, color=TS)
    txt(d, x + dp(cell) + dp(14), y + dp(52), "右：删除（红垃圾桶）", size=12, color=TS)
    txt(d, x + dp(cell) + dp(14), y + dp(80), "壁纸删除直接生效，", size=12, color=TS)
    txt(d, x + dp(cell) + dp(14), y + dp(98), "不再二次确认", size=12, color=TS)
    txt(d, x + dp(cell) + dp(14), y + dp(124), "点空白处或返回手势收起", size=11, color=DANGER)

    txt(d, dp(16), dp(212), "壁纸库行长按 → 右侧一个红垃圾桶（无文字）", size=13, bold=True, color=BRAND)
    lib_row(d, dp(230), "默认库", 12, longpress=True)

    txt(d, dp(16), dp(324), "壁纸库删除 → 二次确认（确认按钮同样是图标）", size=13, bold=True, color=BRAND)
    y = dp(342)
    card(d, dp(12), y, 387, 84, r=20)
    txt(d, dp(30), y + dp(22), "删除壁纸库「默认库」？", size=15, bold=True)
    txt(d, dp(30), y + dp(44), "库里的 12 张壁纸会一并删除，无法恢复", size=12, color=TS)
    icon_button(d, dp(12 + 387 - 30), y + dp(68), 13, "trash")
    txt(d, dp(12 + 387 - 56), y + dp(68), "取消", size=13, color=BRAND, anchor="rm")

    img.convert("RGB").save(os.path.join(OUT, "4_longpress.png"))


# ================================================ 图 5：接管情况的状态（规格说明）
def draw_takeover_states():
    W, H = 411, 400
    img, d = canvas(W, H)
    txt(d, dp(16), dp(22), "接管情况：一个总开关，桌面/锁屏状态各自显示",
        size=13, bold=True, color=BRAND)

    cases = [
        (False, "系统", "系统", "总开关关", "两个范围都回退系统"),
        (True, "WallPaper", "WallPaper", "开了开关 + 有库",
         "只设了桌面库时锁屏被引擎顺带接管；"),
        (True, "系统", "系统", "开了开关但没有任何启用库", "两边都还是系统"),
    ]
    y = 44
    for on, hv, lv, t, note in cases:
        takeover_block(d, dp(12), dp(y), on, hv, lv)
        txt(d, dp(240), dp(y + 30), t, size=12, color=TP, bold=True)
        txt(d, dp(240), dp(y + 52), note, size=11, color=TS)
        if t == "开了开关 + 有库":
            txt(d, dp(240), dp(y + 70), "另外设了锁屏库时，界面同样显示", size=11, color=TS)
            txt(d, dp(240), dp(y + 88), "WallPaper（按你的要求不加说明文字）", size=11, color=TS)
        y += 118

    img.convert("RGB").save(os.path.join(OUT, "5_takeover_states.png"))


# 先清掉之前所有草稿图（含历史变体），只留最终这 5 张
for f in sorted(os.listdir(OUT)):
    if f.endswith(".png"):
        os.remove(os.path.join(OUT, f))

draw_home()
draw_wallpapers()
draw_drawer()
draw_longpress()
draw_takeover_states()
print("ok:", OUT)
for f in sorted(os.listdir(OUT)):
    if f.endswith(".png"):
        im = Image.open(os.path.join(OUT, f))
        print("  {0}  {1}x{2}  {3}B".format(f, im.width, im.height,
                                           os.path.getsize(os.path.join(OUT, f))))
