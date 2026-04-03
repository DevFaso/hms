#!/usr/bin/env python3
"""
App Store Screenshot & Preview Generator for MediHub Patient iOS App
Generates:
  - 10 screenshots at 1284x2778 (iPhone 14 Pro Max / 6.7")
  - 10 screenshots at 1242x2688 (iPhone XS Max / 6.5")
  - 3 app preview poster frames at 1284x2778 & 1242x2688
"""

import os, math
from PIL import Image, ImageDraw, ImageFont

# ── Directories ──────────────────────────────────────────────────────────
BASE = os.path.dirname(os.path.abspath(__file__))
OUT_67 = os.path.join(BASE, "screenshots", "6.7-inch")
OUT_65 = os.path.join(BASE, "screenshots", "6.5-inch")
OUT_PREV_67 = os.path.join(BASE, "previews", "6.7-inch")
OUT_PREV_65 = os.path.join(BASE, "previews", "6.5-inch")
for d in [OUT_67, OUT_65, OUT_PREV_67, OUT_PREV_65]:
    os.makedirs(d, exist_ok=True)

# ── Dimensions ───────────────────────────────────────────────────────────
SIZES = {
    "6.7": (1284, 2778),
    "6.5": (1242, 2688),
}

# ── Brand colours ────────────────────────────────────────────────────────
BRAND_BLUE     = (0, 133, 202)       # #0085CA
BRAND_DARK     = (0, 71, 133)        # #004785
BRAND_LIGHT    = (220, 240, 250)     # #DCF0FA
WHITE          = (255, 255, 255)
NEAR_WHITE     = (248, 250, 252)
DARK_TEXT      = (30, 30, 30)
GRAY_TEXT      = (120, 130, 140)
SUCCESS_GREEN  = (34, 197, 94)
LIGHT_GREEN    = (220, 252, 231)
WARNING_ORANGE = (245, 158, 11)
ERROR_RED      = (239, 68, 68)

# ── Screenshot definitions ───────────────────────────────────────────────
SCREENSHOTS = [
    {
        "filename": "01_login",
        "headline": "Secure Access",
        "subheadline": "Login with your patient credentials",
        "bg_gradient": (BRAND_DARK, BRAND_BLUE),
        "screen_type": "login",
    },
    {
        "filename": "02_dashboard",
        "headline": "Your Health at a Glance",
        "subheadline": "Dashboard with quick access to everything",
        "bg_gradient": (BRAND_BLUE, (0, 160, 220)),
        "screen_type": "dashboard",
    },
    {
        "filename": "03_appointments",
        "headline": "Book & Manage\nAppointments",
        "subheadline": "Schedule visits with your care team",
        "bg_gradient": ((0, 160, 220), (0, 180, 200)),
        "screen_type": "appointments",
    },
    {
        "filename": "04_lab_results",
        "headline": "Lab Results\nInstantly",
        "subheadline": "View your test results as they arrive",
        "bg_gradient": ((0, 100, 180), BRAND_BLUE),
        "screen_type": "lab_results",
    },
    {
        "filename": "05_medications",
        "headline": "Track Your\nMedications",
        "subheadline": "Prescriptions and refill requests",
        "bg_gradient": ((0, 140, 190), (30, 170, 210)),
        "screen_type": "medications",
    },
    {
        "filename": "06_messages",
        "headline": "Message Your\nCare Team",
        "subheadline": "Secure messaging with providers",
        "bg_gradient": ((20, 120, 200), (0, 150, 210)),
        "screen_type": "messages",
    },
    {
        "filename": "07_vitals",
        "headline": "Monitor Vitals",
        "subheadline": "Track blood pressure, heart rate & more",
        "bg_gradient": ((0, 110, 190), (0, 160, 200)),
        "screen_type": "vitals",
    },
    {
        "filename": "08_billing",
        "headline": "Billing &\nPayments",
        "subheadline": "View invoices and payment history",
        "bg_gradient": (BRAND_DARK, (0, 100, 170)),
        "screen_type": "billing",
    },
    {
        "filename": "09_profile",
        "headline": "Your Profile",
        "subheadline": "Manage personal & medical information",
        "bg_gradient": ((0, 90, 170), BRAND_BLUE),
        "screen_type": "profile",
    },
    {
        "filename": "10_family_sharing",
        "headline": "Family Access &\nSharing",
        "subheadline": "Share records securely with family",
        "bg_gradient": ((0, 130, 190), (20, 160, 210)),
        "screen_type": "family",
    },
]

