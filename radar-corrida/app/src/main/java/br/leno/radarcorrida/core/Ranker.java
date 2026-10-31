package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordena ofertas simultâneas da melhor para a pior:
 *  1. veredito (ACEITAR > AVALIAR > RECUSAR);
 *  2. nota do perfil (quão acima da meta);
 *  3. lucro por hora (desempate: o que paga melhor o seu tempo).
 */
public final class Ranker {
    private Ranker() {}

    public static List<Evaluation> rank(List<Evaluation> evs) {
        List<Evaluation> l = new ArrayList<>(evs);
        Collections.sort(l, (a, b) -> {
            int va = order(a.verdict);
            int vb = order(b.verdict);
            if (va != vb) return Integer.compare(va, vb);
            if (a.score != b.score) return Double.compare(b.score, a.score);
            return Double.compare(b.profitPerHour, a.profitPerHour);
        });
        return l;
    }

    public static List<Evaluation> evaluateAndRank(List<Offer> offers, Settings s) {
        List<Evaluation> evs = new ArrayList<>();
        for (Offer o : offers) evs.add(Calculator.evaluate(o, s));
        return rank(evs);
    }

    private static int order(Evaluation.Verdict v) {
        switch (v) {
            case GOOD: return 0;
            case OK: return 1;
            default: return 2;
        }
    }
}
