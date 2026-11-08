package br.leno.radarcorrida;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import br.leno.radarcorrida.core.Profile;
import br.leno.radarcorrida.core.Settings;

/** Leitura e gravação das configurações: custos do carro, perfis e opções de diagnóstico. */
public final class Prefs {
    public static final String FILE = "radar";

    // Carro (comum a todos os perfis)
    public static final String K_FUEL = "fuel_price";
    public static final String K_KMPL = "km_per_liter";
    public static final String K_EXTRA = "extra_cost_km";
    // Perfis
    public static final String K_PROFILES = "profiles_json";
    public static final String K_ACTIVE = "active_profile";
    // Diagnóstico
    public static final String K_PACKAGES = "packages";
    public static final String K_DEBUG = "debug_dump";
    public static final String K_DISCOVERY = "discovery";
    public static final String K_OVERLAY_Y = "overlay_y_dp";
    public static final String K_COMPACT = "overlay_compact";
    public static final String K_LAYOUT = "overlay_layout";
    public static final String K_GOAL = "daily_goal";
    public static final String K_HOURS = "daily_hours";
    public static final String K_BUSY = "busy_pct";
    public static final String K_NET = "net_goal";
    public static final String K_HIST_LAST = "hist_last_import";
    public static final String K_CONSENT = "consent_v2";
    public static final String K_OCR = "ocr_enabled";
    public static final String K_PRESET_LIVING = "preset_living_v1";

    public static final String DEF_FUEL = "6,45";
    public static final String DEF_KMPL = "14";
    public static final String DEF_EXTRA = "0,00";

    /** Pacotes padrão. Confirme com o modo descoberta: versões e regiões podem variar. */
    public static final String DEFAULT_PACKAGES =
            "com.ubercab.driver\ncom.app99.driver\nsinet.startup.inDriver";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Custos do carro + perfil ativo, prontos para o cálculo. */
    public static Settings settings(Context c) {
        SharedPreferences p = sp(c);
        Settings s = new Settings();
        s.fuelPrice = num(p.getString(K_FUEL, DEF_FUEL), s.fuelPrice);
        s.kmPerLiter = num(p.getString(K_KMPL, DEF_KMPL), s.kmPerLiter);
        s.extraCostPerKm = num(p.getString(K_EXTRA, DEF_EXTRA), s.extraCostPerKm);
        s.dailyGoal = num(p.getString(K_GOAL, "100"), s.dailyGoal);
        s.dailyHours = num(p.getString(K_HOURS, "3"), s.dailyHours);
        s.busyPct = num(p.getString(K_BUSY, "60"), s.busyPct);
        s.netGoal = p.getBoolean(K_NET, false);
        List<Profile> profiles = profiles(c);
        s.profile = profiles.get(activeIndex(c, profiles.size()));
        s.profile.applyGoal(s);
        return s;
    }

    // ---- Perfis ----