PREVIEWS = [
    {
        "filename": "preview_01_welcome",
        "headline": "Welcome to\nMediHub",
        "subheadline": "Your complete health companion",
        "bg_gradient": (BRAND_DARK, BRAND_BLUE),
        "screen_type": "dashboard",
    },
    {
        "filename": "preview_02_appointments",
        "headline": "Smart\nAppointments",
        "subheadline": "Book, reschedule & manage visits",
        "bg_gradient": (BRAND_BLUE, (0, 170, 210)),
        "screen_type": "appointments",
    },
    {
        "filename": "preview_03_records",
        "headline": "All Your\nRecords",
        "subheadline": "Lab results, vitals, medications",
        "bg_gradient": ((0, 100, 180), (0, 150, 200)),
        "screen_type": "lab_results",
    },
]


# ── Helper: gradient background ─────────────────────────────────────────
def make_gradient(w, h, color_top, color_bottom):
    img = Image.new("RGB", (w, h))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        r = int(color_top[0] + (color_bottom[0] - color_top[0]) * t)
        g = int(color_top[1] + (color_bottom[1] - color_top[1]) * t)
        b = int(color_top[2] + (color_bottom[2] - color_top[2]) * t)
        draw.line([(0, y), (w, y)], fill=(r, g, b))
    return img


# ── Helper: rounded rectangle ───────────────────────────────────────────
def rounded_rect(draw, bbox, radius, fill, outline=None):
    x0, y0, x1, y1 = bbox
    draw.rounded_rectangle(bbox, radius=radius, fill=fill, outline=outline)


