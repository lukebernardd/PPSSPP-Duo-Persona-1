package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;

import java.util.Arrays;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persona 1 PSP companion screen. Uses the game's live party/battle memory data
 * and the original in-game face portraits, with a custom SMT IV-inspired layout.
 */
public final class Persona1Mod extends DuoMod {
    public static final String ID = "persona1";
    private static final String[] GAME_IDS = {"ULUS10432"};

    // Verified for Persona 1 PSP USA ULUS10432 v1.00.
    private static final int FLAG = 0x08B967BC;
    private static final int FIELD = 8;
    private static final int PARTY = 0x08CC51F8;
    private static final int PARTY_SIZE = 0x80;
    private static final int UNITS = 0x08D03DC8;
    private static final int UNIT_SIZE = 0x118;
    private static final int PARTY_COUNT = 5;
    private static final int ENEMY_COUNT = 8;
    private static final int ID_OFFSET = 0x3E;
    private static final int NAME = 0x40;
    private static final int NAME_LEN = 0x24;
    private static final int LEVEL = 0x6A;
    private static final int FACING = 0x08CCD8CC;
    private static final int DUNGEON_PTR = 0x08CCBD5C;
    private static final int DUNGEON_SIZE = 0x50;
    private static final int BLOCK_TABLE = 0x08A92AD4;
    private static final int BLOCK_TABLE_SIZE = 0x40;
    private static final int EXPLORED = 0x08CC689C;
    private static final int MAP_SIZE = 24;
    private static final int MAP_BLOCK = MAP_SIZE * 3;
    private static final int MAP_BLOCK_COUNT = 0xA8;

    private DuoModContext host;
    private Persona1View view;
    private long lastBattle;
    private int dungeon;

    @Override public String getId() { return ID; }

    @Override public String getTitle(Context context) {
        return "Persona 1 Dual Screen";
    }

    @Override public String getDescription(Context context) {
        return "Dual-screen party status and game information for Persona 1.";
    }

    @Override public int getPriority(DuoStatus status) {
        return Arrays.asList(GAME_IDS).contains(status.gameId) ? 100 : -1;
    }

    @Override public long getStatusIntervalMs() { return 100; }

    @Override public View onCreateView(DuoModContext host) {
        this.host = host;
        view = new Persona1View(host.getContext());
        dungeon = 0;
        watch();
        Persona1Art.loadFaces(host, () -> {
            if (view != null) view.invalidate();
        });
        return view;
    }

    @Override public void onDestroyView() {
        view = null;
        host = null;
    }

    @Override public void onStatus(DuoStatus status) {
        if (view == null || host == null || (!status.isInGame() && !status.isPauseMenu())) return;
        byte[] flag = host.readMemoryWatch(0);
        byte[] party = host.readMemoryWatch(1);
        byte[] units = host.readMemoryWatch(2);
        if (flag == null || party == null || units == null) return;
        MapState map = readMap();
        view.setMap(map);

        Unit[] enemies = null;
        if (u32(flag, 0) != FIELD) {
            enemies = readUnits(units, PARTY_COUNT, ENEMY_COUNT, UNIT_SIZE, true);
            boolean alive = false;
            for (Unit enemy : enemies) alive |= enemy.hp > 0;
            if (!alive) enemies = null;
        }

        if (enemies != null) {
            lastBattle = android.os.SystemClock.uptimeMillis();
            view.setUnits(readUnits(units, 0, PARTY_COUNT, UNIT_SIZE, false), enemies, true);
            for (Unit enemy : enemies) Persona1Art.loadDemon(host, enemy.id, () -> {
                if (view != null) view.invalidate();
            });
        } else if (android.os.SystemClock.uptimeMillis() - lastBattle > 1500) {
            view.setUnits(readUnits(party, 0, PARTY_COUNT, PARTY_SIZE, false), null, false);
        }
    }

    private void watch() {
        host.setMemoryWatches(
            new int[] {FLAG, PARTY, UNITS, FACING, EXPLORED, DUNGEON_PTR, BLOCK_TABLE, dungeon != 0 ? dungeon : DUNGEON_PTR},
            new int[] {4, PARTY_SIZE * PARTY_COUNT, UNIT_SIZE * (PARTY_COUNT + ENEMY_COUNT), 4,
                MAP_BLOCK * MAP_BLOCK_COUNT, 4, BLOCK_TABLE_SIZE, DUNGEON_SIZE}
        );
    }