    public static List<Profile> profiles(Context c) {
        String raw = sp(c).getString(K_PROFILES, null);
        List<Profile> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
            } catch (JSONException ignored) {
                out.clear();
            }
        }
        if (out.isEmpty()) out.addAll(Profile.presets());
        migrate(c, out);
        return out;
    }

    /**
     * 1) Perfis sem meta própria herdam a meta global antiga (nada muda para quem já usava).
     * 2) Uma única vez: cria o perfil "Vivendo do Uber" (troca o "Tempo integral" se ele nunca foi mexido).
     */
    static void migrate(Context c, List<Profile> out) {
        SharedPreferences p = sp(c);
        for (Profile pr : out) {
            if (pr.hasGoal()) continue;
            pr.dailyGoal = num(p.getString(K_GOAL, "100"), 100);
            pr.dailyHours = num(p.getString(K_HOURS, "3"), 3);
            pr.busyPct = num(p.getString(K_BUSY, "60"), 60);
            pr.netGoal = p.getBoolean(K_NET, false);
        }
        if (p.getBoolean(K_PRESET_LIVING, false)) return;
        boolean exists = false;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).isUntouchedOldFullTime()) out.set(i, Profile.livingFromUber());
            if (Profile.LIVING_NAME.equals(out.get(i).name)) exists = true;
        }
        if (!exists) out.add(Profile.livingFromUber());
        saveProfiles(c, out, activeIndex(c, out.size()));
        p.edit().putBoolean(K_PRESET_LIVING, true).apply();
    }

    public static int activeIndex(Context c, int size) {
        int i = sp(c).getInt(K_ACTIVE, 0);
        return (i >= 0 && i < size) ? i : 0;
    }

    public static void saveProfiles(Context c, List<Profile> profiles, int active) {
        JSONArray a = new JSONArray();
        for (Profile p : profiles) {
            try {
                a.put(toJson(p));
            } catch (JSONException ignored) {
                // campo inválido não impede salvar os demais
            }
        }
        sp(c).edit().putString(K_PROFILES, a.toString()).putInt(K_ACTIVE, active).apply();
    }

    private static JSONObject toJson(Profile p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", p.name);
        o.put("matchAll", p.matchAll);
        o.put("minRsPerKm", p.minRsPerKm);
        o.put("minRsPerHour", p.minRsPerHour);
        o.put("minProfitPerHour", p.minProfitPerHour);
        o.put("minProfitPct", p.minProfitPct);
        o.put("maxPickupKm", p.maxPickupKm);
        o.put("maxPickupMin", p.maxPickupMin);
        o.put("includePickup", p.includePickup);
        o.put("routeDetourCostOnly", p.routeDetourCostOnly);
        if (p.hasGoal()) {
            o.put("dailyGoal", p.dailyGoal);
            if (!Double.isNaN(p.dailyHours)) o.put("dailyHours", p.dailyHours);
            if (!Double.isNaN(p.busyPct)) o.put("busyPct", p.busyPct);
            o.put("netGoal", p.netGoal);
        }
        JSONArray k = new JSONArray();
        for (String s : p.routeKeywords) k.put(s);
        o.put("routeKeywords", k);
        return o;
    }

    private static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.name = o.optString("name", "Perfil");
        p.matchAll = o.optBoolean("matchAll", true);
        p.minRsPerKm = o.optDouble("minRsPerKm", 0);
        p.minRsPerHour = o.optDouble("minRsPerHour", 0);
        p.minProfitPerHour = o.optDouble("minProfitPerHour", 0);
        p.minProfitPct = o.optDouble("minProfitPct", 0);
        p.maxPickupKm = o.optDouble("maxPickupKm", 0);
        p.maxPickupMin = o.optDouble("maxPickupMin", 0);
        p.includePickup = o.optBoolean("includePickup", true);
        p.routeDetourCostOnly = o.optBoolean("routeDetourCostOnly", false);
        if (o.has("dailyGoal")) {
            p.dailyGoal = o.optDouble("dailyGoal", Double.NaN);
            p.dailyHours = o.optDouble("dailyHours", Double.NaN);
            p.busyPct = o.optDouble("busyPct", Double.NaN);
            p.netGoal = o.optBoolean("netGoal", false);
        }
        JSONArray k = o.optJSONArray("routeKeywords");
        if (k != null) {
            for (int i = 0; i < k.length(); i++) {
                String s = k.optString(i, "").trim();
                if (!s.isEmpty()) p.routeKeywords.add(s);
            }
        }
        return p;
    }

    // ---- Acumulado do dia (escrito pelo serviço, lido pela tela) ----

    public static br.leno.radarcorrida.core.DayTracker loadDay(Context c) {
        SharedPreferences p = sp(c);
        br.leno.radarcorrida.core.DayTracker d = new br.leno.radarcorrida.core.DayTracker();
        d.day = p.getString("day_date", "");
        d.earnings = num(p.getString("day_earn", "-1"), -1);
        d.onlineMs = p.getLong("day_online_ms", 0);
        d.lastTs = p.getLong("day_last_ts", 0);
        d.online = p.getBoolean("day_online", false);
        return d;
    }

    public static void saveDay(Context c, br.leno.radarcorrida.core.DayTracker d) {
        sp(c).edit()
                .putString("day_date", d.day)
                .putString("day_earn", String.valueOf(d.earnings))
                .putLong("day_online_ms", d.onlineMs)
                .putLong("day_last_ts", d.lastTs)
                .putBoolean("day_online", d.online)
                .apply();
    }

    public static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
    }

    // ---- Diagnóstico ----

    public static Set<String> packages(Context c) {
        String raw = sp(c).getString(K_PACKAGES, DEFAULT_PACKAGES);
        Set<String> out = new LinkedHashSet<>();
        for (String line : raw.split("[\\s,;]+")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static boolean debug(Context c) { return sp(c).getBoolean(K_DEBUG, true); }

    public static boolean discovery(Context c) { return sp(c).getBoolean(K_DISCOVERY, false); }
    /** 0 = cartão completo, 1 = faixa compacta (padrão), 2 = coluna à direita. */
    public static int layout(Context c) { return sp(c).getInt(K_LAYOUT, 1); }

    public static boolean ocr(Context c) { return sp(c).getBoolean(K_OCR, true); }

    public static int overlayYdp(Context c) {
        return (int) num(sp(c).getString(K_OVERLAY_Y, "90"), 90);
    }

    // ---- Números ----

    /** Aceita vírgula ou ponto decimal; vazio ou inválido → padrão. */
    static double num(String v, double def) {
        if (v == null || v.trim().isEmpty()) return def;
        try {
            return Double.parseDouble(v.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Formata para exibir/editar: "1,50"; zero vira vazio (meta desligada). */
    static String edit(double v) {
        if (v == 0) return "";
        if (v == Math.rint(v)) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v);
    }

    static String fmt(double v) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v);
    }
}
