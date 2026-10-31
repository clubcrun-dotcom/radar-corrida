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

    public static Evaluation evaluate(Offer o, Settings s) {
        Profile p = s.profile != null ? s.profile : new Profile();

        double km = o.tripKm + (p.includePickup ? o.pickupKm : 0);
        double min = o.tripMin + (p.includePickup ? o.pickupMin : 0);

        double rsKm = km > 0 ? o.fare / km : 0;
        double hours = min / 60.0;
        double rsH = hours > 0 ? o.fare / hours : 0;

        double cost = s.costPerKm() * km;
        double profit = o.fare - cost;
        double profitPct = o.fare > 0 ? profit / o.fare * 100.0 : 0;
        double profitH = hours > 0 ? profit / hours : 0;

        String routeHit = p.routeMatch(o.destination);
        List<String> reasons = new ArrayList<>();

        // 1. Filtros eliminatórios
        boolean blocked = false;
        if (p.maxPickupKm > 0 && o.pickupKm > p.maxPickupKm) {
            blocked = true;
            reasons.add(f("✘ busca %.1f km > %.1f km", o.pickupKm, p.maxPickupKm));
        }
        if (p.maxPickupMin > 0 && o.pickupMin > p.maxPickupMin) {
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

        return new Evaluation(o, p.name, km, min, rsKm, rsH, cost, profit, profitPct, profitH,
                score, v, routeHit, reasons);
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