    private static final class MapState {
        final byte[] cells = new byte[MAP_BLOCK];
        int x, y;
        float angle;
        int[] walls, doors;
        boolean explored(int c, int r) {
            if (c < 0 || r < 0 || c >= MAP_SIZE || r >= MAP_SIZE) return false;
            return (c == x && r == y) || (cells[r * 3 + c / 8] & (0x80 >> (c % 8))) != 0;
        }
    }

    private MapState readMap() {
        byte[] facing = host.readMemoryWatch(3), explored = host.readMemoryWatch(4), ptr = host.readMemoryWatch(5);
        byte[] table = host.readMemoryWatch(6), d = host.readMemoryWatch(7);
        if (ptr == null) return null;
        int p = u32(ptr, 0);
        if (p != dungeon) {
            dungeon = p >= 0x08800000 && p < 0x0A000000 - DUNGEON_SIZE ? p : 0;
            watch();
            return null;
        }
        if (dungeon == 0 || facing == null || explored == null || table == null || d == null) return null;
        int id = u16(d, 0x46), floor = u16(d, 0x48);
        if (id >= BLOCK_TABLE_SIZE || floor > 15) return null;
        int block = (table[id] & 0xFF) + floor;
        MapState m = new MapState();
        m.angle = Float.intBitsToFloat(u32(facing, 0));
        m.x = d[0x2A] & 0xFF;
        m.y = d[0x2C] & 0xFF;
        if (block >= MAP_BLOCK_COUNT || m.x >= MAP_SIZE || m.y >= MAP_SIZE || Float.isNaN(m.angle)) return null;
        System.arraycopy(explored, block * MAP_BLOCK, m.cells, 0, MAP_BLOCK);
        Persona1Art.Dungeon dg = Persona1Art.dungeon(id);
        if (dg == null) Persona1Art.loadDungeon(host, id, () -> { if (view != null) view.invalidate(); });
        else if (floor < dg.walls.length) { m.walls = dg.walls[floor]; m.doors = dg.doors[floor]; }
        return m;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    private static final class Unit {
        String name;
        int id, level, hp, maxHp, sp, maxSp;
        boolean enemy;

        boolean same(Unit other) {
            return other != null && id == other.id && level == other.level
                && hp == other.hp && maxHp == other.maxHp && sp == other.sp
                && maxSp == other.maxSp && name.equals(other.name);
        }

        Bitmap portrait() {
            return enemy ? Persona1Art.demon(id) : Persona1Art.face(id);
        }
    }

    private static Unit[] readUnits(byte[] data, int first, int count, int size, boolean enemy) {
        Unit[] result = new Unit[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            int offset = (first + i) * size;
            Unit unit = new Unit();
            unit.hp = u32(data, offset);
            unit.maxHp = u32(data, offset + 4);
            unit.sp = u32(data, offset + 8);
            unit.maxSp = u32(data, offset + 0xC);
            if (unit.maxHp <= 0 || unit.maxHp > 9999 || unit.hp < 0 || unit.hp > unit.maxHp
                || unit.maxSp < 0 || unit.maxSp > 9999 || unit.sp < 0 || unit.sp > unit.maxSp) {
                continue;
            }
            unit.id = (data[offset + ID_OFFSET] & 0xFF) | ((data[offset + ID_OFFSET + 1] & 0xFF) << 8);
            unit.level = data[offset + LEVEL] & 0xFF;
            unit.name = decodeName(data, offset + NAME);
            unit.enemy = enemy;
            if (unit.name.isEmpty()) continue;
            result[used++] = unit;
        }
        return Arrays.copyOf(result, used);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static String decodeName(byte[] data, int offset) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i + 1 < NAME_LEN && offset + i + 1 < data.length; i += 2) {
            int ch = ((data[offset + i] & 0xFF) << 8) | (data[offset + i + 1] & 0xFF);
            if (ch == 0 || ch == 0xFFFF) break;
            if (ch == 0x8140) result.append(' ');
            else if (ch >= 0x101 && ch <= 0x11A) result.append((char) (ch - 0xA0));
            else if (ch >= 0xBF && ch < 0x100) result.append((char) (ch - 0x9F));
        }
        return result.toString().trim();
    }

