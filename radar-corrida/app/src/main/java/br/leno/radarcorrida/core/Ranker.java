package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordena ofertas simultâneas da melhor para a pior:
 *  1. veredito (ACEITAR > AVALIAR > RECUSAR);
 *  2. lucro por hora (o que paga melhor o seu tempo);
 *  3. nota do perfil (desempate).
 */
public final class Ranker {
    private Ranker() {}

    public static List<Evaluation> rank(List<Evaluation> evs) {
        List<Evaluation> l = new ArrayList<>(evs);
        Collections.sort(l, (a, b) -> {
            int va = order(a.verdict);
            int vb = order(b.verdict);
            if (va != vb) return Integer.compare(va, vb);
            if (a.profitPerHour != b.profitPerHour) return Double.compare(b.profitPerHour, a.profitPerHour);
            return Double.compare(b.score, a.score);
        });
        return l;
    }

    /** Mesmo critério, mas pelo R$/hora bruto (motorista com meta em faturamento). */
    public static List<Evaluation> rankGross(List<Evaluation> evs) {
        List<Evaluation> l = new ArrayList<>(evs);
        Collections.sort(l, (a, b) -> {
            int va = order(a.verdict);
            int vb = order(b.verdict);
            if (va != vb) return Integer.compare(va, vb);
            if (a.rsPerHour != b.rsPerHour) return Double.compare(b.rsPerHour, a.rsPerHour);
            return Double.compare(b.score, a.score);
        });
        return l;
    }

    public static List<Evaluation> evaluateAndRank(List<Offer> offers, Settings s) {
        List<Evaluation> evs = new ArrayList<>();
        for (Offer o : offers) evs.add(Calculator.evaluate(o, s));
        return s.netGoal ? rank(evs) : rankGross(evs);
    }

    private static int order(Evaluation.Verdict v) {
        switch (v) {
            case GOOD: return 0;
            case OK: return 1;
            default: return 2;
        }
    }
}
