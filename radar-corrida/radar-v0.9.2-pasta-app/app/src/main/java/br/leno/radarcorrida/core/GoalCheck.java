package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Checagem de realidade da meta do dia.
 *
 * Compara o que a meta exige (por hora de corrida) com o que as ofertas que o motorista
 * REALMENTE recebeu pagaram, usando os custos atuais do carro. Sem dados, só explica a conta.
 *
 *  - Exigido no dia: meta ÷ horas.
 *  - Exigido por corrida: exigido no dia ÷ % do tempo com passageiro (o resto é espera/volta).
 *  - Com dados: % das ofertas que atingem o exigido e projeção realista do dia aceitando
 *    as melhores ofertas disponíveis.
 */
public final class GoalCheck {
    public enum Status { NO_DATA, OK, TIGHT, UNLIKELY }

    /** Uma oferta vista: valor (R$), km total (com busca) e minutos totais (com busca). */
    public static final class Seen {
        public final double fare, km, min;

        public Seen(double fare, double km, double min) {
            this.fare = fare;
            this.km = km;
            this.min = min;
        }
    }

    public Status status = Status.NO_DATA;
    public double neededOverall;   // R$/h no dia todo
    public double neededPerRide;   // R$/h enquanto a corrida dura
    public int sample;             // ofertas consideradas
    public double shareMeeting;    // 0–1: parte das ofertas que atingem o exigido
    public double typical;         // mediana do indicador (R$/h)
    public double best;            // média dos 30% melhores (R$/h)
    public double projectedDay;    // R$ no dia, aceitando as melhores ofertas
    public double avgMarginPct;    // margem média (lucro ÷ valor) das ofertas vistas
    public final List<String> lines = new ArrayList<>();

    private static final Locale BR = Locale.forLanguageTag("pt-BR");
    static final int MIN_SAMPLE = 8;

