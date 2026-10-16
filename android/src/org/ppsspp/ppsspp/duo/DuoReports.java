package org.ppsspp.ppsspp.duo;

import java.util.LinkedHashMap;
import java.util.Map;

// What game mods last saw (for example why a battle can't be read), shown in the Diagnostics tab
// and copied with its Copy logs button. Kept for the app's lifetime, across tab switches.
public final class DuoReports {
	private DuoReports() {}

	private static final Map<String, String> reports = new LinkedHashMap<>();

	public static synchronized void put(String mod, String text) {
		reports.put(mod, text);
	}

	// One "mod: text" line each, or "" if no mod reported anything.
	public static synchronized String text() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : reports.entrySet()) {
			sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
		}
		return sb.toString();
	}
}
