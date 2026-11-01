package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê a tela "Histórico de ganhos" da Uber (texto da acessibilidade, sem imagem) e devolve as
 * corridas realizadas: data, hora, tipo, valor, duração, km, dinâmica e pedágio.
 *
 * Formato observado (31/10/2026):
 *   "qua., 28 de out."                                  ← cabeçalho do dia
 *   "R$ 42,76"                                          ← valor recebido
 *   "Aumentou"                                          ← (opcional)
 *   "Uber X · 48 min 35 segundos · 25.84 km"            ← tipo · duração · km
 *   "6:28"                                              ← hora
 *   "R$ 5,50 Preço dinâmico" / "R$ 6,60 Pedágio"        ← (opcionais)
 *   endereços…
 * Promoções aparecem como "R$ 180,00" + "Promoção" + "6:59".
 */
public final class HistoryParser {
    private HistoryParser() {}

    public static final class Trip {
        public String date = "";      // aaaa-mm-dd
        public String time = "";      // hh:mm
        public String kind = "";      // "Uber X", "Prioridade", "Promoção"…
        public double fare;           // R$ recebido (inclui pedágio, que é reembolso)
        public double minutes;
        public double km;
        public double surge;          // R$ de preço dinâmico
        public double toll;           // R$ de pedágio (reembolso, não é ganho)
        public double tip;            // R$ de valor extra do passageiro
        public boolean promo;
        public boolean cancelled;

        /** Ganho real: valor menos o pedágio (que só devolve o que o motorista pagou). */
        public double earnings() { return fare - toll; }

        public String key() {
            return date + " " + time + " " + String.format(Locale.ROOT, "%.2f", fare);
        }

        public int hour() {
            int i = time.indexOf(':');
            try {
                return i > 0 ? Integer.parseInt(time.substring(0, i)) : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    private static final String[] MONTHS = {"jan", "fev", "mar", "abr", "mai", "jun",
            "jul", "ago", "set", "out", "nov", "dez"};
    private static final Pattern DAY_HEADER = Pattern.compile(
            "^(?:seg|ter|qua|qui|sex|s[áa]b|dom)\\.?,?\\s+(\\d{1,2})\\s+de\\s+([a-zç]{3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FARE = Pattern.compile("^R\\$\\s*(\\d{1,3}(?:\\.\\d{3})*,\\d{2})$");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}:\\d{2})$");
    private static final Pattern EXTRA = Pattern.compile("^R\\$\\s*(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s+(.+)$");
    private static final Pattern H = Pattern.compile("(\\d+)\\s*h\\b");
    private static final Pattern MIN = Pattern.compile("(\\d+)\\s*min");
    private static final Pattern SEC = Pattern.compile("(\\d+)\\s*segundo");
    private static final Pattern KM = Pattern.compile("([\\d.,]+)\\s*km");

    /** true se a tela é o histórico de ganhos. */
    public static boolean isHistoryScreen(List<String> texts) {
        for (String t : texts) if ("Histórico de ganhos".equalsIgnoreCase(clean(t))) return true;
        return false;
    }

    /**
     * Corridas visíveis. Entradas no topo da lista sem cabeçalho de dia visível acima são
     * ignoradas (a data não é conhecida); aparecem quando o motorista rola a lista.
     * year/month = data de hoje, para deduzir o ano (dezembro visto em janeiro = ano anterior).
     */
    public static List<Trip> parse(List<String> raw, int year, int month) {
        List<Trip> out = new ArrayList<>();
        String date = null;
        Trip cur = null;
        for (int i = 0; i < raw.size(); i++) {
            String t = clean(raw.get(i));
            if (t.isEmpty()) continue;

            Matcher d = DAY_HEADER.matcher(t);
            if (d.find()) {
                if (cur != null) { finish(cur, out); cur = null; }
                int m = monthIndex(d.group(2));
                if (m > 0) {
                    int y = m > month ? year - 1 : year;
                    date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, Integer.parseInt(d.group(1)));
                }
                continue;
            }

            Matcher f = FARE.matcher(t);
            if (f.find()) {
                if (cur != null) finish(cur, out);
                cur = null;
                if (date != null) {
                    cur = new Trip();
                    cur.date = date;
                    cur.fare = money(f.group(1));
                }
                continue;
            }
            if (cur == null) continue;

            if (t.contains("·") && (t.contains("km") || t.toLowerCase(Locale.ROOT).contains("cancel"))) {
                String[] parts = t.split("·");
                cur.kind = parts[0].trim();
                if (t.toLowerCase(Locale.ROOT).contains("cancel")) cur.cancelled = true;
                double min = 0;
                Matcher mh = H.matcher(t);
                if (mh.find()) min += 60 * Integer.parseInt(mh.group(1));
                Matcher mm = MIN.matcher(t);
                if (mm.find()) min += Integer.parseInt(mm.group(1));
                Matcher ms = SEC.matcher(t);
                if (ms.find()) min += Integer.parseInt(ms.group(1)) / 60.0;
                cur.minutes = min;
                Matcher mk = KM.matcher(t);
                if (mk.find()) cur.km = Double.parseDouble(mk.group(1).replace(",", "."));
                continue;
            }
            if (t.equalsIgnoreCase("Promoção")) {
                cur.promo = true;
                cur.kind = "Promoção";
                continue;
            }
            Matcher tm = TIME.matcher(t);
            if (tm.find() && cur.time.isEmpty()) {
                cur.time = tm.group(1);
                continue;
            }
            Matcher ex = EXTRA.matcher(t);
            if (ex.find()) {
                String what = ex.group(2).toLowerCase(Locale.ROOT);
                double v = money(ex.group(1));
                if (what.contains("dinâmico") || what.contains("dinamico")) cur.surge = v;
                else if (what.contains("pedágio") || what.contains("pedagio")) cur.toll = v;
                else if (what.contains("extra") || what.contains("gorjeta")) cur.tip = v;
            }
        }
        if (cur != null) finish(cur, out);
        return out;
    }

    private static void finish(Trip t, List<Trip> out) {
        if (t.time.isEmpty()) return;                       // entrada cortada na borda da tela
        if (!t.promo && !t.cancelled && t.minutes <= 0) return;
        out.add(t);
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u2066-\\u2069\\u200e\\u200f]", "").trim();
    }

