#!/usr/bin/env python3
"""
iPad 13" App Store Screenshot & Preview Generator for MediHub Patient
Generates:
  - 10 screenshots at 2048x2732 (iPad 12.9" / 13")
  - 3 app preview poster frames at 2048x2732
"""

import os, math
from PIL import Image, ImageDraw, ImageFont

BASE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(BASE, "screenshots", "ipad-13")
OUT_PREV = os.path.join(BASE, "previews", "ipad-13")
for d in [OUT_DIR, OUT_PREV]:
    os.makedirs(d, exist_ok=True)

W, H = 2048, 2732

# ── Brand colours ────────────────────────────────────────────────────────
BRAND_BLUE     = (0, 133, 202)
BRAND_DARK     = (0, 71, 133)
BRAND_LIGHT    = (220, 240, 250)
WHITE          = (255, 255, 255)
NEAR_WHITE     = (248, 250, 252)
DARK_TEXT      = (30, 30, 30)
GRAY_TEXT      = (120, 130, 140)
SUCCESS_GREEN  = (34, 197, 94)
LIGHT_GREEN    = (220, 252, 231)
WARNING_ORANGE = (245, 158, 11)
ERROR_RED      = (239, 68, 68)

SCREENSHOTS = [
    {"filename": "01_login",          "headline": "Secure Access",                    "subheadline": "Login with your patient credentials",        "bg_gradient": (BRAND_DARK, BRAND_BLUE),            "screen_type": "login"},
    {"filename": "02_dashboard",      "headline": "Your Health at a Glance",          "subheadline": "Dashboard with quick access to everything",  "bg_gradient": (BRAND_BLUE, (0,160,220)),            "screen_type": "dashboard"},
    {"filename": "03_appointments",   "headline": "Book & Manage\nAppointments",      "subheadline": "Schedule visits with your care team",        "bg_gradient": ((0,160,220), (0,180,200)),           "screen_type": "appointments"},
    {"filename": "04_lab_results",    "headline": "Lab Results\nInstantly",            "subheadline": "View your test results as they arrive",      "bg_gradient": ((0,100,180), BRAND_BLUE),            "screen_type": "lab_results"},
    {"filename": "05_medications",    "headline": "Track Your\nMedications",           "subheadline": "Prescriptions and refill requests",          "bg_gradient": ((0,140,190), (30,170,210)),          "screen_type": "medications"},
    {"filename": "06_messages",       "headline": "Message Your\nCare Team",           "subheadline": "Secure messaging with providers",            "bg_gradient": ((20,120,200), (0,150,210)),          "screen_type": "messages"},
    {"filename": "07_vitals",         "headline": "Monitor Vitals",                    "subheadline": "Track blood pressure, heart rate & more",    "bg_gradient": ((0,110,190), (0,160,200)),           "screen_type": "vitals"},
    {"filename": "08_billing",        "headline": "Billing &\nPayments",               "subheadline": "View invoices and payment history",          "bg_gradient": (BRAND_DARK, (0,100,170)),            "screen_type": "billing"},
    {"filename": "09_profile",        "headline": "Your Profile",                      "subheadline": "Manage your health information",             "bg_gradient": ((0,120,190), BRAND_BLUE),            "screen_type": "profile"},
    {"filename": "10_family_sharing", "headline": "Family Access\n& Sharing",          "subheadline": "Share records securely with family",         "bg_gradient": ((0,150,200), (0,180,220)),           "screen_type": "family"},
]

PREVIEWS = [
    {"filename": "preview_01_welcome",      "headline": "Welcome to\nMediHub Patient",    "subheadline": "Your complete health companion",           "bg_gradient": (BRAND_DARK, BRAND_BLUE)},
    {"filename": "preview_02_appointments", "headline": "Smart\nAppointments",             "subheadline": "Book, reschedule & get reminders",         "bg_gradient": (BRAND_BLUE, (0,170,220))},
    {"filename": "preview_03_records",      "headline": "All Your Records\nOne Place",     "subheadline": "Lab results, vitals, medications & more",  "bg_gradient": ((0,100,180), (0,150,210))},
]

# ── Helpers ──────────────────────────────────────────────────────────────
def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def draw_gradient(draw, w, h, top_color, bot_color):
    for y in range(h):
        c = lerp(top_color, bot_color, y / h)
        draw.line([(0, y), (w, y)], fill=c)

