package org.ppsspp.ppsspp.duo.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Yu-Gi-Oh! GX Tag Force: the card under the cursor in full, both players' Life Points and the
// phase, drawn with the game's own card frame, icons, panels and digits.
//
// Duel state, found on ULES00600 v1.01 (the duel modules are loaded at the same addresses in every
// duel, so these are fixed):
// - 0x09E4D1A0 (modduel_draw data): the card the info panel shows. +0x00 the card's Konami ID,
//   +0x14 ATK, +0x18 DEF, +0x1C race, +0x20 attribute, +0x24 level.
// - 0x09E6418C / 0x09E64190: your and the opponent's Life Points (the HUD follows a write to them;
//   the two words before are the starting LP).
// - 0x09E641AC: whose turn it is (0 you, 1 the opponent); 0x09E641B8: the phase (0 DP, 1 SP,
//   2 MP1, 3 BP, 4 MP2, 5 EP; only DP and MP1 were seen). The turn number isn't known yet.
//
// Card data, read from the disc at runtime. EHP archives: u32 count at +12, then name offset /
// data offset pairs from +16, each name followed by the file's u32 size.
// - duelsys/cardinfo_eng.ehp :: CARD_Prop.bin: 8 bytes per card, indexed by the internal card ID.
//   u16 Konami ID (the top bits are flags); byte 4: attribute in the high nibble (1 LIGHT, 2 DARK,
//   3 WATER, 4 FIRE, 5 EARTH, 6 WIND, 7 DIVINE, 8 spell, 9 trap), kind in the low one (0 normal,
//   1 effect, 2 fusion, 4 ritual); byte 5: level in the low nibble; race = (byte 6 & 15) << 1 |
//   byte 5 >> 7, in Konami's usual order (1 Dragon ... 14 Aqua, 15 Warrior ...).
// - CARD_Indx_E.bin: per internal ID, u32 offsets into CARD_Name_E.bin and CARD_Desc_E.bin
//   (UTF-16LE, zero terminated).
// - card/cardh_e.cip: u32 sector (0x800 bytes) per internal ID - 1 from +0x30; 0 means no picture,
//   bit 31 a list of alternative pictures at that file offset (the first is used). A picture is a
//   0x80-byte GIM header and a 128x64 DXT1 atlas (PSP block layout: 4 index bytes, then two RGB565
//   colours). The slots are 0x1000 apart, so the atlas' last 0x80 bytes are lost to the next
//   header; nothing of the artwork is there. The 78x78 artwork is (0,0)-(78,60) on top,
//   (80,0)-(128,18) below on the left and (80,20)-(110,38) below on the right. Next to it is the
//   name plate, which isn't used.
// - duel/basic_be.ehp: the HUD's GIMs. card_frame_all (the card frame in four parts: top, the two
//   sides lying down, bottom), card_icon01 (attribute icons, level star), lp_pnl_a01 (ATK/DEF
//   panel), lp_pnl_nmb01_a (gold digits), phase_icon01, score-top100001/2 (the players' bars),
//   card_spec_bg (the card detail background).
public final class TagForceMod extends DuoMod {
	public static final String ID = "tag_force";
	private static final String TAG = "PPSSPPDuo";

	private static final String[] GAME_IDS = {"ULES00600"};

	private static final String EHP = "disc0:/PSP_GAME/USRDIR/duelsys/cardinfo_eng.ehp";
	private static final String CIP = "disc0:/PSP_GAME/USRDIR/card/cardh_e.cip";
	private static final String HUD = "disc0:/PSP_GAME/USRDIR/duel/basic_be.ehp";

	private static final int INFO_ADDR = 0x09E4D1A0;
	private static final int INFO_SIZE = 0x28;
	private static final int STATE_ADDR = 0x09E64180;
	private static final int STATE_SIZE = 0x3C;
	private static final int W_INFO = 0;
	private static final int W_STATE = 1;

	private static final int MAX_LP = 99999;
	private static final int ID_MASK = 0x1FFF;
	private static final int CACHE_SIZE = 32;

	private static final String[] RACES = {
		"", "Dragon", "Zombie", "Fiend", "Pyro", "Sea Serpent", "Rock", "Machine", "Fish", "Dinosaur",
		"Insect", "Beast", "Beast-Warrior", "Plant", "Aqua", "Warrior", "Winged Beast", "Fairy",
		"Spellcaster", "Thunder", "Reptile"};

	// HUD textures, in the order they're read.
	private static final String[] HUD_FILES = {
		"card_frame_all.gim", "card_icon01.gim", "lp_pnl_a01.gim", "lp_pnl_nmb01_a.gim",
		"phase_icon01.gim", "score-top100001.gim", "score-top100002.gim", "card_spec_bg.gim"};
	private static final int T_FRAME = 0, T_ICON = 1, T_PANEL = 2, T_DIGITS = 3, T_PHASE = 4, T_BLUE = 5, T_RED = 6, T_BG = 7;

	private static final int KIND_NORMAL = 0, KIND_EFFECT = 1, KIND_FUSION = 2, KIND_RITUAL = 3, KIND_SPELL = 4, KIND_TRAP = 5;

