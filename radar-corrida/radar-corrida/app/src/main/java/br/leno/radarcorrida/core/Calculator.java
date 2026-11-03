package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aritmética da decisão. Sem mágica: R$/km, R$/h, custo variável, lucro e trajeto.
 *
 * 1. Filtros eliminatórios do perfil (busca longe demais) → RECUSAR.
 * 2. Metas ligadas (R$/km, R$/h, lucro %, trajeto), combinadas por TODAS ou QUALQUER:
 *    - bateu a regra → ACEITAR;
 *    - ficou perto (meta numérica a ≥ 85%) → AVALIAR;
 *    - resto → RECUSAR.
 */
public final class Calculator {
    private Calculator() {}

    private static final Locale BR = Locale.forLanguageTag("pt-BR");
    static final double NEAR = 0.85;
    /** Abaixo disso a rota está travada (média urbana com semáforos ≈ 20–30 km/h). */
    static final double TRAFFIC_HEAVY_KMH = 18;
    static final double TRAFFIC_FREE_KMH = 30;

    private static Evaluation.Mark markOf(double ratio) {
        if (ratio < 0) return Evaluation.Mark.NONE;
        if (ratio >= 1) return Evaluation.Mark.GOOD;
        if (ratio >= NEAR) return Evaluation.Mark.NEAR;
        return Evaluation.Mark.BAD;
    }