# ── Helper: draw a mock phone frame with screen content ─────────────────
def draw_phone_frame(img, draw, w, h, screen_type, scale):
    """Draw a phone mockup in the lower portion of the screenshot."""
    phone_w = int(w * 0.78)
    phone_h = int(h * 0.58)
    phone_x = (w - phone_w) // 2
    phone_y = h - phone_h - int(40 * scale)
    corner_r = int(40 * scale)

    # Phone body (dark border + white screen)
    rounded_rect(draw, (phone_x - 4, phone_y - 4, phone_x + phone_w + 4, phone_y + phone_h + 4),
                 corner_r + 2, fill=(50, 50, 50))
    rounded_rect(draw, (phone_x, phone_y, phone_x + phone_w, phone_y + phone_h),
                 corner_r, fill=WHITE)

    # Status bar
    sb_y = phone_y + int(12 * scale)
    draw.text((phone_x + int(24 * scale), sb_y), "9:41", fill=DARK_TEXT,
              font=get_font(int(16 * scale), bold=True))
    # Battery icon area
    bat_x = phone_x + phone_w - int(70 * scale)
    draw.rectangle((bat_x, sb_y + int(2 * scale), bat_x + int(28 * scale), sb_y + int(14 * scale)),
                   fill=DARK_TEXT)

    # Content area
    cx = phone_x + int(16 * scale)
    cy = phone_y + int(44 * scale)
    cw = phone_w - int(32 * scale)

    if screen_type == "login":
        draw_login_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "dashboard":
        draw_dashboard_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "appointments":
        draw_appointments_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "lab_results":
        draw_lab_results_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "medications":
        draw_medications_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "messages":
        draw_messages_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "vitals":
        draw_vitals_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "billing":
        draw_billing_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "profile":
        draw_profile_screen(draw, cx, cy, cw, phone_h, scale)
    elif screen_type == "family":
        draw_family_screen(draw, cx, cy, cw, phone_h, scale)

    # Bottom tab bar
    tab_y = phone_y + phone_h - int(60 * scale)
    draw.line([(phone_x + int(12 * scale), tab_y),
               (phone_x + phone_w - int(12 * scale), tab_y)], fill=(230, 230, 230), width=1)
    tabs = ["Dashboard", "Appts", "Messages", "Profile"]
    tab_w_each = cw // len(tabs)
    for i, tab_name in enumerate(tabs):
        tx = cx + i * tab_w_each + tab_w_each // 2
        # Tab icon placeholder
        icon_size = int(20 * scale)
        color = BRAND_BLUE if i == 0 else GRAY_TEXT
        draw.ellipse((tx - icon_size // 2, tab_y + int(8 * scale),
                       tx + icon_size // 2, tab_y + int(8 * scale) + icon_size),
                      fill=color)
        draw.text((tx, tab_y + int(32 * scale)), tab_name,
                  fill=color, font=get_font(int(10 * scale)), anchor="mt")

    # Home indicator
    hi_w = int(140 * scale)
    hi_h = int(5 * scale)
    hi_x = phone_x + (phone_w - hi_w) // 2
    hi_y = phone_y + phone_h - int(12 * scale)
    rounded_rect(draw, (hi_x, hi_y, hi_x + hi_w, hi_y + hi_h), hi_h // 2, fill=(200, 200, 200))


# ── Screen content drawers ──────────────────────────────────────────────
def draw_login_screen(draw, cx, cy, cw, ph, s):
    # Logo circle
    logo_size = int(70 * s)
    logo_x = cx + cw // 2 - logo_size // 2
    logo_y = cy + int(40 * s)
    draw.ellipse((logo_x, logo_y, logo_x + logo_size, logo_y + logo_size), fill=BRAND_BLUE)
    draw.text((logo_x + logo_size // 2, logo_y + logo_size // 2), "M",
              fill=WHITE, font=get_font(int(32 * s), bold=True), anchor="mm")

    # App name
    draw.text((cx + cw // 2, logo_y + logo_size + int(16 * s)), "MediHub",
              fill=BRAND_DARK, font=get_font(int(24 * s), bold=True), anchor="mt")
    draw.text((cx + cw // 2, logo_y + logo_size + int(44 * s)), "Patient Portal",
              fill=GRAY_TEXT, font=get_font(int(14 * s)), anchor="mt")

    # Input fields
    field_y = logo_y + logo_size + int(80 * s)
    for i, label in enumerate(["Username", "Password"]):
        fy = field_y + i * int(56 * s)
        rounded_rect(draw, (cx + int(20 * s), fy, cx + cw - int(20 * s), fy + int(44 * s)),
                     int(10 * s), fill=NEAR_WHITE, outline=(210, 210, 210))
        draw.text((cx + int(36 * s), fy + int(12 * s)), label,
                  fill=GRAY_TEXT, font=get_font(int(14 * s)))

    # Login button
    btn_y = field_y + int(130 * s)
    rounded_rect(draw, (cx + int(20 * s), btn_y, cx + cw - int(20 * s), btn_y + int(48 * s)),
                 int(12 * s), fill=BRAND_BLUE)
    draw.text((cx + cw // 2, btn_y + int(24 * s)), "Sign In",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mm")

    # Biometric
    bio_y = btn_y + int(70 * s)
    bio_size = int(40 * s)
    draw.ellipse((cx + cw // 2 - bio_size // 2, bio_y,
                   cx + cw // 2 + bio_size // 2, bio_y + bio_size),
                  outline=BRAND_BLUE, width=2)
    draw.text((cx + cw // 2, bio_y + bio_size + int(10 * s)), "Use Face ID",
              fill=BRAND_BLUE, font=get_font(int(12 * s)), anchor="mt")


def draw_dashboard_screen(draw, cx, cy, cw, ph, s):
    # Blue header
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(80 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + int(8 * s), cy + int(8 * s)), "Good Morning, Tiego",
              fill=WHITE, font=get_font(int(16 * s), bold=True))
    draw.text((cx + int(8 * s), cy + int(30 * s)), "MRN: PAT-001234",
              fill=(200, 225, 245), font=get_font(int(11 * s)))

    # Health summary card
    card_y = cy + int(90 * s)
    rounded_rect(draw, (cx + int(4 * s), card_y, cx + cw - int(4 * s), card_y + int(70 * s)),
                 int(12 * s), fill=WHITE, outline=(230, 230, 230))
    draw.text((cx + int(16 * s), card_y + int(10 * s)), "Health Summary",
              fill=DARK_TEXT, font=get_font(int(13 * s), bold=True))
    # Mini stats
    stats = [("BP", "120/80"), ("HR", "72"), ("BMI", "22.4")]
    sw = (cw - int(24 * s)) // 3
    for i, (lbl, val) in enumerate(stats):
        sx = cx + int(16 * s) + i * sw
        draw.text((sx, card_y + int(32 * s)), lbl, fill=GRAY_TEXT, font=get_font(int(10 * s)))
        draw.text((sx, card_y + int(46 * s)), val, fill=BRAND_BLUE, font=get_font(int(14 * s), bold=True))

    # Quick Access grid
    qa_y = card_y + int(84 * s)
    rounded_rect(draw, (cx + int(4 * s), qa_y, cx + cw - int(4 * s), qa_y + int(130 * s)),
                 int(12 * s), fill=WHITE, outline=(230, 230, 230))
    draw.text((cx + int(16 * s), qa_y + int(10 * s)), "Quick Access",
              fill=DARK_TEXT, font=get_font(int(13 * s), bold=True))

    icons = ["Appts", "Lab", "Meds", "Billing", "Vitals", "Team", "Docs", "Family"]
    cols = 4
    icon_w = (cw - int(40 * s)) // cols
    for i, name in enumerate(icons):
        row, col = divmod(i, cols)
        ix = cx + int(20 * s) + col * icon_w + icon_w // 2
        iy = qa_y + int(34 * s) + row * int(48 * s)
        # icon box
        isize = int(28 * s)
        rounded_rect(draw, (ix - isize // 2, iy, ix + isize // 2, iy + isize),
                     int(6 * s), fill=BRAND_LIGHT)
        draw.text((ix, iy + isize + int(4 * s)), name,
                  fill=DARK_TEXT, font=get_font(int(9 * s)), anchor="mt")

    # Upcoming appointment card
    ua_y = qa_y + int(144 * s)
    rounded_rect(draw, (cx + int(4 * s), ua_y, cx + cw - int(4 * s), ua_y + int(60 * s)),
                 int(12 * s), fill=WHITE, outline=(230, 230, 230))
    draw.text((cx + int(16 * s), ua_y + int(8 * s)), "Upcoming Appointment",
              fill=DARK_TEXT, font=get_font(int(12 * s), bold=True))
    draw.text((cx + int(16 * s), ua_y + int(26 * s)), "Dr. Smith — Cardiology",
              fill=GRAY_TEXT, font=get_font(int(11 * s)))
    draw.text((cx + int(16 * s), ua_y + int(42 * s)), "Apr 10, 2026 — 10:00 AM",
              fill=BRAND_BLUE, font=get_font(int(10 * s)))


def draw_appointments_screen(draw, cx, cy, cw, ph, s):
    # Header bar
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Appointments",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    # Tabs
    tab_y = cy + int(50 * s)
    for i, t in enumerate(["Upcoming", "Past", "Cancelled"]):
        tx = cx + int(12 * s) + i * int(95 * s)
        col = BRAND_BLUE if i == 0 else GRAY_TEXT
        draw.text((tx, tab_y), t, fill=col, font=get_font(int(12 * s), bold=(i == 0)))
    draw.line([(cx + int(12 * s), tab_y + int(18 * s)),
               (cx + int(80 * s), tab_y + int(18 * s))], fill=BRAND_BLUE, width=int(2 * s))

    # Appointment cards
    appts = [
        ("Dr. Sarah Johnson", "Cardiology", "Apr 10, 2026 • 10:00 AM", "Confirmed", SUCCESS_GREEN),
        ("Dr. Ahmed Diallo", "General Med", "Apr 15, 2026 • 2:30 PM", "Pending", WARNING_ORANGE),
        ("Dr. Marie Koné", "Dermatology", "Apr 22, 2026 • 9:00 AM", "Confirmed", SUCCESS_GREEN),
    ]
    for i, (doc, dept, dt, status, color) in enumerate(appts):
        ay = tab_y + int(30 * s) + i * int(76 * s)
        rounded_rect(draw, (cx + int(4 * s), ay, cx + cw - int(4 * s), ay + int(68 * s)),
                     int(10 * s), fill=WHITE, outline=(235, 235, 235))
        draw.text((cx + int(16 * s), ay + int(10 * s)), doc,
                  fill=DARK_TEXT, font=get_font(int(13 * s), bold=True))
        draw.text((cx + int(16 * s), ay + int(28 * s)), dept,
                  fill=GRAY_TEXT, font=get_font(int(11 * s)))
        draw.text((cx + int(16 * s), ay + int(46 * s)), dt,
                  fill=BRAND_BLUE, font=get_font(int(10 * s)))
        # Status badge
        badge_x = cx + cw - int(80 * s)
        rounded_rect(draw, (badge_x, ay + int(10 * s), badge_x + int(68 * s), ay + int(28 * s)),
                     int(8 * s), fill=(*color, 40))
        draw.text((badge_x + int(34 * s), ay + int(19 * s)), status,
                  fill=color, font=get_font(int(9 * s), bold=True), anchor="mm")

    # FAB
    fab_size = int(48 * s)
    fab_x = cx + cw - int(20 * s) - fab_size
    fab_y = tab_y + int(280 * s)
    draw.ellipse((fab_x, fab_y, fab_x + fab_size, fab_y + fab_size), fill=BRAND_BLUE)
    draw.text((fab_x + fab_size // 2, fab_y + fab_size // 2), "+",
              fill=WHITE, font=get_font(int(24 * s), bold=True), anchor="mm")


def draw_lab_results_screen(draw, cx, cy, cw, ph, s):
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Lab Results",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    labs = [
        ("Complete Blood Count", "Apr 01, 2026", "Normal", SUCCESS_GREEN),
        ("Metabolic Panel", "Mar 28, 2026", "Review", WARNING_ORANGE),
        ("Lipid Panel", "Mar 15, 2026", "Normal", SUCCESS_GREEN),
        ("Thyroid Function", "Mar 10, 2026", "Normal", SUCCESS_GREEN),
    ]
    for i, (name, dt, status, color) in enumerate(labs):
        ly = cy + int(54 * s) + i * int(68 * s)
        rounded_rect(draw, (cx + int(4 * s), ly, cx + cw - int(4 * s), ly + int(60 * s)),
                     int(10 * s), fill=WHITE, outline=(235, 235, 235))
        # Icon
        draw.ellipse((cx + int(14 * s), ly + int(14 * s),
                       cx + int(42 * s), ly + int(42 * s)), fill=BRAND_LIGHT)
        draw.text((cx + int(28 * s), ly + int(28 * s)), "🧪",
                  font=get_font(int(12 * s)), anchor="mm")
        draw.text((cx + int(52 * s), ly + int(12 * s)), name,
                  fill=DARK_TEXT, font=get_font(int(12 * s), bold=True))
        draw.text((cx + int(52 * s), ly + int(30 * s)), dt,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        badge_x = cx + cw - int(70 * s)
        rounded_rect(draw, (badge_x, ly + int(16 * s), badge_x + int(56 * s), ly + int(34 * s)),
                     int(8 * s), fill=(*color, 40))
        draw.text((badge_x + int(28 * s), ly + int(25 * s)), status,
                  fill=color, font=get_font(int(9 * s), bold=True), anchor="mm")


def draw_medications_screen(draw, cx, cy, cw, ph, s):
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Medications",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    meds = [
        ("Lisinopril 10mg", "Once daily", "Active"),
        ("Metformin 500mg", "Twice daily", "Active"),
        ("Atorvastatin 20mg", "At bedtime", "Active"),
    ]
    for i, (name, freq, status) in enumerate(meds):
        my = cy + int(54 * s) + i * int(72 * s)
        rounded_rect(draw, (cx + int(4 * s), my, cx + cw - int(4 * s), my + int(64 * s)),
                     int(10 * s), fill=WHITE, outline=(235, 235, 235))
        # Pill icon
        rounded_rect(draw, (cx + int(14 * s), my + int(14 * s),
                            cx + int(42 * s), my + int(42 * s)),
                     int(8 * s), fill=BRAND_LIGHT)
        draw.text((cx + int(52 * s), my + int(12 * s)), name,
                  fill=DARK_TEXT, font=get_font(int(13 * s), bold=True))
        draw.text((cx + int(52 * s), my + int(30 * s)), freq,
                  fill=GRAY_TEXT, font=get_font(int(11 * s)))
        badge_x = cx + cw - int(66 * s)
        rounded_rect(draw, (badge_x, my + int(14 * s), badge_x + int(52 * s), my + int(32 * s)),
                     int(8 * s), fill=LIGHT_GREEN)
        draw.text((badge_x + int(26 * s), my + int(23 * s)), status,
                  fill=SUCCESS_GREEN, font=get_font(int(9 * s), bold=True), anchor="mm")

    # Refill button
    btn_y = cy + int(54 * s) + 3 * int(72 * s) + int(10 * s)
    rounded_rect(draw, (cx + int(20 * s), btn_y, cx + cw - int(20 * s), btn_y + int(40 * s)),
                 int(10 * s), fill=BRAND_BLUE)
    draw.text((cx + cw // 2, btn_y + int(20 * s)), "Request Refill",
              fill=WHITE, font=get_font(int(13 * s), bold=True), anchor="mm")


def draw_messages_screen(draw, cx, cy, cw, ph, s):
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Messages",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    threads = [
        ("Dr. Sarah Johnson", "Your lab results look great...", "10:30 AM", True),
        ("Pharmacy", "Your prescription is ready for...", "Yesterday", False),
        ("Dr. Ahmed Diallo", "Please schedule a follow-up...", "Apr 1", True),
        ("Billing Dept", "Your invoice #INV-2026 has...", "Mar 30", False),
    ]
    for i, (name, preview, time, unread) in enumerate(threads):
        my = cy + int(54 * s) + i * int(64 * s)
        rounded_rect(draw, (cx + int(4 * s), my, cx + cw - int(4 * s), my + int(56 * s)),
                     int(10 * s), fill=WHITE if not unread else (245, 248, 255),
                     outline=(235, 235, 235))
        # Avatar
        draw.ellipse((cx + int(14 * s), my + int(10 * s),
                       cx + int(42 * s), my + int(38 * s)), fill=BRAND_LIGHT)
        draw.text((cx + int(28 * s), my + int(24 * s)),
                  name[0], fill=BRAND_BLUE, font=get_font(int(12 * s), bold=True), anchor="mm")
        draw.text((cx + int(52 * s), my + int(10 * s)), name,
                  fill=DARK_TEXT, font=get_font(int(12 * s), bold=True))
        draw.text((cx + int(52 * s), my + int(28 * s)), preview,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        draw.text((cx + cw - int(16 * s), my + int(10 * s)), time,
                  fill=GRAY_TEXT, font=get_font(int(9 * s)), anchor="rt")
        if unread:
            dot_x = cx + cw - int(20 * s)
            draw.ellipse((dot_x, my + int(28 * s), dot_x + int(10 * s), my + int(38 * s)),
                          fill=BRAND_BLUE)


def draw_vitals_screen(draw, cx, cy, cw, ph, s):
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Vitals",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    vitals_data = [
        ("Blood Pressure", "120/80", "mmHg", "Normal", SUCCESS_GREEN),
        ("Heart Rate", "72", "bpm", "Normal", SUCCESS_GREEN),
        ("Temperature", "98.6", "°F", "Normal", SUCCESS_GREEN),
        ("SpO2", "98", "%", "Normal", SUCCESS_GREEN),
    ]
    card_w = (cw - int(20 * s)) // 2
    for i, (name, val, unit, status, color) in enumerate(vitals_data):
        row, col = divmod(i, 2)
        vx = cx + int(4 * s) + col * (card_w + int(12 * s))
        vy = cy + int(54 * s) + row * int(100 * s)
        rounded_rect(draw, (vx, vy, vx + card_w, vy + int(88 * s)),
                     int(12 * s), fill=WHITE, outline=(235, 235, 235))
        draw.text((vx + int(12 * s), vy + int(10 * s)), name,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        draw.text((vx + int(12 * s), vy + int(28 * s)), val,
                  fill=DARK_TEXT, font=get_font(int(22 * s), bold=True))
        draw.text((vx + int(12 * s) + len(val) * int(14 * s), vy + int(36 * s)), unit,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        rounded_rect(draw, (vx + int(12 * s), vy + int(60 * s),
                            vx + int(12 * s) + int(52 * s), vy + int(76 * s)),
                     int(6 * s), fill=LIGHT_GREEN)
        draw.text((vx + int(38 * s), vy + int(68 * s)), status,
                  fill=color, font=get_font(int(9 * s), bold=True), anchor="mm")


def draw_billing_screen(draw, cx, cy, cw, ph, s):
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Billing & Invoices",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    # Balance card
    bal_y = cy + int(54 * s)
    rounded_rect(draw, (cx + int(4 * s), bal_y, cx + cw - int(4 * s), bal_y + int(60 * s)),
                 int(12 * s), fill=BRAND_BLUE)
    draw.text((cx + int(20 * s), bal_y + int(10 * s)), "Outstanding Balance",
              fill=(200, 225, 245), font=get_font(int(11 * s)))
    draw.text((cx + int(20 * s), bal_y + int(28 * s)), "$450.00",
              fill=WHITE, font=get_font(int(22 * s), bold=True))

    invoices = [
        ("INV-2026-0401", "Apr 01, 2026", "$150.00", "Unpaid", WARNING_ORANGE),
        ("INV-2026-0315", "Mar 15, 2026", "$300.00", "Unpaid", WARNING_ORANGE),
        ("INV-2026-0228", "Feb 28, 2026", "$200.00", "Paid", SUCCESS_GREEN),
    ]
    for i, (inv, dt, amt, status, color) in enumerate(invoices):
        iy = bal_y + int(74 * s) + i * int(64 * s)
        rounded_rect(draw, (cx + int(4 * s), iy, cx + cw - int(4 * s), iy + int(56 * s)),
                     int(10 * s), fill=WHITE, outline=(235, 235, 235))
        draw.text((cx + int(16 * s), iy + int(10 * s)), inv,
                  fill=DARK_TEXT, font=get_font(int(12 * s), bold=True))
        draw.text((cx + int(16 * s), iy + int(28 * s)), dt,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        draw.text((cx + cw - int(20 * s), iy + int(10 * s)), amt,
                  fill=DARK_TEXT, font=get_font(int(13 * s), bold=True), anchor="rt")
        badge_x = cx + cw - int(64 * s)
        rounded_rect(draw, (badge_x, iy + int(30 * s), badge_x + int(50 * s), iy + int(46 * s)),
                     int(6 * s), fill=(*color, 40))
        draw.text((badge_x + int(25 * s), iy + int(38 * s)), status,
                  fill=color, font=get_font(int(9 * s), bold=True), anchor="mm")


def draw_profile_screen(draw, cx, cy, cw, ph, s):
    # Profile header with avatar
    header_h = int(100 * s)
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + header_h),
                 0, fill=BRAND_BLUE)
    # Avatar
    av_size = int(56 * s)
    av_x = cx + cw // 2 - av_size // 2
    av_y = cy + int(10 * s)
    draw.ellipse((av_x, av_y, av_x + av_size, av_y + av_size), fill=WHITE)
    draw.text((av_x + av_size // 2, av_y + av_size // 2), "TO",
              fill=BRAND_BLUE, font=get_font(int(18 * s), bold=True), anchor="mm")
    draw.text((cx + cw // 2, av_y + av_size + int(8 * s)), "Tiego Ouedraogo",
              fill=WHITE, font=get_font(int(14 * s), bold=True), anchor="mt")

    # Info cards
    fields = [
        ("Email", "t.ouedraogo@email.com"),
        ("Phone", "+226 70 12 34 56"),
        ("Date of Birth", "Jan 15, 1990"),
        ("Blood Type", "O+"),
        ("Insurance", "CNSS — Active"),
    ]
    for i, (label, value) in enumerate(fields):
        fy = cy + header_h + int(12 * s) + i * int(44 * s)
        rounded_rect(draw, (cx + int(4 * s), fy, cx + cw - int(4 * s), fy + int(38 * s)),
                     int(8 * s), fill=WHITE, outline=(235, 235, 235))
        draw.text((cx + int(16 * s), fy + int(6 * s)), label,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        draw.text((cx + int(16 * s), fy + int(20 * s)), value,
                  fill=DARK_TEXT, font=get_font(int(11 * s), bold=True))


def draw_family_screen(draw, cx, cy, cw, ph, s):
    rounded_rect(draw, (cx - int(16 * s), cy - int(4 * s), cx + cw + int(16 * s), cy + int(44 * s)),
                 0, fill=BRAND_BLUE)
    draw.text((cx + cw // 2, cy + int(14 * s)), "Family Access",
              fill=WHITE, font=get_font(int(16 * s), bold=True), anchor="mt")

    # Tabs
    tab_y = cy + int(50 * s)
    for i, t in enumerate(["Granted", "Received"]):
        tx = cx + int(20 * s) + i * int(120 * s)
        col = BRAND_BLUE if i == 0 else GRAY_TEXT
        draw.text((tx, tab_y), t, fill=col, font=get_font(int(13 * s), bold=(i == 0)))

    members = [
        ("Aminata Ouedraogo", "Spouse", "Full Access", SUCCESS_GREEN),
        ("Ibrahim Ouedraogo", "Parent", "View Only", BRAND_BLUE),
    ]
    for i, (name, relation, access, color) in enumerate(members):
        my = tab_y + int(28 * s) + i * int(72 * s)
        rounded_rect(draw, (cx + int(4 * s), my, cx + cw - int(4 * s), my + int(64 * s)),
                     int(10 * s), fill=WHITE, outline=(235, 235, 235))
        # Avatar
        draw.ellipse((cx + int(14 * s), my + int(12 * s),
                       cx + int(46 * s), my + int(44 * s)), fill=BRAND_LIGHT)
        draw.text((cx + int(30 * s), my + int(28 * s)),
                  name[0], fill=BRAND_BLUE, font=get_font(int(14 * s), bold=True), anchor="mm")
        draw.text((cx + int(56 * s), my + int(12 * s)), name,
                  fill=DARK_TEXT, font=get_font(int(12 * s), bold=True))
        draw.text((cx + int(56 * s), my + int(30 * s)), relation,
                  fill=GRAY_TEXT, font=get_font(int(10 * s)))
        badge_x = cx + cw - int(80 * s)
        rounded_rect(draw, (badge_x, my + int(38 * s), badge_x + int(68 * s), my + int(54 * s)),
                     int(6 * s), fill=(*color, 30))
        draw.text((badge_x + int(34 * s), my + int(46 * s)), access,
                  fill=color, font=get_font(int(9 * s), bold=True), anchor="mm")


# ── Font helper ──────────────────────────────────────────────────────────
_font_cache = {}

def get_font(size, bold=False):
    key = (size, bold)
    if key not in _font_cache:
        # Try system fonts on macOS
        paths = [
            "/System/Library/Fonts/SFPro-Bold.otf" if bold else "/System/Library/Fonts/SFPro-Regular.otf",
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold else "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
        ]
        font = None
        for p in paths:
            if os.path.exists(p):
                try:
                    font = ImageFont.truetype(p, size)
                    break
                except Exception:
                    continue
        if font is None:
            font = ImageFont.load_default()
        _font_cache[key] = font
    return _font_cache[key]


# ── Main generator ───────────────────────────────────────────────────────
def generate_screenshot(spec, size_key, output_dir, is_preview=False):
    w, h = SIZES[size_key]
    scale = w / 1284  # Scale relative to 6.7" baseline

    # Background gradient
    c1, c2 = spec["bg_gradient"]
    img = make_gradient(w, h, c1, c2)
    draw = ImageDraw.Draw(img)

    # Decorative circles (subtle)
    for cx_off, cy_off, radius, alpha in [(0.8, 0.15, 200, 20), (0.2, 0.25, 150, 15), (0.9, 0.6, 100, 10)]:
        cx_d = int(w * cx_off)
        cy_d = int(h * cy_off)
        r = int(radius * scale)
        overlay = Image.new("RGBA", (r * 2, r * 2), (0, 0, 0, 0))
        ov_draw = ImageDraw.Draw(overlay)
        ov_draw.ellipse((0, 0, r * 2, r * 2), fill=(255, 255, 255, alpha))
        img.paste(Image.alpha_composite(
            Image.new("RGBA", overlay.size, (0, 0, 0, 0)), overlay).convert("RGB"),
            (cx_d - r, cy_d - r), overlay)

    draw = ImageDraw.Draw(img)

    # Headline text
    headline_y = int(100 * scale)
    lines = spec["headline"].split("\n")
    for i, line in enumerate(lines):
        draw.text((w // 2, headline_y + i * int(60 * scale)), line,
                  fill=WHITE, font=get_font(int(48 * scale), bold=True), anchor="mt")

    # Subheadline
    sub_y = headline_y + len(lines) * int(60 * scale) + int(10 * scale)
    draw.text((w // 2, sub_y), spec["subheadline"],
              fill=(220, 235, 250), font=get_font(int(22 * scale)), anchor="mt")

    # Phone frame with screen content
    draw_phone_frame(img, draw, w, h, spec["screen_type"], scale)

    # Save
    fname = f"{spec['filename']}.png"
    path = os.path.join(output_dir, fname)
    img.save(path, "PNG", quality=100)
    print(f"  ✅ {path}")
    return path


def main():
    print("=" * 60)
    print("MediHub Patient — App Store Asset Generator")
    print("=" * 60)

    # Generate screenshots for both sizes
    for size_key, (w, h) in SIZES.items():
        out = OUT_67 if size_key == "6.7" else OUT_65
        print(f"\n📱 Generating 10 screenshots @ {w}×{h} ({size_key}\")...")
        for spec in SCREENSHOTS:
            generate_screenshot(spec, size_key, out)

    # Generate preview poster frames
    for size_key, (w, h) in SIZES.items():
        out = OUT_PREV_67 if size_key == "6.7" else OUT_PREV_65
        print(f"\n🎬 Generating 3 preview frames @ {w}×{h} ({size_key}\")...")
        for spec in PREVIEWS:
            generate_screenshot(spec, size_key, out, is_preview=True)

    print("\n" + "=" * 60)
    print("✅ All assets generated!")
    print(f"   Screenshots: {OUT_67}")
    print(f"                {OUT_65}")
    print(f"   Previews:    {OUT_PREV_67}")
    print(f"                {OUT_PREV_65}")
    print("=" * 60)


if __name__ == "__main__":
    main()