    public static GoalCheck run(Settings s, boolean net, List<Seen> seen) {
        GoalCheck g = new GoalCheck();
        String kind = net ? "lucro líquido" : "bruto";
        g.neededOverall = s.neededProfitPerHourOverall();
        g.neededPerRide = s.neededProfitPerHourPerRide();
        double busy = Math.max(10, Math.min(100, s.busyPct)) / 100.0;

        g.lines.add(String.format(BR, "Meta: R$ %.0f de %s em %.1f h = R$ %.0f/h no dia.",
                s.dailyGoal, kind, s.dailyHours, g.neededOverall));
        g.lines.add(String.format(BR, "Com %.0f%% do tempo em corrida, cada corrida precisa render "
                + "≥ R$ %.0f/h (%s) enquanto dura.", s.busyPct, g.neededPerRide, kind));

        List<Double> vals = new ArrayList<>();
        double marginSum = 0;
        int marginN = 0;
        double cost = s.costPerKm();
        for (Seen o : seen) {
            if (o.min <= 0 || o.fare <= 0) continue;
            double profit = o.fare - cost * o.km;
            double perHour = (net ? profit : o.fare) / (o.min / 60.0);
            vals.add(perHour);
            marginSum += profit / o.fare;
            marginN++;
        }
        g.sample = vals.size();
        g.avgMarginPct = marginN > 0 ? marginSum / marginN * 100 : 0;

        if (g.sample < MIN_SAMPLE) {
            g.status = Status.NO_DATA;
            g.lines.add(String.format(BR, "Ainda não há ofertas suficientes para comparar (%d de %d). "
                    + "Rode com o Radar ligado e a checagem passa a usar as ofertas que você recebe.",
                    g.sample, MIN_SAMPLE));
            return g;
        }

        Collections.sort(vals);
        int meet = 0;
        for (double v : vals) if (v >= g.neededPerRide) meet++;
        g.shareMeeting = (double) meet / vals.size();
        g.typical = vals.get(vals.size() / 2);
        int topN = Math.max(1, (int) Math.round(vals.size() * 0.3));
        double sum = 0;
        for (int i = vals.size() - topN; i < vals.size(); i++) sum += vals.get(i);
        g.best = sum / topN;

        // Projeção: aceitando só o que atinge a meta se houver bastante oferta assim; senão,
        // as melhores disponíveis (30% do topo), no tempo útil do dia.
        double perRide = g.shareMeeting >= 0.4 ? Math.max(g.neededPerRide, g.typical) : g.best;
        g.projectedDay = perRide * s.dailyHours * busy;

        if (g.shareMeeting >= 0.4) g.status = Status.OK;
        else if (g.shareMeeting >= 0.15 || g.projectedDay >= s.dailyGoal * 0.85) g.status = Status.TIGHT;
        else g.status = Status.UNLIKELY;

        g.lines.add(String.format(BR, "Nas suas últimas %d ofertas: mediana R$ %.0f/h, as melhores "
                + "R$ %.0f/h. %.0f%% atingem o exigido.", g.sample, g.typical, g.best, g.shareMeeting * 100));
        switch (g.status) {
            case OK:
                g.lines.add(String.format(BR, "✔ VIÁVEL: projeção de R$ %.0f no dia. Aceite as que "
                        + "ficarem acima de R$ %.0f/h.", g.projectedDay, g.neededPerRide));
                break;
            case TIGHT:
                g.lines.add(String.format(BR, "≈ APERTADO: projeção de R$ %.0f no dia. Dá, mas depende "
                        + "de horário bom (pico, dinâmica) e de não recusar as boas.", g.projectedDay));
                break;
            default:
                g.lines.add(String.format(BR, "✘ IMPROVÁVEL com as ofertas que você recebe: projeção de "
                        + "R$ %.0f no dia. Para fechar, ajuste horas, horário/região ou a meta.",
                        g.projectedDay));
                break;
        }
        if (!net) {
            g.lines.add(String.format(BR, "Com o custo do seu carro, R$ %.0f bruto viram ~R$ %.0f de "
                    + "lucro líquido (margem média de %.0f%%).", s.dailyGoal,
                    s.dailyGoal * g.avgMarginPct / 100, g.avgMarginPct));
        } else if (g.avgMarginPct > 0) {
            g.lines.add(String.format(BR, "Para R$ %.0f de lucro líquido, você precisa faturar ~R$ %.0f "
                    + "bruto (margem média de %.0f%%).", s.dailyGoal,
                    s.dailyGoal / (g.avgMarginPct / 100), g.avgMarginPct));
        }
        return g;
    }

    /**
     * Lê o corridas.csv (todas as versões do app) e devolve as ofertas vistas nos apps de
     * corrida, sem repetições (a mesma oferta lida várias vezes conta uma vez) e sem testes
     * feitos pela Galeria.
     */
    public static List<Seen> fromCsv(String csv) {
        List<Seen> out = new ArrayList<>();
        if (csv == null) return out;
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (String line : csv.split("\\r?\\n")) {
            if (line.isEmpty() || line.startsWith("data_hora")) continue;
            String[] c = line.split(";", -1);
            int iApp = 1, iFare, iKm, iMin;
            if (c.length >= 21) { iFare = 5; iKm = 10; iMin = 11; }        // v0.3+
            else if (c.length >= 19) { iFare = 3; iKm = 8; iMin = 9; }     // v0.2
            else if (c.length >= 16) { iFare = 2; iKm = 7; iMin = 8; }     // v0.1
            else continue;
            String app = c[iApp].toLowerCase(Locale.ROOT);
            if (app.contains("gallery")) continue;
            try {
                double fare = num(c[iFare]), km = num(c[iKm]), min = num(c[iMin]);
                String minute = c[0].length() >= 16 ? c[0].substring(0, 16) : c[0];
                String key = String.format(Locale.ROOT, "%.2f|%.0f", fare, min) + "|" + minute.substring(0, Math.min(13, minute.length()));
                if (!seenKeys.add(key)) continue;
                out.add(new Seen(fare, km, min));
            } catch (NumberFormatException ignored) {
                // linha incompleta
            }
        }
        return out;
    }

    private static double num(String s) {
        return Double.parseDouble(s.trim().replace(".", "").replace(',', '.'));
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }
}