    public static Evaluation evaluate(Offer o, Settings s) {
        Profile p = s.profile != null ? s.profile : new Profile();

        double km = o.tripKm + (p.includePickup ? o.pickupKm : 0);
        double min = o.tripMin + (p.includePickup ? o.pickupMin : 0);

        double rsKm = km > 0 ? o.fare / km : 0;
        double hours = min / 60.0;
        double rsH = hours > 0 ? o.fare / hours : 0;

        // Corrida no trajeto (ex.: casa → trabalho): o km da viagem você rodaria de qualquer jeito.
        // Com a opção ligada, o custo é só o do desvio (o deslocamento até o passageiro).
        String routeHitEarly = p.routeMatch(o.destination);
        boolean detourOnly = p.routeDetourCostOnly && routeHitEarly != null;
        double costKm = detourOnly ? (p.includePickup ? o.pickupKm : 0) : km;
        double cost = s.costPerKm() * costKm;
        double profit = o.fare - cost;
        double profitPct = o.fare > 0 ? profit / o.fare * 100.0 : 0;
        double profitH = hours > 0 ? profit / hours : 0;

        String routeHit = routeHitEarly;
        List<String> reasons = new ArrayList<>();

        // 1. Filtros eliminatórios
        // Busca estimada (não lida na tela) não recusa sozinha: avisa e deixa o motorista conferir.
        boolean blocked = false;
        if (p.maxPickupKm > 0 && o.pickupKm > p.maxPickupKm) {
            blocked = !o.pickupEstimated;
            reasons.add(o.pickupEstimated
                    ? f("? busca ~%.1f km (confira no cartão)", o.pickupKm)
                    : f("✘ busca %.1f km > %.1f km", o.pickupKm, p.maxPickupKm));
        }
        if (p.maxPickupMin > 0 && o.pickupMin > p.maxPickupMin && !o.pickupEstimated) {
            blocked = true;
            reasons.add(f("✘ busca %.0f min > %.0f min", o.pickupMin, p.maxPickupMin));
        }

        // 2. Metas. ratio >= 1 = batida.
        List<Double> ratios = new ArrayList<>();
        List<Boolean> numeric = new ArrayList<>();
        if (p.minRsPerKm > 0) {
            double r = rsKm / p.minRsPerKm;
            ratios.add(r); numeric.add(true);
            reasons.add(f("%s R$ %.2f/km (meta %.2f)", mark(r), rsKm, p.minRsPerKm));
        }
        if (p.minRsPerHour > 0) {
            double r = rsH / p.minRsPerHour;
            ratios.add(r); numeric.add(true);
            reasons.add(f("%s R$ %.0f/h (meta %.0f)", mark(r), rsH, p.minRsPerHour));
        }
        if (p.minProfitPerHour > 0) {
            double r = profitH / p.minProfitPerHour;
            ratios.add(r); numeric.add(true);
            reasons.add(f("%s lucro R$ %.0f/h (meta %.0f)", mark(r), profitH, p.minProfitPerHour));
        }
        if (p.minProfitPct > 0) {
            double r = profitPct / p.minProfitPct;
            ratios.add(r); numeric.add(true);
            reasons.add(f("%s lucro %.0f%% (meta %.0f%%)", mark(r), profitPct, p.minProfitPct));
        }
        if (!p.routeKeywords.isEmpty()) {
            double r = routeHit != null ? 1 : 0;
            ratios.add(r); numeric.add(false);
            reasons.add(routeHit != null ? "✔ no trajeto: " + routeHit : "✘ fora do trajeto");
        }

        boolean pass;
        boolean near;
        if (ratios.isEmpty()) {
            pass = true;
            near = true;
        } else if (p.matchAll) {
            pass = true;
            int misses = 0;
            boolean missIsNear = true;
            for (int i = 0; i < ratios.size(); i++) {
                double r = ratios.get(i);
                if (r < 1) {
                    pass = false;
                    misses++;
                    if (!numeric.get(i) || r < NEAR) missIsNear = false;
                }
            }
            near = misses == 1 && missIsNear;
        } else {
            pass = false;
            near = false;
            for (int i = 0; i < ratios.size(); i++) {
                double r = ratios.get(i);
                if (r >= 1) pass = true;
                if (numeric.get(i) && r >= NEAR) near = true;
            }
        }

        Evaluation.Verdict v;
        if (blocked) v = Evaluation.Verdict.BAD;
        else if (pass) v = Evaluation.Verdict.GOOD;
        else if (near) v = Evaluation.Verdict.OK;
        else v = Evaluation.Verdict.BAD;

        // Nota 0–5: 2,5 = exatamente na meta, 5 = o dobro. Regra "qualquer" usa a melhor meta.
        double score;
        if (ratios.isEmpty()) {
            score = 2.5;
        } else if (p.matchAll) {
            double sum = 0;
            for (double r : ratios) sum += clamp(r, 0, 2);
            score = 2.5 * sum / ratios.size();
        } else {
            double best = 0;
            for (double r : ratios) best = Math.max(best, clamp(r, 0, 2));
            score = 2.5 * best;
        }
        if (blocked) score = Math.min(score, 1.0);
        score = Math.round(score * 10.0) / 10.0;

        Evaluation ev = new Evaluation(o, p.name, km, min, rsKm, rsH, cost, profit, profitPct, profitH,
                score, v, routeHit, reasons);
        ev.markKm = markOf(p.minRsPerKm > 0 ? rsKm / p.minRsPerKm : -1);
        ev.markHour = markOf(p.minRsPerHour > 0 ? rsH / p.minRsPerHour : -1);
        ev.markProfitHour = markOf(p.minProfitPerHour > 0 ? profitH / p.minProfitPerHour : -1);
        ev.markProfit = markOf(p.minProfitPct > 0 ? profitPct / p.minProfitPct : -1);
        ev.routeConfigured = !p.routeKeywords.isEmpty();
        ev.detourCostOnly = detourOnly;

        // Trânsito: o tempo estimado pelo app de corrida já considera o trânsito atual,
        // então a velocidade média da viagem mostra se a rota está travada.
        if (o.tripMin > 0 && o.tripKm > 0) {
            ev.tripSpeedKmh = o.tripKm / (o.tripMin / 60.0);
            if (ev.tripSpeedKmh < TRAFFIC_HEAVY_KMH) {
                ev.markTraffic = Evaluation.Mark.BAD;
                ev.trafficLabel = "pesado";
            } else if (ev.tripSpeedKmh < TRAFFIC_FREE_KMH) {
                ev.markTraffic = Evaluation.Mark.NEAR;
                ev.trafficLabel = "moderado";
            } else {
                ev.markTraffic = Evaluation.Mark.GOOD;
                ev.trafficLabel = "livre";
            }
        }
        return ev;
    }

    private static String mark(double ratio) {
        return ratio >= 1 ? "✔" : (ratio >= NEAR ? "≈" : "✘");
    }

    private static String f(String fmt, Object... args) {
        return String.format(BR, fmt, args);
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
