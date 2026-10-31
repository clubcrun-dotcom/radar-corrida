package br.leno.radarcorrida.core;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Resultado do cálculo para uma oferta. */
public final class Evaluation {
    public enum Verdict { GOOD, OK, BAD }

    /** Situação de cada indicador em relação à meta: sem meta, batida, perto, abaixo. */
    public enum Mark { NONE, GOOD, NEAR, BAD }

    public final Offer offer;
    public final String profileName;
    public final double totalKm;
    public final double totalMin;
    public final double rsPerKm;
    public final double rsPerHour;
    public final double cost;
    public final double profit;
    public final double profitPct;
    public final double profitPerHour;
    /** Nota 0–5: 2,5 = exatamente na meta, 5 = o dobro da meta. */
    public final double score;
    public final Verdict verdict;
    /** Palavra do trajeto encontrada no destino, ou null. */
    public final String routeHit;
    /** Uma linha por meta/filtro: "✔ lucro 92% (meta 90%)". */
    public final List<String> reasons;

    // Quadros da etiqueta (preenchidos pelo Calculator).
    public Mark markKm = Mark.NONE;
    public Mark markHour = Mark.NONE;
    public Mark markProfit = Mark.NONE;
    /** Velocidade média da viagem (km/h), pelo tempo que o app de corrida estima. */
    public double tripSpeedKmh;
    public Mark markTraffic = Mark.NONE;
    /** "livre", "moderado", "pesado" ou vazio. */
    public String trafficLabel = "";
    /** true se o perfil tem bairros de trajeto cadastrados. */
    public boolean routeConfigured;

    Evaluation(Offer offer, String profileName, double totalKm, double totalMin,
               double rsPerKm, double rsPerHour, double cost, double profit, double profitPct,
               double profitPerHour, double score, Verdict verdict, String routeHit,
               List<String> reasons) {
        this.offer = offer;
        this.profileName = profileName;
        this.totalKm = totalKm;
        this.totalMin = totalMin;
        this.rsPerKm = rsPerKm;
        this.rsPerHour = rsPerHour;
        this.cost = cost;
        this.profit = profit;
        this.profitPct = profitPct;
        this.profitPerHour = profitPerHour;
        this.score = score;
        this.verdict = verdict;
        this.routeHit = routeHit;
        this.reasons = Collections.unmodifiableList(reasons);
    }

    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    public String headline() {
        return String.format(BR, "R$ %.2f/km · R$ %.0f/h · lucro %.0f%%", rsPerKm, rsPerHour, profitPct);
    }

    public String details() {
        return String.format(BR, "R$ %.2f · %.1f km · %.0f min · nota %.1f",
                offer.fare, totalKm, totalMin, score);
    }

    /** Motivos em uma linha, para a etiqueta. */
    public String reasonsLine() {
        StringBuilder sb = new StringBuilder();
        for (String r : reasons) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(r);
        }
        return sb.toString();
    }

    /** Linha curta para a lista de ofertas simultâneas: o valor ajuda a achar a oferta na tela. */
    public String compactLine() {
        String mark = verdict == Verdict.GOOD ? "✔" : (verdict == Verdict.OK ? "≈" : "✘");
        String dest = offer.destination;
        int cut = dest.indexOf(" | ");
        if (cut > 0) dest = dest.substring(0, cut);
        if (dest.length() > 22) dest = dest.substring(0, 21) + "…";
        String s = String.format(BR, "%s R$ %.2f · %.2f/km · %.0f/h · %.0f%%",
                mark, offer.fare, rsPerKm, rsPerHour, profitPct);
        if (routeHit != null) s += " · trajeto";
        if (!dest.isEmpty()) s += " → " + dest;
        return s;
    }

    /** Só o veredito, sem o "NO TRAJETO" (a etiqueta mostra o trajeto em selo próprio). */
    public String verdictLabelShort() {
        switch (verdict) {
            case GOOD: return "ACEITAR";
            case OK: return "AVALIAR";
            default: return "RECUSAR";
        }
    }

    public String verdictLabel() {
        String base;
        switch (verdict) {
            case GOOD: base = "ACEITAR"; break;
            case OK: base = "AVALIAR"; break;
            default: base = "RECUSAR"; break;
        }
        return routeHit != null && verdict != Verdict.BAD ? base + " · NO TRAJETO" : base;
    }
}
