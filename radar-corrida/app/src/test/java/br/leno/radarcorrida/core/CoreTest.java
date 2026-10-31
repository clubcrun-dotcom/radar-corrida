package br.leno.radarcorrida.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class CoreTest {
    private static final double EPS = 0.01;

    @Test
    public void uberStyle_pickupAndTrip() {
        Offer o = OfferParser.parse(Arrays.asList(
                "R$ 120,50", // ganhos do dia no topo: não pode virar a tarifa
                "UberX",
                "R$ 23,94",
                "4,92 (215)",
                "5 min (1,8 km) de distância",
                "Rua Doutor Lopes, São Gonçalo",
                "Viagem de 23 min (13,3 km)",
                "Av. Amaral Peixoto, Niterói",
                "Aceitar"));
        assertNotNull(o);
        assertEquals(23.94, o.fare, EPS);
        assertEquals(1.8, o.pickupKm, EPS);
        assertEquals(5, o.pickupMin, EPS);
        assertEquals(13.3, o.tripKm, EPS);
        assertEquals(23, o.tripMin, EPS);
        assertEquals("Av. Amaral Peixoto, Niterói", o.destination);
    }

    @Test
    public void surgeIncluded_isIgnored() {
        Offer o = OfferParser.parse(Arrays.asList(
                "R$ 18,50", "+R$ 3,00 incluído",
                "3 min (800 m) de distância", "Viagem de 15 min (7,2 km)"));
        assertNotNull(o);
        assertEquals(18.50, o.fare, EPS);
        assertEquals(0.8, o.pickupKm, EPS);
        assertEquals(7.2, o.tripKm, EPS);
    }

    @Test
    public void hoursAndMinutes() {
        Offer o = OfferParser.parse(Arrays.asList(
                "R$ 78,40", "6 min (2,3 km) de distância", "Viagem de 1 h 5 min (52,4 km)"));
        assertNotNull(o);
        assertEquals(65, o.tripMin, EPS);
        assertEquals(52.4, o.tripKm, EPS);
    }

    @Test
    public void style99_kmDotMin_withPerKmValue() {
        Offer o = OfferParser.parse(Arrays.asList(
                "R$ 15,20", "R$ 1,25/km", "2,1 km · 6 min", "10 km · 19 min"));
        assertNotNull(o);
        assertEquals(15.20, o.fare, EPS);
        assertEquals(2.1, o.pickupKm, EPS);
        assertEquals(6, o.pickupMin, EPS);
        assertEquals(10, o.tripKm, EPS);
        assertEquals(19, o.tripMin, EPS);
    }

    @Test
    public void minDotKm_singleLeg() {
        Offer o = OfferParser.parse(Arrays.asList("R$23,94", "23 min · 13,3 km"));
        assertNotNull(o);
        assertEquals(23.94, o.fare, EPS);
        assertEquals(0, o.pickupKm, EPS);
        assertEquals(13.3, o.tripKm, EPS);
    }

    @Test
    public void notAnOffer() {
        assertNull(OfferParser.parse(Arrays.asList("Você está online", "R$ 245,00 hoje")));
        assertNull(OfferParser.parse(Arrays.asList("Procurando viagens", "12 min")));
    }

    @Test
    public void absurdRatio_isRejected() {
        // R$ 1.245,00 numa viagem de 3 km = provavelmente leu o saldo, não a tarifa.
        assertNull(OfferParser.parse(Arrays.asList("R$ 1.245,00", "Viagem de 8 min (3 km)")));
    }

    @Test
    public void numberParsing() {
        assertEquals(1234.56, OfferParser.parseNumber("1.234,56"), EPS);
        assertEquals(13.3, OfferParser.parseNumber("13,3"), EPS);
        assertEquals(13.3, OfferParser.parseNumber("13.3"), EPS);
        assertEquals(1234, OfferParser.parseNumber("1.234"), EPS);
    }

    @Test
    public void calculator_matchesRotaProScreenshot() {
        // Print do anúncio: 23 min · 13,3 km → R$/km 1,80, R$/h 62, lucro 75%.
        Settings s = new Settings();
        s.fuelPrice = 6.30;
        s.kmPerLiter = 14;
        s.extraCostPerKm = 0;
        Evaluation e = Calculator.evaluate(new Offer(23.94, 0, 0, 13.3, 23), s);
        assertEquals(1.80, e.rsPerKm, EPS);
        assertEquals(62.45, e.rsPerHour, 0.05);
        assertEquals(75.0, e.profitPct, 0.5);
        assertEquals(Evaluation.Verdict.GOOD, e.verdict);
    }

    @Test
    public void calculator_pickupChangesTheVerdict() {
        Settings s = new Settings(); // perfil tempo integral: 1,50/km e 40/h, busca até 5 km
        Offer o = new Offer(12.00, 4.0, 12, 6.0, 14);
        s.profile.includePickup = false;
        assertEquals(Evaluation.Verdict.GOOD, Calculator.evaluate(o, s).verdict); // 2,00/km
        s.profile.includePickup = true;
        Evaluation e = Calculator.evaluate(o, s); // 1,20/km e 27,7/h
        assertEquals(1.20, e.rsPerKm, EPS);
        assertEquals(Evaluation.Verdict.BAD, e.verdict);
    }

    // ---- Perfil renda extra · trajeto (gasolina R$ 6,45, 14 km/l) ----

    private static Settings extraIncome() {
        Settings s = new Settings();
        s.fuelPrice = 6.45;
        s.kmPerLiter = 14;
        s.profile = Profile.extraIncomeRoute();
        s.profile.routeKeywords = Profile.parseKeywords("Colubande, Alcântara\nNiteroi");
        return s;
    }

    @Test
    public void breakEven_for90pctProfit() {
        assertEquals(4.61, extraIncome().breakEvenRsPerKm(90), EPS);
    }

    @Test
    public void extraIncome_shortHighMarginRide_accepts() {
        // Corrida curta de tarifa mínima: lucro alto em %, mesmo fora do trajeto.
        Evaluation e = Calculator.evaluate(new Offer(9.00, 0.8, 3, 1.0, 5, "Rua Qualquer, Tijuca"), extraIncome());
        assertEquals(Evaluation.Verdict.GOOD, e.verdict);
        assertNull(e.routeHit);
    }

    @Test
    public void extraIncome_onRoute_acceptsEvenWithLowerMargin() {
        Evaluation e = Calculator.evaluate(
                new Offer(20.00, 1.5, 5, 14.0, 25, "Rua Dr. Alfredo Backer, Colubandê, São Gonçalo"),
                extraIncome());
        assertEquals(Evaluation.Verdict.GOOD, e.verdict);
        assertEquals("Colubande", e.routeHit);
        assertEquals("ACEITAR · NO TRAJETO", e.verdictLabel());
    }

    @Test
    public void extraIncome_offRoute_75pct_rejects() {
        Evaluation e = Calculator.evaluate(new Offer(23.94, 0, 0, 13.3, 23, "Barra da Tijuca"), extraIncome());
        assertEquals(Evaluation.Verdict.BAD, e.verdict);
    }

    @Test
    public void extraIncome_farPickup_blocksEvenOnRoute() {
        Evaluation e = Calculator.evaluate(new Offer(20.00, 5.0, 12, 14.0, 25, "Colubandê"), extraIncome());
        assertEquals(Evaluation.Verdict.BAD, e.verdict);
    }

    @Test
    public void routeMatch_ignoresAccentsAndCase() {
        Profile p = new Profile();
        p.routeKeywords = Profile.parseKeywords("niteroi");
        assertEquals("niteroi", p.routeMatch("Av. Amaral Peixoto, NITERÓI"));
    }

    // ---- Várias ofertas ao mesmo tempo ----

    @Test
    public void multipleOffers_listIsSplitAndRanked() {
        java.util.List<Offer> offers = OfferParser.parseAll(Arrays.asList(
                "R$ 152,30",                    // ganhos do dia: bloco sem distância, descartado
                "R$ 18,00", "2,0 km · 5 min", "8,5 km · 15 min", "Icaraí, Niterói",
                "R$ 30,00", "1,0 km · 3 min", "20 km · 30 min", "Colubandê, São Gonçalo",
                "R$ 12,00", "4 km · 12 min", "5 km · 10 min", "Tijuca"));
        assertEquals(3, offers.size());
        assertEquals(18.00, offers.get(0).fare, EPS);
        assertEquals(8.5, offers.get(0).tripKm, EPS);
        assertEquals("Colubandê, São Gonçalo", offers.get(1).destination);
        assertEquals(12.00, offers.get(2).fare, EPS);

        Settings s = new Settings(); // tempo integral: 1,50/km e 40/h, busca ≤ 5 km
        java.util.List<Evaluation> ranked = Ranker.evaluateAndRank(offers, s);
        // 18,00 em 10,5 km/20 min = 1,71/km e 54/h → única que bate as duas metas
        assertEquals(18.00, ranked.get(0).offer.fare, EPS);
        assertEquals(Evaluation.Verdict.GOOD, ranked.get(0).verdict);
        assertEquals(Evaluation.Verdict.BAD, ranked.get(2).verdict);
    }

    @Test
    public void multipleOffers_routeWinsForExtraIncome() {
        java.util.List<Offer> offers = OfferParser.parseAll(Arrays.asList(
                "R$ 18,00", "2,0 km · 5 min", "8,5 km · 15 min", "Icaraí, Niterói",
                "R$ 30,00", "1,0 km · 3 min", "20 km · 30 min", "Colubandê, São Gonçalo"));
        Settings s = extraIncome();
        s.profile.routeKeywords = Profile.parseKeywords("Colubandê");
        java.util.List<Evaluation> ranked = Ranker.evaluateAndRank(offers, s);
        assertEquals(30.00, ranked.get(0).offer.fare, EPS);
        assertEquals("Colubandê", ranked.get(0).routeHit);
    }

    @Test
    public void singleUberCard_withEarningsPill_stillOneOffer() {
        java.util.List<Offer> offers = OfferParser.parseAll(Arrays.asList(
                "R$ 120,50", "UberX", "R$ 23,94", "5 min (1,8 km) de distância",
                "Rua Doutor Lopes, São Gonçalo", "Viagem de 23 min (13,3 km)", "Av. Amaral Peixoto, Niterói"));
        assertEquals(1, offers.size());
        assertEquals(23.94, offers.get(0).fare, EPS);
        assertEquals(1.8, offers.get(0).pickupKm, EPS);
    }

    @Test
    public void fareLastLayout_isSplitCorrectly() {
        java.util.List<Offer> offers = OfferParser.parseAll(Arrays.asList(
                "3 min (900 m)", "Viagem de 12 min (6 km)", "R$ 14,00",
                "4 min (1,2 km)", "Viagem de 30 min (22 km)", "R$ 41,00"));
        assertEquals(2, offers.size());
        assertEquals(14.00, offers.get(0).fare, EPS);
        assertEquals(6, offers.get(0).tripKm, EPS);
        assertEquals(41.00, offers.get(1).fare, EPS);
        assertEquals(22, offers.get(1).tripKm, EPS);
    }
}
