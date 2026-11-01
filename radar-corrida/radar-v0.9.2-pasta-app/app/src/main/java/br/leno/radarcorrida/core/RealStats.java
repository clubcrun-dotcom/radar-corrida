package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Números REAIS do motorista, a partir do histórico de ganhos importado da Uber.
 *
 * Observações honestas sobre a base:
 *  - O tempo e o km do histórico são só da viagem (sem o deslocamento até o passageiro e sem
 *    a espera). O R$/h aqui é, portanto, o teto; o ritmo do dia (quadro "Hoje") é o realista.
 *  - Pedágio é reembolso: sai do ganho. Promoções entram à parte.
 */
public final class RealStats {
    public int days, trips;
    public double earnings, minutes, km, promos, costTotal;
    public double perHour, netPerHour, avgPerDay, tripsPerDay;
    public int daysHitGoal;
    public double amPerHour, pmPerHour;
    public int amTrips, pmTrips;
    public double bestShortPerHour;   // corridas até 8 km
    public double longPerHour;        // corridas acima de 15 km

    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    public static RealStats of(List<HistoryParser.Trip> all, Settings s) {
        RealStats r = new RealStats();
        TreeMap<String, Double> perDay = new TreeMap<>();
        double amE = 0, amM = 0, pmE = 0, pmM = 0, shE = 0, shM = 0, lgE = 0, lgM = 0;
        double cost = s.costPerKm();
        for (HistoryParser.Trip t : all) {
            if (t.cancelled) continue;
            if (t.promo) {
                r.promos += t.fare;
                continue;
            }
            double e = t.earnings();
            r.trips++;
            r.earnings += e;
            r.minutes += t.minutes;
            r.km += t.km;
            r.costTotal += cost * t.km;
            Double prev = perDay.get(t.date);
            perDay.put(t.date, (prev == null ? 0 : prev) + e);
            if (t.hour() >= 0 && t.hour() < 12) { amE += e; amM += t.minutes; r.amTrips++; }
            else { pmE += e; pmM += t.minutes; r.pmTrips++; }
            if (t.km <= 8) { shE += e; shM += t.minutes; }
            if (t.km > 15) { lgE += e; lgM += t.minutes; }
        }
        r.days = perDay.size();
        if (r.minutes > 0) {
            r.perHour = r.earnings / (r.minutes / 60);
            r.netPerHour = (r.earnings - r.costTotal) / (r.minutes / 60);
        }
        if (r.days > 0) {
            r.avgPerDay = r.earnings / r.days;
            r.tripsPerDay = (double) r.trips / r.days;
        }
        for (double v : perDay.values()) if (v >= s.dailyGoal) r.daysHitGoal++;
        r.amPerHour = amM > 0 ? amE / (amM / 60) : 0;
        r.pmPerHour = pmM > 0 ? pmE / (pmM / 60) : 0;
        r.bestShortPerHour = shM > 0 ? shE / (shM / 60) : 0;
        r.longPerHour = lgM > 0 ? lgE / (lgM / 60) : 0;
        return r;
    }

    public String text(Settings s) {
        if (trips == 0) {
            return "Ainda sem histórico. Na Uber, abra Ganhos → Histórico de ganhos e role a lista: "
                    + "o Radar registra as corridas sozinho.";
        }
        List<String> l = new ArrayList<>();
        l.add(String.format(BR, "%d corridas em %d dias · média R$ %.0f/dia (%.1f corridas/dia)",
                trips, days, avgPerDay, tripsPerDay));
        l.add(String.format(BR, "Bateram a meta de R$ %.0f: %d de %d dias", s.dailyGoal, daysHitGoal, days));
        l.add(String.format(BR, "Ritmo em viagem: R$ %.0f/h bruto · R$ %.0f/h de lucro", perHour, netPerHour));
        if (amTrips > 0 && pmTrips > 0) {
            l.add(String.format(BR, "Manhã R$ %.0f/h (%d) · Noite R$ %.0f/h (%d)", amPerHour, amTrips, pmPerHour, pmTrips));
        }
        if (bestShortPerHour > 0 && longPerHour > 0) {
            l.add(String.format(BR, "Curtas (até 8 km) R$ %.0f/h · Longas (15+ km) R$ %.0f/h",
                    bestShortPerHour, longPerHour));
        }
        if (promos > 0) l.add(String.format(BR, "Promoções: R$ %.2f (fora das contas acima)", promos));
        // O que falta para a meta, em corridas, no padrão real do motorista.
        if (avgPerDay < s.dailyGoal && tripsPerDay > 0) {
            double perTrip = avgPerDay / tripsPerDay;
            l.add(String.format(BR, "Para fechar R$ %.0f no seu padrão (R$ %.0f por corrida): ~%.0f corridas/dia, "
                    + "hoje você faz %.1f.", s.dailyGoal, perTrip, Math.ceil(s.dailyGoal / perTrip), tripsPerDay));
        }
        l.add("(Tempo e km do histórico são só da viagem; o ritmo real do dia é menor.)");
        StringBuilder sb = new StringBuilder();
        for (String x : l) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        return sb.toString();
    }
}