    private static final class Persona1View extends View {
        private static final int BG = 0xFF292D2E;
        private static final int PANEL = 0xFF85898A;
        private static final int PANEL_LIGHT = 0xFFA5A7A5;
        private static final int PANEL_DARK = 0xFF292D2E;
        private static final int WHITE = 0xFFF3F1EA;
        private static final int BLACK = 0xFF151719;
        private static final int GREEN = 0xFF9CFF20;
        private static final int HP_COLOR = 0xFFB7D83D;
        private static final int SP_COLOR = 0xFF50D2E7;
        private static final int DIM_OVERLAY = 0x77565A5B;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint grayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path mapPath = new Path();
        private MapState map;
        private boolean showMap;
        private final Typeface font;
        private final Typeface boldFont;
        private Unit[] party = new Unit[0];
        private Unit[] enemies;
        private boolean battle;
        private int selected = 0;
        private boolean detailMode;
        private boolean showFullPortrait;
        private final Map<String, Bitmap> highResPortraits = new HashMap<>();
        private Unit fullPortraitUnit;
        private float scrollX;
        private float downX, downY, lastX;
        private boolean dragging;
        private boolean moved;
        private static final float MARGIN = 5f;
        private static final float GAP = 4f;
        private static final float CARD_W = 58f;
        private static final float CARD_TOP = 5f;
        private static final float CARD_H = 154f;
        private static final float PANEL_TOP = 177f;
        private static final float PANEL_BOTTOM = 235f;

        Persona1View(Context context) {
            super(context);
            Typeface loaded;
            try {
                loaded = Typeface.createFromAsset(context.getAssets(), "Fontsona4Golden.ttf");
            } catch (RuntimeException | Error ignored) {
                loaded = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            }
            font = loaded;
            boldFont = Typeface.create(loaded, Typeface.BOLD);
            loadHighResPortraits(context);
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0);
            grayPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
            setFocusable(true);
        }

        void setMap(MapState newMap) {
            map = newMap;
            invalidate();
        }

        void setUnits(Unit[] newParty, Unit[] newEnemies, boolean inBattle) {
            if (sameUnits(party, newParty) && sameUnits(enemies, newEnemies) && battle == inBattle) return;
            party = newParty;
            enemies = newEnemies;
            battle = inBattle;
            if (selected >= party.length) selected = party.length - 1;
            if (selected < -1) selected = -1;
            invalidate();
        }

        private static boolean sameUnits(Unit[] a, Unit[] b) {
            if (a == null || b == null) return a == b;
            if (a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) if (!a[i].same(b[i])) return false;
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float scale = h / 240f;
            canvas.save();
            canvas.scale(scale, scale);
            float logicalW = w / scale;
            canvas.drawColor(BG);
            drawBackground(canvas, logicalW, 240);
            if (showFullPortrait && fullPortraitUnit != null) {
                drawFullPortrait(canvas, logicalW, 240, fullPortraitUnit);
            } else if (showMap) {
                drawMapScreen(canvas, logicalW, 240);
            } else {
                drawCardsViewport(canvas, logicalW);
                drawInfoPanel(canvas, logicalW);
            }
            canvas.restore();
        }