	private static final class Card {
		final int id;
		int attr, kind, level, race;
		String name = "";
		String text = "";
		Bitmap art;
		boolean done;

		Card(int id) {
			this.id = id;
		}

		boolean monster() {
			return kind != KIND_SPELL && kind != KIND_TRAP;
		}
	}

	private DuoModContext host;
	private TagForceView view;
	private int generation;
	private boolean watching;

	// Disc tables.
	private int idxOff, nameOff, descOff, propOff, nameSize, descSize, propSize;
	private boolean headerRead;
	private byte[] prop;
	private int[] internalId;
	private long retryAt;

	// HUD art, shared by every view of this mod.
	private int[] hudOffsets, hudSizes;
	private final Bitmap[] hud = new Bitmap[HUD_FILES.length];
	private int hudNext;
	private boolean hudFailed;

	private final Map<Integer, Card> cache = new LinkedHashMap<Integer, Card>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Entry<Integer, Card> eldest) {
			return size() > CACHE_SIZE;
		}
	};
	private int wantedId;
	private boolean busy;

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_tagforce);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_tagforce_desc);
	}

	@Override
	public int getPriority(DuoStatus s) {
		boolean match = Arrays.asList(GAME_IDS).contains(s.gameId)
			|| s.title.replaceAll("\\s+", " ").trim().equalsIgnoreCase("Yu-Gi-Oh! GX Tag Force");
		return match ? 100 : -1;
	}

	@Override
	public long getStatusIntervalMs() {
		return 100;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		generation++;
		view = new TagForceView(host.getContext(), hud);
		watching = false;
		busy = false;
		wantedId = 0;
		retryAt = 0;
		return view;
	}

	@Override
	public void onDestroyView() {
		generation++;
		view = null;
		host = null;
	}

	private static int u32(ByteBuffer b, int o) {
		return b.getInt(o);
	}

	@Override
	public void onStatus(DuoStatus s) {
		if (!s.isInGame() && !s.isPauseMenu()) {
			view.setDuel(null);
			return;
		}
		pump();
		if (!watching) {
			watching = host.setMemoryWatches(new int[] {INFO_ADDR, STATE_ADDR}, new int[] {INFO_SIZE, STATE_SIZE});
			return;
		}
		byte[] info = host.readMemoryWatch(W_INFO);
		byte[] state = host.readMemoryWatch(W_STATE);
		if (info == null || state == null) {
			return;
		}
		ByteBuffer ib = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer sb = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
		int lpYou = u32(sb, 0x0C), lpOpp = u32(sb, 0x10);
		int side = u32(sb, 0x2C), phase = u32(sb, 0x38);
		if (lpYou < 0 || lpYou > MAX_LP || lpOpp < 0 || lpOpp > MAX_LP || lpYou + lpOpp == 0 || (side != 0 && side != 1)) {
			view.setDuel(null);
			return;
		}
		Duel d = new Duel();
		d.lpYou = lpYou;
		d.lpOpp = lpOpp;
		d.oppTurn = side == 1;
		d.phase = phase >= 0 && phase < 6 ? phase : -1;
		int id = u32(ib, 0);
		d.atk = u32(ib, 0x14);
		d.def = u32(ib, 0x18);
		d.cardId = id;
		if (id > 0 && id <= ID_MASK) {
			wantedId = id;
			d.card = cache.get(id);
		}
		d.hudGeneration = hudNext;
		view.setDuel(d);
	}

	// ---- Disc reads, one at a time.

	private interface Next {
		void run(byte[] data);
	}

	private void read(String path, int offset, int size, Next next) {
		busy = true;
		final int gen = generation;
		boolean queued = host.readGameFile(path, offset, size, data -> {
			if (gen != generation) {
				return;
			}
			busy = false;
			next.run(data);
		});
		if (!queued) {
			busy = false;
		}
	}

	private void fail(String what) {
		Log.w(TAG, "Tag Force: " + what);
		retryAt = SystemClock.uptimeMillis() + 3000;
	}

	private void pump() {
		if (busy || host == null || SystemClock.uptimeMillis() < retryAt) {
			return;
		}
		// Nothing is being read, so a card that isn't done lost its read (the mod was switched away
		// from, or the request wasn't queued): read it again.
		for (Iterator<Card> it = cache.values().iterator(); it.hasNext(); ) {
			if (!it.next().done) {
				it.remove();
			}
		}
		if (!headerRead) {
			readHeader();
		} else if (prop == null) {
			readProp();
		} else if (wantedId != 0 && cache.get(wantedId) == null) {
			Card c = new Card(wantedId);
			cache.put(wantedId, c);
			loadCard(c);
		} else if (!hudFailed && hudNext < HUD_FILES.length) {
			// The HUD art comes after the first card, so the text shows up quickly.
			readHud();
		}
	}

	// Entries of an EHP archive's header: name -> {offset, size}.
	private static Map<String, int[]> ehpEntries(byte[] data) {
		Map<String, int[]> out = new LinkedHashMap<>();
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		int n = u32(b, 12);
		for (int i = 0; i < n && 24 + i * 8 <= data.length; i++) {
			int no = u32(b, 16 + i * 8), off = u32(b, 20 + i * 8);
			if (no < 0 || no >= data.length) {
				continue;
			}
			int e = no;
			while (e < data.length && data[e] != 0) {
				e++;
			}
			if (e + 5 > data.length) {
				continue;
			}
			out.put(new String(data, no, e - no, StandardCharsets.US_ASCII), new int[] {off, u32(b, e + 1)});
		}
		return out;
	}

	private void readHeader() {
		read(EHP, 0, 0x200, data -> {
			if (data == null || data.length < 0x20) {
				fail("cardinfo_eng.ehp header unreadable");
				return;
			}
			Map<String, int[]> e = ehpEntries(data);
			int[] idx = e.get("CARD_Indx_E.bin"), name = e.get("CARD_Name_E.bin"), desc = e.get("CARD_Desc_E.bin"), pr = e.get("CARD_Prop.bin");
			if (idx == null || name == null || desc == null || pr == null || pr[1] <= 0 || pr[1] > 0x40000) {
				fail("cardinfo_eng.ehp has no card tables");
				return;
			}
			idxOff = idx[0];
			nameOff = name[0];
			nameSize = name[1];
			descOff = desc[0];
			descSize = desc[1];
			propOff = pr[0];
			propSize = pr[1];
			headerRead = true;
		});
	}

	private void readProp() {
		read(EHP, propOff, propSize, data -> {
			if (data == null || data.length < 16) {
				fail("CARD_Prop.bin unreadable");
				return;
			}
			int[] map = new int[ID_MASK + 1];
			for (int i = 1; i * 8 + 8 <= data.length; i++) {
				int kid = ((data[i * 8] & 0xFF) | (data[i * 8 + 1] & 0xFF) << 8) & ID_MASK;
				if (kid != 0 && map[kid] == 0) {
					map[kid] = i;
				}
			}
			internalId = map;
			prop = data;
			Log.i(TAG, "Tag Force: " + data.length / 8 + " cards in CARD_Prop.bin");
		});
	}

	private void readHud() {
		if (hudOffsets == null) {
			read(HUD, 0, 0x2000, data -> {
				if (data == null || data.length < 0x20) {
					hudFailed = true;
					Log.w(TAG, "Tag Force: basic_be.ehp unreadable, plain look");
					return;
				}
				Map<String, int[]> e = ehpEntries(data);
				hudOffsets = new int[HUD_FILES.length];
				hudSizes = new int[HUD_FILES.length];
				for (int i = 0; i < HUD_FILES.length; i++) {
					int[] f = e.get(HUD_FILES[i]);
					if (f == null || f[1] <= 0 || f[1] > 0x100000) {
						hudFailed = true;
						Log.w(TAG, "Tag Force: no " + HUD_FILES[i] + " in basic_be.ehp, plain look");
						return;
					}
					hudOffsets[i] = f[0];
					hudSizes[i] = f[1];
				}
			});
			return;
		}
		final int i = hudNext;
		read(HUD, hudOffsets[i], hudSizes[i], data -> {
			Bitmap bmp = data != null ? GimImage.decode(data) : null;
			if (bmp == null) {
				Log.w(TAG, "Tag Force: " + HUD_FILES[i] + " didn't decode");
			}
			hud[i] = bmp;
			hudNext = i + 1;
			if (view != null) {
				view.hudChanged();
			}
		});
	}

	private void loadCard(Card c) {
		int in = c.id < internalId.length ? internalId[c.id] : 0;
		if (in == 0) {
			c.name = String.format(Locale.US, "#%d", c.id);
			c.done = true;
			return;
		}
		int b4 = prop[in * 8 + 4] & 0xFF, b5 = prop[in * 8 + 5] & 0xFF, b6 = prop[in * 8 + 6] & 0xFF;
		c.attr = b4 >> 4;
		if (c.attr == 8) {
			c.kind = KIND_SPELL;
		} else if (c.attr == 9) {
			c.kind = KIND_TRAP;
		} else {
			switch (b4 & 15) {
			case 0: c.kind = KIND_NORMAL; break;
			case 2: c.kind = KIND_FUSION; break;
			case 4: c.kind = KIND_RITUAL; break;
			default: c.kind = KIND_EFFECT; break;
			}
			c.level = b5 & 15;
			c.race = (b6 & 15) << 1 | b5 >> 7;
		}
		// Offsets of the name and the description.
		read(EHP, idxOff + in * 8, 8, idx -> {
			if (idx == null || idx.length < 8) {
				cache.remove(c.id);
				fail("card index unreadable");
				return;
			}
			ByteBuffer b = ByteBuffer.wrap(idx).order(ByteOrder.LITTLE_ENDIAN);
			int nameRel = u32(b, 0), descRel = u32(b, 4);
			read(EHP, nameOff + nameRel, Math.max(2, Math.min(160, nameSize - nameRel)), nameData -> {
				c.name = utf16(nameData);
				read(EHP, descOff + descRel, Math.max(2, Math.min(2048, descSize - descRel)), descData -> {
					c.text = utf16(descData);
					loadArt(c, in);
				});
			});
		});
	}

	private void loadArt(Card c, int internal) {
		read(CIP, 0x30 + (internal - 1) * 8, 8, entry -> {
			if (entry == null || entry.length < 4) {
				c.done = true;
				return;
			}
			int sector = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
			if (sector < 0) {
				// Alternative pictures: a list at this file offset, the first is the card's own.
				read(CIP, sector & 0x7FFFFFFF, 4, list -> {
					int first = list == null || list.length < 4 ? 0 : ByteBuffer.wrap(list).order(ByteOrder.LITTLE_ENDIAN).getInt(0) & 0x7FFFFFFF;
					readPicture(c, first);
				});
			} else {
				readPicture(c, sector);
			}
		});
	}

	private void readPicture(Card c, int sector) {
		if (sector <= 0) {
			c.done = true;
			return;
		}
		read(CIP, sector * 0x800, 0x1000, data -> {
			if (data != null && data.length >= 0x1000) {
				c.art = artwork(data);
			}
			c.done = true;
		});
	}

	private static String utf16(byte[] data) {
		if (data == null) {
			return "";
		}
		int n = 0;
		while (n + 1 < data.length && (data[n] != 0 || data[n + 1] != 0)) {
			n += 2;
		}
		return new String(data, 0, n, StandardCharsets.UTF_16LE).replace("\r", "");
	}

	// The 78x78 artwork out of a cardh_e.cip slot (see the class comment).
	private static Bitmap artwork(byte[] slot) {
		final int aw = 128, ah = 64;
		byte[] dxt = Arrays.copyOfRange(slot, 0x80, 0x80 + aw * ah / 2);  // zero padded past the slot
		int[] atlas = decodeDxt1(dxt, aw, ah);
		int[] px = new int[78 * 78];
		copy(atlas, aw, 0, 0, 78, 60, px, 78, 0, 0);
		copy(atlas, aw, 80, 0, 48, 18, px, 78, 0, 60);
		copy(atlas, aw, 80, 20, 30, 18, px, 78, 48, 60);
		return Bitmap.createBitmap(px, 78, 78, Bitmap.Config.ARGB_8888);
	}

	private static void copy(int[] src, int sw, int sx, int sy, int w, int h, int[] dst, int dw, int dx, int dy) {
		for (int y = 0; y < h; y++) {
			System.arraycopy(src, (sy + y) * sw + sx, dst, (dy + y) * dw + dx, w);
		}
	}

	private static int rgb565(int v) {
		int r = ((v >> 11) & 31) * 255 / 31, g = ((v >> 5) & 63) * 255 / 63, b = (v & 31) * 255 / 31;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int mix(int a, int b, int wa, int wb) {
		int d = wa + wb;
		int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / d;
		int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / d;
		int bl = ((a & 255) * wa + (b & 255) * wb) / d;
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}

	// DXT1 as the PSP stores it: per 4x4 block, four rows of 2-bit indices, then the two colours
	// (RGB565, red in the high bits).
	private static int[] decodeDxt1(byte[] d, int w, int h) {
		int[] px = new int[w * h];
		int bw = w / 4;
		int[] pal = new int[4];
		for (int by = 0; by < h / 4; by++) {
			for (int bx = 0; bx < bw; bx++) {
				int o = (by * bw + bx) * 8;
				int v0 = (d[o + 4] & 0xFF) | (d[o + 5] & 0xFF) << 8;
				int v1 = (d[o + 6] & 0xFF) | (d[o + 7] & 0xFF) << 8;
				int a = rgb565(v0), b = rgb565(v1);
				pal[0] = a;
				pal[1] = b;
				if (v0 > v1) {
					pal[2] = mix(a, b, 2, 1);
					pal[3] = mix(a, b, 1, 2);
				} else {
					pal[2] = mix(a, b, 1, 1);
					pal[3] = 0xFF000000;
				}
				for (int y = 0; y < 4; y++) {
					int row = d[o + y] & 0xFF;
					for (int x = 0; x < 4; x++) {
						px[(by * 4 + y) * w + bx * 4 + x] = pal[(row >> (2 * x)) & 3];
					}
				}
			}
		}
		return px;
	}

	// ---- View

	private static final class Duel {
		int lpYou, lpOpp, phase, atk, def, cardId, hudGeneration;
		boolean oppTurn;
		Card card;

		String signature() {
			Card c = card;
			return lpYou + "/" + lpOpp + "/" + phase + "/" + oppTurn + "/" + atk + "/" + def + "/" + cardId
				+ "/" + (c != null && c.done ? (c.art != null ? 2 : 1) : 0) + "/" + hudGeneration;
		}
	}

	// Drawn in a 1240x1080 design space (the AYN Thor's second screen), scaled to fit.
	private static final class TagForceView extends View {
		private static final float DW = 1240, DH = 1080;
		private static final int COLOR_YOU = 0xFF6FB6F2;
		private static final int COLOR_OPP = 0xFFF08A8A;
		private static final int COLOR_GOLD = 0xFFFFE458;

		// Frame colours per kind (KIND_*); normal monsters use the texture as it is.
		private static final int[] KIND_TINT = {0, 0xFFD9772E, 0xFF9A6BC9, 0xFF5D86D6, 0xFF2FA88F, 0xFFC0508F};

		private final Bitmap[] hud;
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final Paint tinted = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
		private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
		private final Rect src = new Rect();
		private final RectF dst = new RectF();
		private Bitmap[] frameParts;
		private final ColorMatrixColorFilter[] tints = new ColorMatrixColorFilter[KIND_TINT.length];
		private Duel duel;
		private String signature = "";

		TagForceView(Context context, Bitmap[] hud) {
			super(context);
			this.hud = hud;
			setBackgroundColor(0xFF0B1630);
			for (int k = 1; k < KIND_TINT.length; k++) {
				// Grey of the gold frame, times the kind's colour, a bit brighter.
				int c = KIND_TINT[k];
				float r = ((c >> 16) & 255) / 255f * 1.45f, g = ((c >> 8) & 255) / 255f * 1.45f, b = (c & 255) / 255f * 1.45f;
				ColorMatrix m = new ColorMatrix(new float[] {
					0.30f * r, 0.59f * r, 0.11f * r, 0, 0,
					0.30f * g, 0.59f * g, 0.11f * g, 0, 0,
					0.30f * b, 0.59f * b, 0.11f * b, 0, 0,
					0, 0, 0, 1, 0});
				tints[k] = new ColorMatrixColorFilter(m);
			}
		}

		void hudChanged() {
			signature = "";
			invalidate();
		}

		void setDuel(Duel d) {
			String sig = d == null ? "" : d.signature();
			if (d == null && duel == null) {
				return;
			}
			if (!sig.equals(signature)) {
				signature = sig;
				duel = d;
				invalidate();
			}
		}

		private Bitmap t(int i) {
			return hud[i];
		}

		private void blit(Canvas canvas, Bitmap b, int sx, int sy, int sw, int sh, float x, float y, float w, float h, Paint p) {
			src.set(sx, sy, sx + sw, sy + sh);
			dst.set(x, y, x + w, y + h);
			canvas.drawBitmap(b, src, dst, p);
		}

		// Outlined text, like the game's.
		private void outlined(Canvas canvas, String s, float x, float y, float size, int color, Paint.Align align) {
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextAlign(align);
			paint.setTextSize(size);
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(size * 0.16f);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setColor(0xFF000000);
			canvas.drawText(s, x, y, paint);
			paint.setStyle(Paint.Style.FILL);
			paint.setColor(color);
			canvas.drawText(s, x, y, paint);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float w = getWidth(), h = getHeight();
			float s = Math.min(w / DW, h / DH);
			canvas.save();
			canvas.translate((w - DW * s) / 2, (h - DH * s) / 2);
			canvas.scale(s, s);
			if (t(T_BG) != null) {
				paint.setAlpha(150);
				blit(canvas, t(T_BG), 0, 0, t(T_BG).getWidth(), t(T_BG).getHeight(), -(w / s - DW) / 2, -(h / s - DH) / 2, w / s, h / s, paint);
				paint.setAlpha(255);
			}
			if (duel == null) {
				outlined(canvas, getContext().getString(R.string.duo_tagforce_idle), DW / 2, DH / 2, 40, 0xFFFFFFFF, Paint.Align.CENTER);
			} else {
				drawPlayers(canvas);
				drawPhases(canvas, 200);
				drawCardArea(canvas, 340);
			}
			canvas.restore();
		}

		// ---- Life Points

		private float digitsWidth(String n, float h) {
			return n.length() * 17 * h / 37 + h / 37;
		}

		private void digits(Canvas canvas, String n, float x, float y, float h) {
			Bitmap d = t(T_DIGITS);
			if (d == null) {
				outlined(canvas, n, x, y + h * 0.85f, h, COLOR_GOLD, Paint.Align.LEFT);
				return;
			}
			float k = h / 37;
			for (int i = 0; i < n.length(); i++) {
				int v = n.charAt(i) - '0';
				if (v < 0 || v > 9) {
					v = 10;  // '?'
				}
				blit(canvas, d, 18 * v, 0, 18, 37, x + i * 17 * k, y, 18 * k, h, paint);
			}
		}

		private void drawPlayers(Canvas canvas) {
			Context ctx = getContext();
			float barW = 560, barH = 91, y = 30;
			// Your bar on the left, the opponent's mirrored on the right, as on the top screen.
			if (t(T_BLUE) != null) {
				blit(canvas, t(T_BLUE), 0, 0, 166, 27, 30, y, barW, barH, paint);
			}
			if (t(T_RED) != null) {
				canvas.save();
				canvas.scale(-1, 1, DW / 2, 0);
				blit(canvas, t(T_RED), 0, 0, 166, 27, 30, y, barW, barH, paint);
				canvas.restore();
			}
			String you = String.valueOf(duel.lpYou), opp = String.valueOf(duel.lpOpp);
			float dh = 74;
			outlined(canvas, "LP", 56, y + 62, 42, COLOR_GOLD, Paint.Align.LEFT);
			digits(canvas, you, 130, y + 6, dh);
			outlined(canvas, "LP", DW - 56, y + 62, 42, COLOR_GOLD, Paint.Align.RIGHT);
			digits(canvas, opp, DW - 130 - digitsWidth(opp, dh), y + 6, dh);

			outlined(canvas, ctx.getString(R.string.duo_tagforce_you), 44, y + barH + 38, 28, COLOR_YOU, Paint.Align.LEFT);
			outlined(canvas, ctx.getString(R.string.duo_tagforce_opponent), DW - 44, y + barH + 38, 28, COLOR_OPP, Paint.Align.RIGHT);
			String turn = ctx.getString(duel.oppTurn ? R.string.duo_tagforce_opp_turn : R.string.duo_tagforce_your_turn);
			outlined(canvas, turn, DW / 2, y + barH + 38, 32, duel.oppTurn ? COLOR_OPP : COLOR_YOU, Paint.Align.CENTER);
		}

		// ---- Phases

		private static final String[] PHASES = {"DP", "SP", "MP1", "BP", "MP2", "EP"};

		private void drawPhases(Canvas canvas, float y) {
			Bitmap p = t(T_PHASE);
			float size = 76, left = 30, right = DW - 30;
			float gap = (right - left - 6 * size) / 5;
			for (int i = 0; i < 6; i++) {
				float x = left + i * (size + gap);
				boolean on = i == duel.phase;
				if (p != null) {
					paint.setFilterBitmap(false);
					blit(canvas, p, 20 * i, on ? 0 : 32, 19, 19, x, y, size, size, paint);
					blit(canvas, p, 20 * i, on ? 21 : 53, 20, 11, x - 2, y + size + 4, 80, 44, paint);
					if (i < 5) {
						blit(canvas, p, 120, 0, 7, 11, x + size + gap / 2 - 10, y + 22, 21, 33, paint);
					}
					paint.setFilterBitmap(true);
				} else {
					dst.set(x, y, x + size, y + size + 48);
					paint.setColor(on ? DuoUi.COLOR_ACCENT : DuoUi.COLOR_SURFACE);
					canvas.drawRoundRect(dst, 10, 10, paint);
					paint.setColor(0xFFFFFFFF);
					outlined(canvas, PHASES[i], x + size / 2, y + size / 2 + 20, 28, 0xFFFFFFFF, Paint.Align.CENTER);
				}
			}
		}

		// ---- The card

		private void drawCardArea(Canvas canvas, float top) {
			Context ctx = getContext();
			Card c = duel.card;
			float bottom = DH - 24;
			float cardH = bottom - top, cardW = cardH * 108 / 154;
			float left = 30;
			if (c == null || !c.done) {
				outlined(canvas, ctx.getString(duel.cardId == 0 ? R.string.duo_tagforce_no_card : R.string.duo_tagforce_loading),
					DW / 2, top + cardH / 2, 34, 0xFFFFFFFF, Paint.Align.CENTER);
				return;
			}
			drawCard(canvas, c, left, top, cardW / 108);

			float x = left + cardW + 30, right = DW - 30;
			// The ATK/DEF panel at the bottom, level with the card's bottom, and the text right above
			// it.
			float textBottom = bottom;
			if (c.monster()) {
				float statsTop = bottom - STATS_H;
				drawStats(canvas, c, x, right, statsTop);
				textBottom = statsTop - 26;
			}
			// Name, like the info panel: yellow, outlined.
			float nameSize = 44;
			paint.setTypeface(Typeface.DEFAULT_BOLD);
			paint.setTextSize(nameSize);
			String name = c.name.toUpperCase(Locale.US);
			while (paint.measureText(name) > right - x && nameSize > 24) {
				nameSize -= 2;
				paint.setTextSize(nameSize);
			}
			String type = typeLine(c);
			float header = nameSize + 18 + (type.isEmpty() ? 0 : 34 + 18);
			StaticLayout desc = fitText(c.text, right - x, textBottom - top - header, 42, 18, true);
			float descH = desc == null ? 0 : Math.min(desc.getHeight(), textBottom - top - header);
			float y = Math.max(top, textBottom - header - descH);
			outlined(canvas, name, x, y + nameSize, nameSize, COLOR_GOLD, Paint.Align.LEFT);
			y += nameSize + 18;
			if (!type.isEmpty()) {
				outlined(canvas, type, x, y + 34, 34, 0xFF7BE35A, Paint.Align.LEFT);
				y += 34 + 18;
			}
			drawText(canvas, c.text, x, y, right - x, descH, 42, 18, 0xFFFFFFFF, true);
		}

		private static final float STATS_K = 2.6f;
		private static final float STATS_H = 111 * STATS_K;

		private static String typeLine(Card c) {
			if (c.kind == KIND_SPELL) {
				return "[SPELL CARD]";
			}
			if (c.kind == KIND_TRAP) {
				return "[TRAP CARD]";
			}
			String race = c.race > 0 && c.race < RACES.length ? RACES[c.race].toUpperCase(Locale.US) : "";
			String kind = c.kind == KIND_EFFECT ? "/EFFECT" : c.kind == KIND_FUSION ? "/FUSION" : c.kind == KIND_RITUAL ? "/RITUAL" : "";
			return race.isEmpty() ? "" : "[" + race + kind + "]";
		}

		// The silver ATK/DEF panel with gold digits, the attribute and the level next to it.
		private float drawStats(Canvas canvas, Card c, float x, float right, float y) {
			Bitmap p = t(T_PANEL);
			float k = STATS_K;
			float pw = 156 * k, ph = 111 * k;
			if (p != null) {
				blit(canvas, p, 0, 127, 156, 111, x, y, pw, ph, paint);
				float dh = 30 * k;
				String atk = String.valueOf(duel.atk), def = String.valueOf(duel.def);
				// Right-aligned in the windows ((66,140)-(141,172) and (66,190)-(141,221)).
				digits(canvas, atk, x + 139 * k - digitsWidth(atk, dh), y + (141 - 127) * k, dh);
				digits(canvas, def, x + 139 * k - digitsWidth(def, dh), y + (191 - 127) * k, dh);
			} else {
				outlined(canvas, "ATK " + duel.atk, x, y + 80, 64, COLOR_GOLD, Paint.Align.LEFT);
				outlined(canvas, "DEF " + duel.def, x, y + 170, 64, COLOR_YOU, Paint.Align.LEFT);
			}
			// Attribute icon and "★×level" in the space to the right.
			float cx = (x + pw + right) / 2;
			Bitmap icons = t(T_ICON);
			if (icons != null && c.attr >= 1 && c.attr <= 7) {
				float is = 120;
				if (c.attr == 7) {
					blit(canvas, icons, 102, 85, 16, 16, cx - is / 2, y + 30, is, is, paint);
				} else {
					blit(canvas, icons, 17 * (c.attr - 1), 68, 16, 16, cx - is / 2, y + 30, is, is, paint);
				}
			}
			if (c.level > 0) {
				String lv = "×" + c.level;
				paint.setTextSize(52);
				float tw = 52 + 8 + paint.measureText(lv);
				float sx = cx - tw / 2;
				if (icons != null) {
					blit(canvas, icons, 56, 120, 8, 8, sx, y + 190, 52, 52, paint);
				}
				outlined(canvas, lv, sx + 60, y + 236, 52, 0xFFFFFFFF, Paint.Align.LEFT);
			}
			return y + ph;
		}

		// Wraps text to the width at the biggest size that fits the height (or the smallest one).
		// Leaves textPaint set up for it.
		private StaticLayout fitText(String text, float w, float h, float maxSize, float minSize, boolean outline) {
			if (w <= 0 || h <= 0) {
				return null;
			}
			textPaint.setTypeface(outline ? Typeface.DEFAULT_BOLD : Typeface.SERIF);
			textPaint.setStyle(Paint.Style.FILL);
			float size = maxSize;
			while (true) {
				textPaint.setTextSize(size);
				StaticLayout layout = new StaticLayout(text, textPaint, (int)w, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0, false);
				if (layout.getHeight() <= h || size <= minSize) {
					return layout;
				}
				size -= 1;
			}
		}

		// Wraps text into the box, shrinking it until it fits.
		private void drawText(Canvas canvas, String text, float x, float y, float w, float h, float maxSize, float minSize, int color, boolean outline) {
			StaticLayout layout = fitText(text, w, h, maxSize, minSize, outline);
			if (layout == null) {
				return;
			}
			float size = textPaint.getTextSize();
			canvas.save();
			canvas.translate(x, y);
			canvas.clipRect(-size, -size, w + size, h + size);
			if (outline) {
				textPaint.setStyle(Paint.Style.STROKE);
				textPaint.setStrokeWidth(size * 0.16f);
				textPaint.setStrokeJoin(Paint.Join.ROUND);
				textPaint.setColor(0xFF000000);
				layout.draw(canvas);
				textPaint.setStyle(Paint.Style.FILL);
			}
			textPaint.setColor(color);
			layout.draw(canvas);
			canvas.restore();
		}

		// The card as the game draws it, in 108x154 card units times k.
		private void drawCard(Canvas canvas, Card c, float x, float y, float k) {
			Bitmap f = t(T_FRAME);
			Paint fp = paint;
			if (KIND_TINT[c.kind] != 0) {
				tinted.setColorFilter(tints[c.kind]);
				fp = tinted;
			}
			if (f != null) {
				if (frameParts == null) {
					frameParts = cutFrame(f);
				}
				drawWhole(canvas, frameParts[0], x, y, 108 * k, 34 * k, fp);
				drawWhole(canvas, frameParts[1], x, y + 34 * k, 14 * k, 78 * k, fp);
				drawWhole(canvas, frameParts[2], x + 92 * k, y + 34 * k, 16 * k, 78 * k, fp);
				drawWhole(canvas, frameParts[3], x, y + 112 * k, 108 * k, 42 * k, fp);
			} else {
				dst.set(x, y, x + 108 * k, y + 154 * k);
				paint.setColor(0xFFC9A04A);
				canvas.drawRoundRect(dst, 4 * k, 4 * k, paint);
			}
			if (c.art != null) {
				blit(canvas, c.art, 0, 0, 78, 78, x + 14 * k, y + 34 * k, 78 * k, 78 * k, paint);
			} else {
				dst.set(x + 14 * k, y + 34 * k, x + 92 * k, y + 112 * k);
				paint.setColor(0xFF1A1A22);
				canvas.drawRect(dst, paint);
			}

			// Name in the name bar, attribute icon at its end.
			Bitmap icons = t(T_ICON);
			float iconX = 86 * k;
			textPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
			textPaint.setStyle(Paint.Style.FILL);
			textPaint.setColor(c.kind == KIND_SPELL || c.kind == KIND_TRAP || c.kind == KIND_FUSION ? 0xFFFFFFFF : 0xFF1A1208);
			float ns = 9 * k;
			String name = c.name.toUpperCase(Locale.US);
			textPaint.setTextSize(ns);
			while (textPaint.measureText(name) > iconX - 10 * k && ns > 4 * k) {
				ns -= 0.25f * k;
				textPaint.setTextSize(ns);
			}
			canvas.drawText(name, x + 9 * k, y + 13.5f * k + ns * 0.36f, textPaint);
			if (icons != null) {
				if (c.kind == KIND_SPELL) {
					blit(canvas, icons, 102, 68, 16, 16, x + iconX, y + 5 * k, 16 * k, 16 * k, paint);
				} else if (c.kind == KIND_TRAP) {
					blit(canvas, icons, 85, 85, 16, 16, x + iconX, y + 5 * k, 16 * k, 16 * k, paint);
				} else if (c.attr == 7) {
					blit(canvas, icons, 102, 85, 16, 16, x + iconX, y + 5 * k, 16 * k, 16 * k, paint);
				} else if (c.attr >= 1 && c.attr <= 6) {
					blit(canvas, icons, 17 * (c.attr - 1), 68, 16, 16, x + iconX, y + 5 * k, 16 * k, 16 * k, paint);
				}
				// Level stars, right-aligned under the name bar.
				for (int i = 0; i < c.level; i++) {
					blit(canvas, icons, 56, 120, 8, 8, x + (97 - 8 * (i + 1)) * k, y + 24 * k, 7.5f * k, 7.5f * k, paint);
				}
			}
			if (!c.monster()) {
				textPaint.setTextAlign(Paint.Align.RIGHT);
				textPaint.setTextSize(5.5f * k);
				textPaint.setColor(0xFF1A1208);
				canvas.drawText(c.kind == KIND_SPELL ? "[SPELL CARD]" : "[TRAP CARD]", x + 97 * k, y + 30 * k, textPaint);
				textPaint.setTextAlign(Paint.Align.LEFT);
			}

			// The text box: description, and ATK/DEF under a line for monsters.
			float bx = x + 10 * k, bw = 88 * k, by = y + 114.5f * k;
			float bh = c.monster() ? 22 * k : 31 * k;
			drawText(canvas, c.text, bx, by, bw, bh, 5.5f * k, 2.5f * k, 0xFF1A1208, false);
			if (c.monster()) {
				paint.setColor(0xFF1A1208);
				canvas.drawRect(bx, y + 137.5f * k, bx + bw, y + 138f * k, paint);
				textPaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
				textPaint.setTextSize(6.5f * k);
				textPaint.setColor(0xFF1A1208);
				textPaint.setTextAlign(Paint.Align.RIGHT);
				canvas.drawText(String.format(Locale.US, "ATK/%4d  DEF/%4d", duel.atk, duel.def), bx + bw, y + 144.5f * k, textPaint);
				textPaint.setTextAlign(Paint.Align.LEFT);
			}
		}

		private void drawWhole(Canvas canvas, Bitmap b, float x, float y, float w, float h, Paint p) {
			dst.set(x, y, x + w, y + h);
			canvas.drawBitmap(b, null, dst, p);
		}

		// The frame's four parts as their own bitmaps: filtering a sub-rectangle of the texture
		// samples the neighbouring part. The sides lie down in the texture and are both turned
		// counter-clockwise (checked against the top and bottom parts' edges).
		private static Bitmap[] cutFrame(Bitmap f) {
			Matrix ccw = new Matrix();
			ccw.postRotate(-90);
			return new Bitmap[] {
				Bitmap.createBitmap(f, 0, 0, 108, 34),
				Bitmap.createBitmap(f, 0, 34, 78, 14, ccw, false),
				Bitmap.createBitmap(f, 0, 48, 78, 16, ccw, false),
				Bitmap.createBitmap(f, 0, 64, 108, 42)};
		}
	}
}