def get_font(size, bold=False):
    paths = [
        "/System/Library/Fonts/SFPro-Bold.otf" if bold else "/System/Library/Fonts/SFPro-Regular.otf",
        "/System/Library/Fonts/SFNS.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
        "/Library/Fonts/Arial Bold.ttf" if bold else "/Library/Fonts/Arial.ttf",
    ]
    for p in paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()

def rounded_rect(draw, xy, radius, fill):
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle(xy, radius=radius, fill=fill)

def draw_mock_phone(img, draw, x, y, pw, ph):
    """Draw a mock iPad device frame on the image."""
    radius = 36
    border = 6
    # Outer bezel
    draw.rounded_rectangle([x, y, x + pw, y + ph], radius=radius, fill=(50, 50, 55))
    # Inner screen area
    inner_margin = border + 4
    draw.rounded_rectangle(
        [x + inner_margin, y + inner_margin, x + pw - inner_margin, y + ph - inner_margin],
        radius=radius - 8, fill=NEAR_WHITE
    )
    return (x + inner_margin, y + inner_margin, pw - 2 * inner_margin, ph - 2 * inner_margin)

# ── Screen content drawers ───────────────────────────────────────────────

def draw_login_screen(draw, sx, sy, sw, sh):
    cy = sy + sh // 3
    # Logo circle
    draw.ellipse([sx + sw//2 - 60, cy - 80, sx + sw//2 + 60, cy + 40], fill=BRAND_BLUE)
    font = get_font(28, bold=True)
    draw.text((sx + sw//2, cy - 20), "M", fill=WHITE, font=font, anchor="mm")
    # Fields
    fy = cy + 80
    for label in ["Username", "Password"]:
        draw.rounded_rectangle([sx+40, fy, sx+sw-40, fy+56], radius=12, fill=WHITE, outline=(200,200,210))
        sf = get_font(18)
        draw.text((sx+60, fy+18), label, fill=GRAY_TEXT, font=sf)
        fy += 76
    # Button
    draw.rounded_rectangle([sx+40, fy+20, sx+sw-40, fy+76], radius=14, fill=BRAND_BLUE)
    bf = get_font(22, bold=True)
    draw.text((sx + sw//2, fy+48), "Sign In", fill=WHITE, font=bf, anchor="mm")

def draw_dashboard_screen(draw, sx, sy, sw, sh):
    # Header
    hf = get_font(24, bold=True)
    draw.text((sx+30, sy+30), "Dashboard", fill=DARK_TEXT, font=hf)
    # Quick links grid (3x2 for iPad)
    grid_y = sy + 90
    cols, rows = 3, 2
    tile_w = (sw - 80) // cols
    tile_h = 100
    colors = [BRAND_BLUE, SUCCESS_GREEN, (156,100,220), WARNING_ORANGE, ERROR_RED, (0,180,200)]
    labels = ["Appointments", "Lab Results", "Medications", "Billing", "Care Team", "Vitals"]
    icons  = ["📅", "🧪", "💊", "💳", "👥", "❤️"]
    for i in range(min(6, cols * rows)):
        r, c = divmod(i, cols)
        tx = sx + 30 + c * (tile_w + 10)
        ty = grid_y + r * (tile_h + 14)
        draw.rounded_rectangle([tx, ty, tx+tile_w, ty+tile_h], radius=16, fill=colors[i])
        lf = get_font(16, bold=True)
        draw.text((tx + tile_w//2, ty + tile_h//2), labels[i], fill=WHITE, font=lf, anchor="mm")
    # Section card
    card_y = grid_y + rows * (tile_h + 14) + 30
    draw.rounded_rectangle([sx+20, card_y, sx+sw-20, card_y+160], radius=16, fill=WHITE, outline=(230,230,235))
    sf = get_font(20, bold=True)
    draw.text((sx+40, card_y+18), "Upcoming Appointments", fill=DARK_TEXT, font=sf)
    for j in range(2):
        ry = card_y + 56 + j * 48
        draw.rounded_rectangle([sx+40, ry, sx+sw-40, ry+38], radius=10, fill=BRAND_LIGHT)
        rf = get_font(14)
        draw.text((sx+56, ry+10), f"Dr. Smith · Apr {10+j}, 2026 · 10:00 AM", fill=DARK_TEXT, font=rf)

def draw_list_screen(draw, sx, sy, sw, sh, title, items):
    hf = get_font(24, bold=True)
    draw.text((sx+30, sy+30), title, fill=DARK_TEXT, font=hf)
    for i, item in enumerate(items[:5]):
        ry = sy + 90 + i * 86
        draw.rounded_rectangle([sx+20, ry, sx+sw-20, ry+74], radius=14, fill=WHITE, outline=(235,235,240))
        tf = get_font(17, bold=True)
        draw.text((sx+40, ry+14), item[0], fill=DARK_TEXT, font=tf)
        sf = get_font(14)
        draw.text((sx+40, ry+42), item[1], fill=GRAY_TEXT, font=sf)
        # Status chip
        chip_color = SUCCESS_GREEN if "normal" in item[1].lower() or "confirmed" in item[1].lower() else BRAND_BLUE
        draw.rounded_rectangle([sx+sw-150, ry+20, sx+sw-40, ry+50], radius=10, fill=(*chip_color, 30))
        cf = get_font(12, bold=True)
        status = "Normal" if "normal" in item[1].lower() else "Active"
        draw.text((sx+sw-95, ry+28), status, fill=chip_color, font=cf, anchor="mm")

def draw_profile_screen(draw, sx, sy, sw, sh):
    cy = sy + 60
    draw.ellipse([sx+sw//2-50, cy, sx+sw//2+50, cy+100], fill=BRAND_BLUE)
    pf = get_font(36, bold=True)
    draw.text((sx+sw//2, cy+50), "T", fill=WHITE, font=pf, anchor="mm")
    nf = get_font(22, bold=True)
    draw.text((sx+sw//2, cy+120), "Tiego Ouedraogo", fill=DARK_TEXT, font=nf, anchor="mm")
    ef = get_font(14)
    draw.text((sx+sw//2, cy+148), "MRN: PAT-001234", fill=GRAY_TEXT, font=ef, anchor="mm")
    # Settings rows
    settings = ["Personal Info", "Language", "Health Records", "Documents", "Settings"]
    for i, s in enumerate(settings):
        ry = cy + 190 + i * 64
        draw.rounded_rectangle([sx+20, ry, sx+sw-20, ry+52], radius=12, fill=WHITE, outline=(235,235,240))
        sf = get_font(17)
        draw.text((sx+44, ry+16), s, fill=DARK_TEXT, font=sf)

def draw_family_screen(draw, sx, sy, sw, sh):
    hf = get_font(24, bold=True)
    draw.text((sx+30, sy+30), "Family Access", fill=DARK_TEXT, font=hf)
    members = [("Marie O.", "Spouse", "Full Access"), ("Awa O.", "Child", "View Only")]
    for i, (name, rel, access) in enumerate(members):
        ry = sy + 90 + i * 100
        draw.rounded_rectangle([sx+20, ry, sx+sw-20, ry+86], radius=14, fill=WHITE, outline=(235,235,240))
        draw.ellipse([sx+36, ry+16, sx+90, ry+70], fill=BRAND_LIGHT)
        nf = get_font(18, bold=True)
        draw.text((sx+104, ry+20), name, fill=DARK_TEXT, font=nf)
        rf = get_font(14)
        draw.text((sx+104, ry+48), f"{rel} · {access}", fill=GRAY_TEXT, font=rf)

def draw_screen_content(draw, sx, sy, sw, sh, screen_type):
    # Fill screen bg
    draw.rectangle([sx, sy, sx+sw, sy+sh], fill=NEAR_WHITE)
    if screen_type == "login":
        draw_login_screen(draw, sx, sy, sw, sh)
    elif screen_type == "dashboard":
        draw_dashboard_screen(draw, sx, sy, sw, sh)
    elif screen_type == "appointments":
        draw_list_screen(draw, sx, sy, sw, sh, "Appointments", [
            ("Dr. Ouedraogo", "Apr 10 · Confirmed"), ("Dr. Diallo", "Apr 14 · Pending"),
            ("Lab Work", "Apr 18 · Confirmed"), ("Dr. Kone", "Apr 22 · Scheduled"),
        ])
    elif screen_type == "lab_results":
        draw_list_screen(draw, sx, sy, sw, sh, "Lab Results", [
            ("Complete Blood Count", "Mar 28 · Normal"), ("Lipid Panel", "Mar 25 · Normal"),
            ("HbA1c", "Mar 20 · Review"), ("Urinalysis", "Mar 15 · Normal"),
        ])
    elif screen_type == "medications":
        draw_list_screen(draw, sx, sy, sw, sh, "Medications", [
            ("Metformin 500mg", "Twice daily · Active"), ("Lisinopril 10mg", "Once daily · Active"),
            ("Vitamin D 1000IU", "Once daily · Active"), ("Aspirin 81mg", "Once daily · Active"),
        ])
    elif screen_type == "messages":
        draw_list_screen(draw, sx, sy, sw, sh, "Messages", [
            ("Dr. Ouedraogo", "Your lab results look good..."), ("Pharmacy", "Your prescription is ready..."),
            ("Dr. Diallo", "Please schedule a follow-up..."), ("Admin", "Appointment reminder for..."),
        ])
    elif screen_type == "vitals":
        draw_list_screen(draw, sx, sy, sw, sh, "Vitals", [
            ("Blood Pressure", "120/80 mmHg · Normal"), ("Heart Rate", "72 bpm · Normal"),
            ("Temperature", "98.6°F · Normal"), ("Weight", "165 lbs · Stable"),
        ])
    elif screen_type == "billing":
        draw_list_screen(draw, sx, sy, sw, sh, "Billing", [
            ("Office Visit", "$150.00 · Paid"), ("Lab Work", "$85.00 · Pending"),
            ("Prescription", "$45.00 · Paid"), ("Imaging", "$320.00 · Insurance"),
        ])
    elif screen_type == "profile":
        draw_profile_screen(draw, sx, sy, sw, sh)
    elif screen_type == "family":
        draw_family_screen(draw, sx, sy, sw, sh)

# ── Main generation ──────────────────────────────────────────────────────

def generate_screenshot(spec, output_dir):
    img = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(img)

    # Background gradient
    draw_gradient(draw, W, H, spec["bg_gradient"][0], spec["bg_gradient"][1])

    # Headline text at top
    headline_font = get_font(72, bold=True)
    sub_font = get_font(34)

    # Headline
    lines = spec["headline"].split("\n")
    ty = 160
    for line in lines:
        draw.text((W // 2, ty), line, fill=WHITE, font=headline_font, anchor="mm")
        ty += 90

    # Subheadline
    draw.text((W // 2, ty + 20), spec["subheadline"], fill=(*WHITE[:2], 200), font=sub_font, anchor="mm")

    # Mock device frame (iPad-proportioned)
    device_w = int(W * 0.72)
    device_h = int(device_w * 1.35)
    dx = (W - device_w) // 2
    dy = ty + 100

    sx, sy, sw, sh = draw_mock_phone(img, draw, dx, dy, device_w, device_h)
    draw_screen_content(draw, sx, sy, sw, sh, spec["screen_type"])

    path = os.path.join(output_dir, f"{spec['filename']}.png")
    img.save(path, "PNG")
    print(f"  ✅ {path}")

def generate_preview(spec, output_dir):
    img = Image.new("RGB", (W, H))
    draw = ImageDraw.Draw(img)
    draw_gradient(draw, W, H, spec["bg_gradient"][0], spec["bg_gradient"][1])

    headline_font = get_font(80, bold=True)
    sub_font = get_font(36)

    # Center vertically
    lines = spec["headline"].split("\n")
    total_h = len(lines) * 100 + 60
    start_y = (H - total_h) // 2 - 40
    for line in lines:
        draw.text((W // 2, start_y), line, fill=WHITE, font=headline_font, anchor="mm")
        start_y += 100
    draw.text((W // 2, start_y + 30), spec["subheadline"], fill=(*WHITE[:2], 200), font=sub_font, anchor="mm")

    # App icon placeholder
    icon_y = start_y + 120
    draw.rounded_rectangle([W//2-60, icon_y, W//2+60, icon_y+120], radius=28, fill=WHITE)
    icon_font = get_font(48, bold=True)
    draw.text((W//2, icon_y+60), "M", fill=BRAND_BLUE, font=icon_font, anchor="mm")

    path = os.path.join(output_dir, f"{spec['filename']}.png")
    img.save(path, "PNG")
    print(f"  ✅ {path}")


if __name__ == "__main__":
    print("📱 Generating iPad 13\" screenshots (2048×2732)...")
    for s in SCREENSHOTS:
        generate_screenshot(s, OUT_DIR)

    print("\n🎬 Generating iPad 13\" preview posters...")
    for p in PREVIEWS:
        generate_preview(p, OUT_PREV)

    print(f"\nDone! {len(SCREENSHOTS)} screenshots + {len(PREVIEWS)} previews generated.")