        private void loadHighResPortraits(Context context) {
            String[] files = {"Persona1Naoya.png", "Persona1Maki.png", "Persona1Kei.png", "Persona1Masao.png",
                "Persona1Reiji.png", "Persona1Eriko.png", "Persona1Hidehiko.png", "Persona1Yuka.png", "Persona1Yukino.png"};
            for (String file : files) {
                try (java.io.InputStream in = context.getAssets().open(file)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) highResPortraits.put(file, bitmap);
                } catch (IOException ignored) { }
            }
        }

        private Bitmap getPortrait(Unit unit) {
            if (unit.enemy) return unit.portrait();
            String n = unit.name == null ? "" : unit.name.toLowerCase(Locale.ROOT);
            String file = null;
            if (n.contains("maki")) file = "Persona1Maki.png";
            else if (n.contains("nanjo") || n.contains("kei")) file = "Persona1Kei.png";
            else if (n.contains("mark") || n.contains("masao")) file = "Persona1Masao.png";
            else if (n.contains("reiji")) file = "Persona1Reiji.png";
            else if (n.contains("elly") || n.contains("eriko")) file = "Persona1Eriko.png";
            else if (n.contains("brown") || n.contains("hidehiko")) file = "Persona1Hidehiko.png";
            else if (n.contains("yuka")) file = "Persona1Yuka.png";
            else if (n.contains("yukino")) file = "Persona1Yukino.png";
            else if (n.contains("nao") || n.contains("hero") || n.contains("protagonist")) file = "Persona1Naoya.png";
            Bitmap bitmap = file == null ? null : highResPortraits.get(file);
            return bitmap != null ? bitmap : unit.portrait();
        }

        private void drawFullPortrait(Canvas c, float w, float h, Unit unit) {
            c.drawColor(0xFF101213);
            Bitmap bitmap = getPortrait(unit);
            if (bitmap != null) {
                // Show the entire supplied image without cropping or stretching.
                float availableW = Math.max(1f, w - 12f);
                float availableH = Math.max(1f, h - 30f);
                float scale = Math.min(availableW / bitmap.getWidth(), availableH / bitmap.getHeight());
                float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
                float left = (w - dw) / 2f;
                float top = 3f + (availableH - dh) / 2f;
                rect.set(left, top, left + dw, top + dh);
                c.drawBitmap(bitmap, null, rect, paint);
            }
            float bx = w - 49f, by = 5f;
            fill(c, bx, by, w - 5f, by + 17f, 0xFF353A3B);
            outline(c, bx, by, w - 5f, by + 17f, GREEN, 0.8f);
            text(c, "Close", (bx + w - 5f) / 2f, by + 11.5f, 7.5f, WHITE, true, Paint.Align.CENTER);
            text(c, unit.name, w / 2f, h - 5f, 8f, WHITE, true, Paint.Align.CENTER);
        }

        private void drawMapScreen(Canvas c, float w, float h) {
            c.drawColor(0xFF171A1B);
            text(c, "DUNGEON MAP", 9, 16, 10, 0xFFE0A030, true, Paint.Align.LEFT);
            if (map != null) {
                drawDungeonMap(c, map, 8, 22, w - 8, h - 8, 1f);
            } else {
                text(c, "Map unavailable outside a dungeon", w / 2f, h / 2f, 8, WHITE, true, Paint.Align.CENTER);
            }
            float bx = w - 42, by = 5;
            fill(c, bx, by, w - 5, by + 17, 0xFF353A3B);
            outline(c, bx, by, w - 5, by + 17, GREEN, 0.8f);
            text(c, "Close", (bx + w - 5) / 2f, by + 11.5f, 7.5f, WHITE, true, Paint.Align.CENTER);
        }

        private void drawDungeonMap(Canvas c, MapState mp, float l, float t, float r, float b, float u) {
            final int MAP_FLOOR = 0xFF5A4318, MAP_EDGE = 0xFFE0A030, MAP_GRID = 0xFF141414;
            final int PLAYER = 0xFFFFE680, DOOR = 0xFFFFD84A;
            final int[] SIDES = {Persona1Art.Dungeon.N, Persona1Art.Dungeon.E, Persona1Art.Dungeon.S, Persona1Art.Dungeon.W};
            float size = Math.min(r - l, b - t), cell = size / MAP_SIZE;
            float ox = (l + r - size) / 2f, oy = t + (b - t - size) / 2f;
            for (int y = 0; y < MAP_SIZE; y++) for (int x = 0; x < MAP_SIZE; x++) {
                float x0 = ox + x * cell, y0 = oy + y * cell;
                if (!mp.explored(x, y)) { fill(c, x0 + 1, y0 + 1, x0 + cell - 1, y0 + cell - 1, MAP_GRID); continue; }
                fill(c, x0, y0, x0 + cell, y0 + cell, MAP_FLOOR);
                float edge = Math.max(1.2f, 2.1f * u);
                for (int side = 0; side < 4; side++) {
                    int bit = SIDES[side];
                    int nx = x + (bit == Persona1Art.Dungeon.E ? 1 : bit == Persona1Art.Dungeon.W ? -1 : 0);
                    int ny = y + (bit == Persona1Art.Dungeon.S ? 1 : bit == Persona1Art.Dungeon.N ? -1 : 0);
                    boolean inside = nx >= 0 && ny >= 0 && nx < MAP_SIZE && ny < MAP_SIZE;
                    boolean wall, door = false;
                    if (mp.walls == null || mp.doors == null) wall = !mp.explored(nx, ny);
                    else {
                        int k = y * MAP_SIZE + x, nk = ny * MAP_SIZE + nx, opposite = SIDES[(side + 2) % 4];
                        door = (mp.doors[k] & bit) != 0 || (inside && (mp.doors[nk] & opposite) != 0);
                        wall = !door && ((mp.walls[k] & bit) != 0 || (inside && (mp.walls[nk] & opposite) != 0));
                    }
                    int color = door ? DOOR : MAP_EDGE;
                    if (wall || door) {
                        float inset = door ? cell * .22f : 0, thick = door ? edge * 1.8f : edge;
                        float x1 = x0 + cell, y1 = y0 + cell;
                        if (bit == Persona1Art.Dungeon.N) fill(c, x0 + inset, y0, x1 - inset, y0 + thick, color);
                        else if (bit == Persona1Art.Dungeon.S) fill(c, x0 + inset, y1 - thick, x1 - inset, y1, color);
                        else if (bit == Persona1Art.Dungeon.W) fill(c, x0, y0 + inset, x0 + thick, y1 - inset, color);
                        else fill(c, x1 - thick, y0 + inset, x1, y1 - inset, color);
                    }
                }
            }
            float px = ox + (mp.x + .5f) * cell, py = oy + (mp.y + .5f) * cell;
            c.save(); c.rotate(-mp.angle, px, py);
            mapPath.reset(); mapPath.moveTo(px, py - cell * .42f); mapPath.lineTo(px + cell * .34f, py + cell * .34f);
            mapPath.lineTo(px, py + cell * .16f); mapPath.lineTo(px - cell * .34f, py + cell * .34f); mapPath.close();
            paint.setColor(PLAYER); c.drawPath(mapPath, paint); c.restore();
        }

        private void drawBackground(Canvas c, float w, float h) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF303435);
            for (float y = 3; y < h; y += 6) {
                for (float x = ((int)(y * 2)) % 11; x < w; x += 11) c.drawCircle(x, y, 0.28f, paint);
            }
        }

        private void drawCardsViewport(Canvas c, float w) {
            c.save();
            c.clipRect(MARGIN, 0, w - MARGIN, PANEL_TOP - 3);
            float total = party.length * CARD_W + Math.max(0, party.length - 1) * GAP;
            float maxScroll = Math.max(0, total - (w - MARGIN * 2));
            scrollX = Math.max(0, Math.min(scrollX, maxScroll));
            for (int i = 0; i < party.length; i++) {
                float x = MARGIN + i * (CARD_W + GAP) - scrollX;
                drawCard(c, party[i], x, CARD_TOP, CARD_W, CARD_H, i == selected);
            }
            c.restore();
            if (maxScroll > 0) {
                float trackL = MARGIN, trackR = w - MARGIN, y = 173.2f;
                fill(c, trackL, y, trackR, y + 1.3f, 0xFF454A4B);
                float thumbW = (trackR - trackL) * ((trackR - trackL) / total);
                float thumbX = trackL + (trackR - trackL - thumbW) * (scrollX / maxScroll);
                fill(c, thumbX, y, thumbX + thumbW, y + 1.3f, 0xFFB9C0C0);
            }
        }

        private void drawCard(Canvas c, Unit unit, float x, float y, float w, float h, boolean active) {
            boolean down = unit.hp == 0;
            float right = x + w, bottom = y + h;
            fill(c, x, y, right, bottom, 0xFF111314);
            stroke(c, x + 0.7f, y + 0.7f, right - 0.7f, bottom - 0.7f, 0xFF6E7475, 0.7f);
            float left = x + 1.5f, innerR = right - 1.5f;
            fill(c, left, y + 1.5f, innerR, y + 17, active ? PANEL_LIGHT : 0xFF4A4D4E);
            text(c, unit.name, x + w / 2, y + 12, fit(unit.name, w - 8, 9), WHITE, true, Paint.Align.CENTER);

            drawStat(c, "HP", unit.hp, unit.maxHp, HP_COLOR, left, y + 18, innerR, 19);
            drawStat(c, "SP", unit.sp, unit.maxSp, SP_COLOR, left, y + 37, innerR, 19);

            float imageTop = y + 56, imageBottom = bottom - 1.5f;
            fill(c, left, imageTop, innerR, imageBottom, unit.enemy ? 0xFFE8DCD4 : 0xFFDCE6F0);
            Bitmap portrait = getPortrait(unit);
            if (portrait != null) {
                c.save();
                c.clipRect(left, imageTop, innerR, imageBottom);
                float iw = innerR - left, ih = imageBottom - imageTop;
                if (!unit.enemy && highResPortraits.containsValue(portrait)) {
                    // Use the upper half of the source art for the normal card.
                    // Mark's face sits toward the right side of his artwork, so use a
                    // right-aligned source crop instead of shifting the destination image
                    // (which was pushing his face farther out of frame).
                    int halfHeight = Math.max(1, portrait.getHeight() / 2);
                    boolean mark = unit.name != null && (unit.name.equalsIgnoreCase("MARK") || unit.name.equalsIgnoreCase("MASAO"));
                    int cropWidth = Math.min(portrait.getWidth(), Math.max(1, Math.round(halfHeight * (iw / ih))));
                    // Keep Mark's face near the center/right, but move the crop a little farther
                    // left from the previous framing so his face
                    // stays fully visible while retaining more of his body.
                    int cropLeft;
                    if (mark) {
                        int leftShift = Math.round(cropWidth * 0.34f);
                        cropLeft = Math.max(0, portrait.getWidth() - cropWidth - leftShift);
                    } else {
                        cropLeft = (portrait.getWidth() - cropWidth) / 2;
                    }
                    // Clamp the crop to the source bitmap bounds.
                    cropLeft = Math.min(cropLeft, portrait.getWidth() - cropWidth);
                    android.graphics.Rect src = new android.graphics.Rect(cropLeft, 0, cropLeft + cropWidth, halfHeight);
                    rect.set(left, imageTop, innerR, imageBottom);
                    c.drawBitmap(portrait, src, rect, down ? grayPaint : paint);
                } else {
                    float factor = unit.enemy
                        ? Math.min(iw * 0.92f / portrait.getWidth(), ih * 0.9f / portrait.getHeight())
                        : Math.max(iw / portrait.getWidth(), ih / portrait.getHeight());
                    float dw = portrait.getWidth() * factor, dh = portrait.getHeight() * factor;
                    if (unit.enemy) rect.set(left + (iw-dw)/2, imageTop + (ih-dh)/2, left + (iw+dw)/2, imageTop + (ih+dh)/2);
                    else rect.set(left + (iw-dw)/2, imageBottom-dh, left + (iw+dw)/2, imageBottom);
                    c.drawBitmap(portrait, null, rect, down ? grayPaint : paint);
                }
                c.restore();
            } else {
                text(c, unit.enemy ? "?" : initials(unit.name), x + w/2, imageTop + (imageBottom-imageTop)*0.55f,
                    20, 0xFF202426, true, Paint.Align.CENTER);
            }
            if (unit.level > 0) {
                fill(c, left, bottom - 14, left + 36, bottom, 0xD8101014);
                text(c, "Lv." + unit.level, left + 3, bottom - 4, 8, WHITE, true, Paint.Align.LEFT);
            }
            if (down) fill(c, x, y, right, bottom, 0x66707070);
            if (!active) fill(c, x, y, right, bottom, DIM_OVERLAY);
            else {
                outline(c, x - 1, y - 1, right + 1, bottom + 1, 0x339CFF20, 2.2f);
                outline(c, x - 0.2f, y - 0.2f, right + 0.2f, bottom + 0.2f, GREEN, 1.1f);
                outline(c, x + 1.2f, y + 1.2f, right - 1.2f, bottom - 1.2f, 0xFFCBFF85, 0.35f);
            }
        }

        private void drawStat(Canvas c, String label, int value, int max, int color, float l, float y, float r, float rowH) {
            fill(c, l, y, r, y + rowH, 0xFF1C2021);
            float labelW = 18;
            // SMT IV-style labels: colored letters with a dark outline, not colored boxes.
            outlineText(c, label, l + 3, y + rowH - 5, 7.2f, color, true, Paint.Align.LEFT);
            text(c, value + "/" + max, r - 3, y + 8, 6.8f, WHITE, false, Paint.Align.RIGHT);
            float barY = y + 11;
            float barH = 4;
            float barW = r - l - labelW - 6;
            float bx = l + labelW + 3;
            fill(c, bx, barY, bx + barW, barY + barH, 0xFF777D7E);
            float fraction = max <= 0 ? 0 : Math.max(0, Math.min(1, value / (float)max));
            if (fraction > 0) fill(c, bx, barY, bx + barW * fraction, barY + barH, color);
        }

        private void drawInfoPanel(Canvas c, float w) {
            float top = PANEL_TOP, bottom = PANEL_BOTTOM;
            fill(c, 5, top, w - 5, bottom, 0xFFD7D6D0);
            outline(c, 5, top, w - 5, bottom, 0xFFF0EEE7, 0.8f);
            paint.setColor(0x22FFFFFF);
            paint.setStrokeWidth(0.35f);
            for (float y = top + 4; y < bottom; y += 8) c.drawLine(7, y, w - 7, y, paint);
            if (selected < 0 || selected >= party.length) {
                text(c, "PARTY", 10, top + 14, 9, BLACK, true, Paint.Align.LEFT);
                text(c, "Tap a card to view status", 10, top + 30, 7.5f, 0xFF444747, false, Paint.Align.LEFT);
                return;
            }
            Unit unit = party[selected];
            text(c, "PARTY MEMBER  /  " + unit.name.toUpperCase() + "   LV. " + unit.level,
                10, top + 13, fit("PARTY MEMBER  /  " + unit.name.toUpperCase() + "   LV. " + unit.level, w - 20, 8.2f),
                BLACK, true, Paint.Align.LEFT);
            textSpaced(c, "HP  " + unit.hp + " / " + unit.maxHp, 10, top + 28, 7.8f, 0xFF444747, Paint.Align.LEFT);
            float barW = Math.min(w - 20, 142);
            drawPanelBar(c, 10, top + 31, barW, 3.2f, unit.hp, unit.maxHp, HP_COLOR);
            textSpaced(c, "SP  " + unit.sp + " / " + unit.maxSp, 10, top + 44, 7.8f, 0xFF444747, Paint.Align.LEFT);
            drawPanelBar(c, 10, top + 47, barW, 3.2f, unit.sp, unit.maxSp, SP_COLOR);
            float buttonW = 48f, buttonH = 17f;
            float fullX = w - buttonW - 32f - 5f - 11f, by = top + 19f;
            float mapW = 32f, mapX = fullX + buttonW + 5f;
            fill(c, fullX, by, fullX + buttonW, by + buttonH, 0xFF353A3B);
            outline(c, fullX, by, fullX + buttonW, by + buttonH, GREEN, 0.8f);
            text(c, "Full portrait", fullX + buttonW / 2f, by + 11.5f, 6.8f, WHITE, true, Paint.Align.CENTER);
            fill(c, mapX, by, mapX + mapW, by + buttonH, 0xFF353A3B);
            outline(c, mapX, by, mapX + mapW, by + buttonH, 0xFFE0A030, 0.8f);
            textSpaced(c, "Map", mapX + mapW / 2f, by + 11.5f, 6.8f, WHITE, Paint.Align.CENTER);
        }

        private void drawPanelBar(Canvas c, float x, float y, float width, float height, int value, int max, int color) {
            fill(c, x, y, x + width, y + height, 0xFF777D7E);
            float f = max <= 0 ? 0 : Math.max(0, Math.min(1, value / (float)max));
            if (f > 0) fill(c, x, y, x + width * f, y + height, color);
        }


        private void textSpaced(Canvas c, String value, float x, float y, float size, int color, Paint.Align align) {
            // Keep the Persona 4 Golden typeface but draw glyphs separately to prevent
            // overlapping number pairs in the bottom status panel.
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(font);
            paint.setTextSize(size);
            float total = 0;
            for (int i = 0; i < value.length(); i++) total += paint.measureText(value.substring(i, i + 1)) + 0.75f;
            float cursor = x;
            if (align == Paint.Align.RIGHT) cursor -= total;
            else if (align == Paint.Align.CENTER) cursor -= total / 2f;
            for (int i = 0; i < value.length(); i++) {
                String ch = value.substring(i, i + 1);
                c.drawText(ch, cursor, y, paint);
                cursor += paint.measureText(ch) + 0.75f;
            }
        }

        private void outlineText(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setShader(null);
            paint.setStrokeWidth(1.25f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFF101314);
            paint.setTypeface(bold ? boldFont : font);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            c.drawText(value, x, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawText(value, x, y, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private float fit(String value, float maxWidth, float size) {
            paint.setTypeface(font);
            paint.setTextSize(size);
            float measured = paint.measureText(value);
            return measured <= maxWidth ? size : Math.max(5, size * maxWidth / Math.max(1, measured));
        }

        private String initials(String name) {
            return name.length() <= 2 ? name.toUpperCase() : name.substring(0, 2).toUpperCase();
        }

        private void fill(Canvas c, float l, float t, float r, float b, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha((color >>> 24) & 0xFF);
            c.drawRect(l, t, r, b, paint);
            paint.setAlpha(255);
        }

        private void stroke(Canvas c, float l, float t, float r, float b, int color, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            c.drawRect(l, t, r, b, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void outline(Canvas c, float l, float t, float r, float b, int color, float width) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            rect.set(l, t, r, b);
            c.drawRect(rect, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void textPlain(Canvas c, String value, float x, float y, float size, int color, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(size);
            paint.setTextAlign(align);
            c.drawText(value, x, y, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setTypeface(bold ? boldFont : font);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            c.drawText(value, x, y, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float scale = getHeight() / 240f;
            if (scale <= 0) return true;
            float x = event.getX() / scale;
            float y = event.getY() / scale;
            float w = getWidth() / scale;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = x;
                    downY = y;
                    dragging = true;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    float dx = x - lastX;
                    if (Math.abs(x - downX) > 4) moved = true;
                    if (moved && downY < PANEL_TOP - 2) {
                        float total = party.length * CARD_W + Math.max(0, party.length - 1) * GAP;
                        float maxScroll = Math.max(0, total - (w - MARGIN * 2));
                        scrollX = Math.max(0, Math.min(maxScroll, scrollX - dx));
                        invalidate();
                    }
                    lastX = x;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) return true;
                    dragging = false;
                    if (moved) return true;
                    if (showFullPortrait) {
                        showFullPortrait = false;
                        fullPortraitUnit = null;
                        invalidate();
                        return true;
                    }
                    if (showMap) {
                        showMap = false;
                        invalidate();
                        return true;
                    }
                    if (y >= CARD_TOP && y <= CARD_TOP + CARD_H && x >= MARGIN && x <= w - MARGIN) {
                        int index = (int)((x + scrollX - MARGIN) / (CARD_W + GAP));
                        float within = (x + scrollX - MARGIN) % (CARD_W + GAP);
                        if (index >= 0 && index < party.length && within <= CARD_W) {
                            if (selected == index) {
                                if (detailMode) detailMode = false;
                                else selected = -1;
                            } else {
                                selected = index;
                                detailMode = false;
                            }
                            invalidate();
                            return true;
                        }
                    }
                    if (y >= PANEL_TOP && y <= PANEL_BOTTOM) {
                        if (selected >= 0) {
                            float buttonW = 48f;
                            float buttonY = PANEL_TOP + 19f;
                            float fullLeft = w - buttonW - 32f - 5f - 11f;
                            float mapLeft = fullLeft + buttonW + 5f;
                            if (y >= buttonY && y <= buttonY + 17f && x >= fullLeft && x <= fullLeft + buttonW) {
                                fullPortraitUnit = party[selected];
                                showFullPortrait = true;
                            } else if (y >= buttonY && y <= buttonY + 17f && x >= mapLeft && x <= mapLeft + 32f) {
                                showMap = true;
                            } else {
                                detailMode = !detailMode;
                            }
                        }
                        invalidate();
                        return true;
                    }
                    if (selected >= 0) {
                        selected = -1;
                        detailMode = false;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
            }
            return true;
        }
    }
}
