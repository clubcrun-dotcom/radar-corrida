package br.leno.radarcorrida.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acumulado do dia, lido da própria tela da Uber (sem precisar detectar cada corrida):
 *  - Ganhos de hoje: o "R$ 45,30 / HOJE" que a Uber mostra na tela inicial.
 *  - Tempo online: soma do tempo entre "Você está online/Procurando viagens" e "Você está
 *    offline". Lacunas longas sem tela da Uber (navegando no Waze, por exemplo) contam até
 *    {@link #MAX_GAP_MS} cada, porque o motorista continua online.
 * Vale para várias sessões no mesmo dia (ex.: 5:30–6:30 e 18:00–20:00).
 */
public final class DayTracker {
    static final long MAX_GAP_MS = 20 * 60 * 1000L;

    public String day = "";
    /** Ganhos de hoje segundo a Uber (R$, bruto). -1 = ainda não lido. */
    public double earnings = -1;
    public long onlineMs;
    public long lastTs;
    public boolean online;

    private static final Pattern MONEY = Pattern.compile("^R\\$\\s*(\\d{1,3}(?:\\.\\d{3})*,\\d{2})$");

    /** Atualiza com uma leitura de tela. day = "aaaa-mm-dd" local; now = relógio em ms. */
    public boolean onScreen(String today, long now, List<String> texts) {
        boolean changed = false;
        if (!today.equals(day)) {
            day = today;
            earnings = -1;
            onlineMs = 0;
            lastTs = 0;
            online = false;
            changed = true;
        }
        if (online && lastTs > 0 && now > lastTs) {
            onlineMs += Math.min(now - lastTs, MAX_GAP_MS);
            changed = true;
        }
        Boolean st = detectOnline(texts);
        if (st != null && st != online) {
            online = st;
            changed = true;
        }
        lastTs = now;
        Double e = detectEarnings(texts);
        if (e != null && e != earnings) {
            earnings = e;
            changed = true;
        }
        return changed;
    }

    static Boolean detectOnline(List<String> texts) {
        for (String t : texts) {
            if (t.equalsIgnoreCase("Você está online") || t.equalsIgnoreCase("Procurando viagens")) return true;
            if (t.equalsIgnoreCase("Você está offline") || t.equalsIgnoreCase("Ficar online")) return false;
        }
        return null;
    }

    /** "R$ 45,30" seguido de "HOJE" (tela inicial da Uber). */
    static Double detectEarnings(List<String> texts) {
        for (int i = 1; i < texts.size(); i++) {
            if (!"HOJE".equalsIgnoreCase(texts.get(i).trim())) continue;
            Matcher m = MONEY.matcher(texts.get(i - 1).trim());
            if (m.find()) {
                return Double.parseDouble(m.group(1).replace(".", "").replace(',', '.'));
            }
        }
        return null;
    }

    public double hoursOnline() {
        return onlineMs / 3_600_000.0;
    }

    /** R$/h do dia até agora (ganhos ÷ tempo online). 0 se ainda não dá para medir. */
    public double pace() {
        double h = hoursOnline();
        return (h >= 0.1 && earnings > 0) ? earnings / h : 0;
    }

    public double remaining(double goal) {
        return Math.max(0, goal - Math.max(0, earnings));
    }

    /** Minutos a mais, no ritmo atual, para fechar a meta. -1 se ainda não há ritmo. */
    public long extraMinutes(double goal) {
        double p = pace();
        if (p <= 0) return -1;
        return Math.round(remaining(goal) / p * 60);
    }

    public String summary(double goal) {
        Locale br = Locale.forLanguageTag("pt-BR");
        if (earnings < 0) return "Abra a tela inicial da Uber para o Radar ler os ganhos de hoje.";
        StringBuilder sb = new StringBuilder(String.format(br,
                "Hoje: R$ %.2f de R$ %.0f (%.0f%%) · %s online", earnings, goal,
                goal > 0 ? earnings / goal * 100 : 0, hm(onlineMs / 60000)));
        double p = pace();
        if (p > 0) sb.append(String.format(br, " · ritmo R$ %.0f/h", p));
        if (remaining(goal) <= 0) {
            sb.append("\n✔ Meta batida.");
        } else {
            long extra = extraMinutes(goal);
            sb.append(String.format(br, "\nFaltam R$ %.2f", remaining(goal)));
            if (extra >= 0) sb.append(" ≈ ").append(hm(extra)).append(" no ritmo atual.");
        }
        return sb.toString();
    }

    static String hm(long minutes) {
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + "h" + String.format(Locale.ROOT, "%02d", minutes % 60);
    }
}