    private static int monthIndex(String m) {
        String k = m.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equals(k)) return i + 1;
        return -1;
    }

    private static double money(String s) {
        return Double.parseDouble(s.replace(".", "").replace(',', '.'));
    }

    // ---- CSV (historico.csv) ----

    public static final String CSV_HEADER =
            "data;hora;tipo;valor;minutos;km;dinamico;pedagio;extra;promocao;cancelada\n";

    public static String toCsv(Trip t) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%s;%s;%s;%.2f;%.1f;%.2f;%.2f;%.2f;%.2f;%s;%s\n",
                t.date, t.time, t.kind.replace(';', ','), t.fare, t.minutes, t.km, t.surge, t.toll, t.tip,
                t.promo ? "sim" : "nao", t.cancelled ? "sim" : "nao");
    }

    public static List<Trip> fromCsv(String csv) {
        List<Trip> out = new ArrayList<>();
        if (csv == null) return out;
        for (String line : csv.split("\\r?\\n")) {
            if (line.isEmpty() || line.startsWith("data;")) continue;
            String[] c = line.split(";", -1);
            if (c.length < 11) continue;
            try {
                Trip t = new Trip();
                t.date = c[0];
                t.time = c[1];
                t.kind = c[2];
                t.fare = n(c[3]);
                t.minutes = n(c[4]);
                t.km = n(c[5]);
                t.surge = n(c[6]);
                t.toll = n(c[7]);
                t.tip = n(c[8]);
                t.promo = "sim".equals(c[9]);
                t.cancelled = "sim".equals(c[10]);
                out.add(t);
            } catch (NumberFormatException ignored) {
                // linha corrompida
            }
        }
        return out;
    }

    private static double n(String s) {
        return Double.parseDouble(s.trim().replace(".", "").replace(',', '.'));
    }
}
