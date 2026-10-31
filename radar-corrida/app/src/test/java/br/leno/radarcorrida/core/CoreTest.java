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

    // ---- Tela real da Uber (31/10/2026, print do Leno) ----
    @Test
    public void realUber_2026_10_31() {
        java.util.List<String> t = Arrays.asList(
                "UberX", "R$ 17,48", "Est. de R$2,16/km",
                "5 min • 1.1 km",
                "R. Expedicionário Ernezito José das Chagas, Colubandê, São Gonçalo",
                "4,92 (1429)",
                "18 minutos • 7.0 km",
                "Travessa Natividade Batista, 45, Santa Catarina, São Gonçalo",
                "Tenho interesse");
        java.util.List<Offer> offers = OfferParser.parseAll(t);
        assertEquals(1, offers.size());
        Offer o = offers.get(0);
        assertEquals(17.48, o.fare, EPS);
        assertEquals(1.1, o.pickupKm, EPS);
        assertEquals(5, o.pickupMin, EPS);
        assertEquals(7.0, o.tripKm, EPS);
        assertEquals(18, o.tripMin, EPS);
        assertEquals("Travessa Natividade Batista, 45, Santa Catarina, São Gonçalo", o.destination);

        Settings s = new Settings();
        s.fuelPrice = 6.50; s.kmPerLiter = 13; s.extraCostPerKm = 0.22;
        s.profile = Profile.extraIncomeRoute();
        s.profile.minProfitPct = 70;
        Evaluation e = Calculator.evaluate(o, s);
        assertEquals(2.16, e.rsPerKm, EPS);   // bate com o "Est. de R$2,16/km" da própria Uber
        System.out.println("REAL: " + e.verdictLabel() + " | " + e.headline() + " | " + e.reasonsLine());
    }

    // ---- Texto como sai da leitura por imagem (OCR) ----
    @Test
    public void ocrStyle_bulletLostAndRS() {
        java.util.List<Offer> offers = OfferParser.parseAll(Arrays.asList(
                "15:23", "UberX", "RS 17,48", "Est. de R$2,16/km",
                "5 min 1.1 km", "R. Expedicionário Ernezito José das",
                "Chagas, Colubandê, São Gonçalo", "4,92 (1429)",
                "18 minutos . 7.0 km", "Travessa Natividade Batista, 45,",
                "Santa Catarina, São Gonçalo", "Tenho interesse"));
        assertEquals(1, offers.size());
        Offer o = offers.get(0);
        assertEquals(17.48, o.fare, EPS);
        assertEquals(1.1, o.pickupKm, EPS);
        assertEquals(7.0, o.tripKm, EPS);
        assertEquals(18, o.tripMin, EPS);
        assertEquals("Travessa Natividade Batista, 45, | Santa Catarina, São Gonçalo", o.destination);
    }

    @Test
    public void lowercaseRs_inAddress_isNotAFare() {
        assertNull(OfferParser.parse(Arrays.asList("Rua Flowers 45", "18 minutos • 7.0 km")));
    }

    @Test
    public void uberHomeScreen_isNotAnOffer() {
        assertEquals(0, OfferParser.parseAll(Arrays.asList(
                "Página inicial", "R$ 0,00", "HOJE", "Você está online",
                "+R$ 3,25", "+R$ 3", "1-4 min", "1-3 min", "1-5 min")).size());
    }

    // ---- Leituras por imagem REAIS (telas.txt de 31/10/2026, print da oferta na Galeria) ----

    private static Offer only(java.util.List<String> lines) {
        java.util.List<Offer> l = OfferParser.parseAll(lines);
        assertEquals(1, l.size());
        return l.get(0);
    }

    @Test
    public void realOcr_llKm_andThumbnailFare() {
        Offer o = only(Arrays.asList("15:50 O", "ll 30", "es", "Praça do Rocha", "PraçaColut", "2 UberX", "X",
                "R$ 17,48", "Est. de R$2,16/km", "5 min ll km",
                "R. Expedicionário Ernezito José das", "Chagas, Colubandê, São Gonçalo", "4,92 (1429)",
                "18 minutos 7.0 km", "Travessa Natividade Batista, 45,", "Santa Catarina, Säão Gonçalo",
                "Tenho interesse", "da Colina de", "Niterói", "R$ 1748"));
        assertEquals(17.48, o.fare, EPS);
        assertEquals(1.1, o.pickupKm, EPS);
        assertEquals(5, o.pickupMin, EPS);
        assertEquals(7.0, o.tripKm, EPS);
    }

    @Test
    public void realOcr_1lKm_70km_fareWithoutComma() {
        Offer o = only(Arrays.asList("15:51", "ll 30", "Praça Colu", "UberX", "X",
                "R$ 1748", "Est. de R$2,16/km", "5 min 1l km",
                "R. Expedicionário Ernezito José das", "Chagas, Colubandê, São Gonçalo", "* 4,92 (1429)",
                "18 minutos• 70 km", "Travessa Natividade Batista, 45,", "Santa Catarina, São Gonçalo",
                "Tenho interesse", "da Colina de", "T", "Niterói", "<"));
        assertEquals(17.48, o.fare, EPS);
        assertEquals(1.1, o.pickupKm, EPS);
        assertEquals(7.0, o.tripKm, EPS);
        assertEquals(18, o.tripMin, EPS);
    }

    @Test
    public void realOcr_l1Km_and_1lKm() {
        for (String pick : new String[]{"5 min l.1 km", "5 min 1.l km", "5 min ll km"}) {
            Offer o = only(Arrays.asList("2 UberX", "R$ 17,48", "Est. de R$2,16/km", pick,
                    "Chagas, Colubandê, São Gonçalo", "18 minutos 7.0 km", "Santa Catarina, São Gonçalo"));
            assertEquals(pick, 1.1, o.pickupKm, EPS);
            assertEquals(pick, 7.0, o.tripKm, EPS);
        }
    }

    @Test
    public void pickupLineMissing_isRebuiltFromUberEstimate() {
        Offer o = only(Arrays.asList("UberX", "R$ 17,48", "Est. de R$2,16/km",
                "Chagas, Colubandê, São Gonçalo", "18 minutos 7.0 km", "Santa Catarina, São Gonçalo"));
        assertEquals(7.0, o.tripKm, EPS);
        assertEquals(1.1, o.pickupKm, 0.06);
        assertEquals(3, o.pickupMin, 0.6);   // estimado pela velocidade da viagem
    }

    @Test
    public void realOcr_finalVerdictIsAvaliar() {
        Offer o = only(Arrays.asList("R$ 1748", "Est. de R$2,16/km", "5 min 1l km",
                "Chagas, Colubandê, São Gonçalo", "18 minutos• 70 km", "Santa Catarina, São Gonçalo"));
        Settings s = new Settings();
        s.fuelPrice = 6.50; s.kmPerLiter = 13; s.extraCostPerKm = 0.22;
        s.profile = Profile.extraIncomeRoute();
        s.profile.minProfitPct = 70;
        Evaluation e = Calculator.evaluate(o, s);
        assertEquals(2.16, e.rsPerKm, EPS);
        assertEquals(Evaluation.Verdict.OK, e.verdict);
    }

    @Test
    public void ocrNormalization() {
        assertEquals("5 min 11 km", OfferParser.normalizeOcr("5 min ll km"));
        assertEquals("5min 11 km", OfferParser.normalizeOcr("Sminll km"));
        assertEquals("5 min 1.1 km", OfferParser.normalizeOcr("5 min 1.l km"));
        assertEquals("Rua Total, 45", OfferParser.normalizeOcr("Rua Total, 45"));
    }

    @Test
    public void tilesAndTraffic() {
        Settings s = new Settings();
        s.fuelPrice = 6.50; s.kmPerLiter = 13; s.extraCostPerKm = 0.22;
        s.profile = Profile.extraIncomeRoute();
        s.profile.minProfitPct = 70;
        Evaluation e = Calculator.evaluate(new Offer(17.48, 1.1, 5, 7.0, 18), s);
        assertEquals(Evaluation.Mark.NEAR, e.markProfit);   // 67% de 70%
        assertEquals(Evaluation.Mark.NONE, e.markKm);       // sem meta de R$/km
        assertEquals(23.3, e.tripSpeedKmh, 0.1);
        assertEquals("moderado", e.trafficLabel);
        Evaluation jam = Calculator.evaluate(new Offer(20, 1, 5, 5.0, 25), s);   // 12 km/h
        assertEquals("pesado", jam.trafficLabel);
    }
}
