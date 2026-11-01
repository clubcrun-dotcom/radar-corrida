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
        s.profile.minProfitPerHour = 0;   // estes testes cobrem a regra de lucro %
        s.profile.minProfitPct = 90;
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
        s.profile.minProfitPerHour = 0;
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
        s.profile.minProfitPerHour = 0;
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
        s.profile.minProfitPerHour = 0;
        s.profile.minProfitPct = 70;
        Evaluation e = Calculator.evaluate(new Offer(17.48, 1.1, 5, 7.0, 18), s);
        assertEquals(Evaluation.Mark.NEAR, e.markProfit);   // 67% de 70%
        assertEquals(Evaluation.Mark.NONE, e.markKm);       // sem meta de R$/km
        assertEquals(23.3, e.tripSpeedKmh, 0.1);
        assertEquals("moderado", e.trafficLabel);
        Evaluation jam = Calculator.evaluate(new Offer(20, 1, 5, 5.0, 25), s);   // 12 km/h
        assertEquals("pesado", jam.trafficLabel);
    }

    // ---- Ofertas REAIS da Uber lidas por imagem no turno de 31/10 (16:27–18:09) ----

    private static void checkReal(double fare, double pKm, double tKm, String... lines) {
        Offer o = only(Arrays.asList(lines));
        assertEquals(java.util.Arrays.toString(lines), fare, o.fare, 0.02);
        assertEquals(java.util.Arrays.toString(lines), pKm, o.pickupKm, 0.06);
        assertEquals(java.util.Arrays.toString(lines), tKm, o.tripKm, 0.06);
    }

    @Test
    public void realShift_2026_10_31() {
        checkReal(21.81, 2.4, 11.2, "16:27 2min", "Radar de Viagens 2", "1-2 min", "uberX", "X",
                "R$ 21,81l", "Est. de R$1,60/km", "8 8 min 2.4 km", "Rua Expedicionário Osvaldo",
                "Conceição, Jardim Nova República,", "São Gonçalo", "* 4,94 (689) OVerificade",
                "16 minutos•11.2 km", "R. Joaquim Lavoura, 353, Monjolos,", "Monjolos", "Tenho interesse");
        checkReal(21.81, 2.4, 11.2, "Radar de Viagens2", "2 UberX", "X", "R$ 21,81", "Est. de R$L,60/km",
                "a 8 min 2.4 km", "Rua Expedicionário Osvaldo", "Conceição, Jardim Nova República,",
                "São Gonçalo", "* 4,94 (689)", "OVerificado", ". 16 minutos • 1l.2 km",
                "R. Joaquim Lavoura, 353, Monjolos,", "Monjolos", "Tenho interesse");
        checkReal(21.81, 2.4, 11.2, "2 UberX", "R$ 21,81", "Est. de R$L,60/km", "a 8 min 2.4 km",
                "São Gonçalo", "O 16 minutos l1.2 km", "Monjolos", "Tenho interesse", "<");
        checkReal(8.77, 2.6, 1.7, "16:27 D→•", "NlG25", "UberX", "X", "R$ 8,77", "Est. de R$2,04/km",
                "2 9 min 2.6 knm", "Estr. Ver. Luiz Carlos Silva, Galo", "Branco, Rio de Janeiro",
                "* 4,97 (286)", "6 minutos •1.7 km", "Av. Jornalista Roberto Marinho, 221,",
                "Mutondo, São Gonçalo", "Tenho interesse", "Ninjdb");
        checkReal(11.87, 1.9, 5.2, "UberX", "X", "R$ 11,87", "Est. de R$1,67/kmn", "6 min •1.9 km",
                "Av. Jornalista Roberto Marinho,", "Mutondo, São Gonçalo", "* 4,85 (134)",
                "15 minutos 5.2 km", "Rua Padre Nicolau Luís, 203, Porto", "do Rosa, São Gonçalo", "Tenho interesse");
        checkReal(25.98, 0.7, 9.5, "Praça do Barenco", "São Gonçalo", "Praça Colubandê", "2 UberX Exclusivo",
                "X", "R$ 25,98", "Est. de R$2,55/km", "8 4 min 0.7 km", "R. Antônio Barradas, Colubandê,",
                "São Gonçalo", "4,98 (87)", "24 minutos 9.5 km", "R. Dr. Porciúncula, 2424, Venda da",
                "Cruz, São Gonçalo", "de", "Aceitar");
        checkReal(9.95, 2.4, 1.6, "UberX", "X", "R$ 9,95", "Est. de R$2,49/km", "aç", "e 9 min 2.4 km",
                "Rua Pastor Avelino de Souza, Jardim", "Nova República, São Gonçalo", "* 4,91 (251)",
                "Verificado", "5 minutos•l.6 km", "Avenida Doutor Eugênio Borges,",
                "850, Tribobó lI, São Gonçalo", "ai", "rk", "Tenho interesse");
        checkReal(13.91, 1.2, 2.5, "DA MARICA", "Ayena", "al", "8", "UberX", "X", "no", "R$ 13,9l",
                "Est. de R$3,76/km", "e 4 min •l.2 km", "Rua Teresa Martins, Colubandê, Säo", "Gonçalo",
                "4,80 (520)", "l parada", "15 minutos 2.5 km", "Estrada Vereador Luiz Carlos da",
                "Silva, 338, São Gonçalo, São", "ite", "Gonçalo", "pa", "Tenho interesse");
        checkReal(9.48, 1.9, 2.2, "uberX", "X", "R$ 9,48", "Est. de R$2,31/km", "6 min •l9 km",
                "Al. Engenheiro Joao Corner, Tribobó", "1, São Gonçalo", "* 4,86 (1562) O Verificado",
                "5 minutos• 2.2 km", "Rua Capitão Juvenal Figueiredo,", "235, Colubandê, São Gonçalo",
                "Tenho interesse");
    }

    @Test
    public void overlayFooterText_isNotAnOffer() {
        // Texto do rodapé/quadros da nova etiqueta, caso a leitura por imagem o capture.
        assertEquals(0, OfferParser.parseAll(Arrays.asList(
                "AVALIAR", "R$/KM", "2,16", "R$/HORA", "46", "LUCRO", "67%", "R$ 11,65", "TRÂNSITO",
                "23 km/h", "moderado", "✘ FORA DA ROTA · Santa Catarina, São Gonçalo",
                "R$ 17,48 · 8,1 km em 23 min · busca 1,1 km em 5 min")).size());
    }

    // ---- Lucro por hora (meta do Leno: R$ 100 em 3 h) ----

    @Test
    public void dailyGoalMath() {
        Settings s = new Settings();
        s.dailyGoal = 100; s.dailyHours = 3; s.busyPct = 60;
        assertEquals(33.33, s.neededProfitPerHourOverall(), 0.01);
        assertEquals(55.56, s.neededProfitPerHourPerRide(), 0.01);
    }

    @Test
    public void profitPerHourRule_andRanking_onRealShift() {
        Settings s = new Settings();
        s.fuelPrice = 6.50; s.kmPerLiter = 13; s.extraCostPerKm = 0.22;
        s.profile = Profile.extraIncomeRoute();   // QUALQUER: lucro/h >= 45 ou trajeto
        s.profile.minProfitPerHour = 38;
        java.util.List<Offer> real = Arrays.asList(
                new Offer(21.81, 2.4, 8, 11.2, 16), new Offer(8.77, 2.6, 9, 1.7, 6),
                new Offer(25.98, 0.7, 4, 9.5, 24), new Offer(9.95, 2.4, 9, 1.6, 5));
        java.util.List<Evaluation> r = Ranker.evaluateAndRank(real, s);
        assertEquals(25.98, r.get(0).offer.fare, EPS);              // maior lucro/h do turno (R$ 40)
        assertEquals(Evaluation.Verdict.GOOD, r.get(0).verdict);
        assertEquals(Evaluation.Mark.GOOD, r.get(0).markProfitHour);
        assertEquals(Evaluation.Verdict.BAD, r.get(r.size() - 1).verdict);   // R$ 8,77: R$ 23/h
    }

    // ---- Checagem de realidade da meta (dados do turno real de 31/10) ----

    private static java.util.List<GoalCheck.Seen> realSeen() {
        double[][] o = {{21.81, 13.6, 24}, {8.77, 4.3, 15}, {11.87, 7.1, 21}, {25.98, 10.2, 28},
                {9.95, 4.0, 14}, {13.91, 3.7, 19}, {9.48, 4.1, 11}, {17.48, 8.1, 23}};
        java.util.List<GoalCheck.Seen> l = new java.util.ArrayList<>();
        for (double[] x : o) l.add(new GoalCheck.Seen(x[0], x[1], x[2]));
        return l;
    }

    private static Settings lenoCar() {
        Settings s = new Settings();
        s.fuelPrice = 6.50; s.kmPerLiter = 13; s.extraCostPerKm = 0.22;
        s.dailyGoal = 100; s.dailyHours = 3; s.busyPct = 60;
        return s;
    }

    @Test
    public void realityCheck_gross100_isTight() {
        GoalCheck g = GoalCheck.run(lenoCar(), false, realSeen());
        System.out.println("BRUTO:\n" + g.text());
        assertEquals(55.56, g.neededPerRide, 0.01);
        assertEquals(8, g.sample);
        assertEquals(GoalCheck.Status.TIGHT, g.status);
    }

    @Test
    public void realityCheck_net100_isUnlikely() {
        GoalCheck g = GoalCheck.run(lenoCar(), true, realSeen());
        System.out.println("LIQUIDO:\n" + g.text());
        assertEquals(GoalCheck.Status.UNLIKELY, g.status);
    }

    @Test
    public void realityCheck_noData() {
        assertEquals(GoalCheck.Status.NO_DATA,
                GoalCheck.run(lenoCar(), false, new java.util.ArrayList<GoalCheck.Seen>()).status);
    }

    @Test
    public void csvReader_allVersions_dedupe_noGallery() {
        String csv = "data_hora;app;valor;busca_km;busca_min;viagem_km;viagem_min;total_km;total_min;rs_km;rs_h;custo;lucro;lucro_pct;nota;veredito\n"
                + "2026-10-31 15:00:01;com.ubercab.driver;10,00;1,00;3;4,00;10;5,00;13;2,00;46,15;3,60;6,40;64,0;2,50;AVALIAR\n"
                + "2026-10-31 16:27:12;com.ubercab.driver [imagem];Renda extra;1;1;21,81;2,40;8;11,20;16;13,60;24;1,60;54,53;9,79;12,02;55,1;1,8;BAD;nao;Monjolos\n"
                + "2026-10-31 16:27:13;com.ubercab.driver [imagem];Renda extra;1;1;21,81;2,40;8;11,20;16;13,60;24;1,60;54,53;9,79;12,02;55,1;1,8;BAD;nao;Monjolos\n"
                + "2026-10-31 15:50:01;com.sec.android.gallery3d [imagem];Renda extra;1;1;17,48;1,10;5;7,00;18;8,10;23;2,16;45,60;5,83;11,65;66,6;2,4;OK;nao;Santa Catarina\n";
        java.util.List<GoalCheck.Seen> l = GoalCheck.fromCsv(csv);
        assertEquals(2, l.size());
        assertEquals(21.81, l.get(1).fare, EPS);
        assertEquals(13.6, l.get(1).km, EPS);
        assertEquals(24, l.get(1).min, EPS);
    }

    // ---- Acumulado do dia (textos reais da tela inicial da Uber) ----

    @Test
    public void dayTracker_twoPeakSessions() {
        DayTracker d = new DayTracker();
        long t0 = 1_000_000L;
        java.util.List<String> online = Arrays.asList("Página inicial", "R$ 0,00", "HOJE", "Você está online");
        d.onScreen("2026-11-02", t0, online);
        assertEquals(0.0, d.earnings, EPS);
        // 1 h de manhã, com corridas: telas a cada 5 min e lacuna de 30 min no Waze (conta 20)
        long t = t0;
        for (int i = 0; i < 6; i++) { t += 5 * 60_000; d.onScreen("2026-11-02", t, Arrays.asList("Procurando viagens")); }
        t += 30 * 60_000;
        d.onScreen("2026-11-02", t, Arrays.asList("Página inicial", "R$ 48,50", "HOJE", "Você está online"));
        assertEquals(48.50, d.earnings, EPS);
        assertEquals(50, d.onlineMs / 60_000);
        // vai offline
        t += 60_000;
        d.onScreen("2026-11-02", t, Arrays.asList("Você está offline"));
        long morning = d.onlineMs;
        // 10 h depois, offline o tempo todo: não conta
        t += 10 * 3_600_000L;
        d.onScreen("2026-11-02", t, Arrays.asList("Página inicial", "R$ 48,50", "HOJE", "Você está online"));
        assertEquals(morning, d.onlineMs);
        assertEquals(51.5, d.remaining(100), EPS);
        System.out.println(d.summary(100));
        assertEquals(true, d.extraMinutes(100) > 0);
    }

    @Test
    public void dayTracker_resetsOnNewDay() {
        DayTracker d = new DayTracker();
        d.onScreen("2026-11-02", 1000, Arrays.asList("R$ 80,00", "HOJE", "Você está online"));
        d.onScreen("2026-11-03", 2000, Arrays.asList("Você está online"));
        assertEquals(-1.0, d.earnings, EPS);
        assertEquals(0, d.onlineMs);
    }

    // ---- Tela "Histórico de ganhos" da Uber NÃO é oferta (falso positivo de 31/10, R$ 193/h) ----
    @Test
    public void earningsHistory_isNotAnOffer() {
        java.util.List<String> ocr = Arrays.asList("21:21", "42", "Historico de ganhos",
                "Prioridade9 min 9 segundos 2.02", "18:24", "km", "9 R$ 1,75 Preço dinâmico",
                "R$ 180,00", "6:59", "Promoção", "R$ 56,19 ↑ Aumentou", "5:35",
                "Uber X.·lh17 min 36.28 km", "Página inicial Descubra", "Ganhos", "Caixa de en...", "Menu");
        assertEquals(false, OfferParser.hasOfferMarker(ocr));
        assertEquals("Uber X.·1h17 min 36.28 km", OfferParser.normalizeOcr("Uber X.·lh17 min 36.28 km"));
        assertEquals(true, OfferParser.hasOfferMarker(Arrays.asList("R$ 9,95", "Est. de R$2,49/km", "Tenho interesse")));
    }

    // ---- Importação do "Histórico de ganhos" (texto real da acessibilidade, 31/10) ----
    private static java.util.List<String> historyScreen() {
        return Arrays.asList("Histórico de ganhos", "Semana passada",
                "R$ 12,30", "\u2066Uber X\u2069 · \u206618 min\u2069 · \u20665.10 km\u2069", "7:59",   // sem dia: ignorada
                "qua., 28 de out.",
                "R$ 42,76", "Aumentou", "\u2066Uber X\u2069 · \u206648 min 35 segundos\u2069 · \u206625.84 km\u2069", "6:28",
                "R$ 6,60 Pedágio", "R. Dr. Alfredo Backer, São Gonçalo", "Centro, Rio de Janeiro",
                "R$ 15,10", "\u2066Uber X\u2069 · \u2066Você cancelou\u2069", "5:10",
                "ter., 20 de out.",
                "R$ 21,90", "\u2066Prioridade\u2069 · \u20661 h 5 min\u2069 · \u206630.2 km\u2069", "19:12",
                "R$ 5,50 Preço dinâmico",
                "R$ 180,00", "Promoção", "6:59",
                "R$ 9,99", "\u2066Uber X\u2069 · \u206612 min");                                       // cortada na borda
    }

    @Test
    public void history_parsesRealUberScreen() {
        java.util.List<String> screen = historyScreen();
        assertEquals(true, HistoryParser.isHistoryScreen(screen));
        java.util.List<HistoryParser.Trip> t = HistoryParser.parse(screen, 2026, 10);
        assertEquals(4, t.size());
        HistoryParser.Trip a = t.get(0);
        assertEquals("2026-10-28", a.date);
        assertEquals("6:28", a.time);
        assertEquals("Uber X", a.kind);
        assertEquals(42.76, a.fare, EPS);
        assertEquals(48.58, a.minutes, EPS);
        assertEquals(25.84, a.km, EPS);
        assertEquals(6.60, a.toll, EPS);
        assertEquals(36.16, a.earnings(), EPS);
        assertEquals(true, t.get(1).cancelled);
        assertEquals(true, t.get(3).promo);
        assertEquals("2026-10-20", t.get(3).date);
        assertEquals(65.0, t.get(2).minutes, EPS);
        assertEquals(5.50, t.get(2).surge, EPS);
        assertEquals(19, t.get(2).hour());
    }

    @Test
    public void history_decemberSeenInJanuary_isLastYear() {
        java.util.List<HistoryParser.Trip> t = HistoryParser.parse(Arrays.asList("Histórico de ganhos",
                "qui., 31 de dez.", "R$ 20,00", "Uber X · 20 min · 10.0 km", "22:10"), 2027, 1);
        assertEquals("2026-12-31", t.get(0).date);
    }

    @Test
    public void history_csvRoundTrip() {
        java.util.List<HistoryParser.Trip> t = HistoryParser.parse(historyScreen(), 2026, 10);
        StringBuilder csv = new StringBuilder(HistoryParser.CSV_HEADER);
        for (HistoryParser.Trip x : t) csv.append(HistoryParser.toCsv(x));
        java.util.List<HistoryParser.Trip> back = HistoryParser.fromCsv(csv.toString());
        assertEquals(t.size(), back.size());
        assertEquals(t.get(0).key(), back.get(0).key());
        assertEquals(6.60, back.get(0).toll, EPS);
        assertEquals(true, back.get(3).promo);
    }

    @Test
    public void realStats_excludePromoTollAndCancel() {
        Settings s = lenoCar();
        RealStats r = RealStats.of(HistoryParser.parse(historyScreen(), 2026, 10), s);
        System.out.println("REAL:\n" + r.text(s));
        assertEquals(2, r.trips);                 // 28/10 6:28 e 20/10 19:12
        assertEquals(2, r.days);
        assertEquals(180.0, r.promos, EPS);
        assertEquals(36.16 + 21.90, r.earnings, EPS);
        assertEquals(29.03, r.avgPerDay, EPS);
        assertEquals(0, r.daysHitGoal);
        assertEquals(1, r.amTrips);
        assertEquals(1, r.pmTrips);
    }

    // ---- Corrida no trajeto: só o desvio até o passageiro custa ----
    @Test
    public void detourOnly_onRoute_chargesOnlyPickup() {
        Settings s = lenoCar();
        s.profile.includePickup = true;
        s.profile.routeKeywords = Profile.parseKeywords("Centro");
        Offer o = new Offer(30.00, 2.0, 5, 20.0, 35, "Av. Rio Branco, Centro, Rio de Janeiro");
        Evaluation full = Calculator.evaluate(o, s);
        s.profile.routeDetourCostOnly = true;
        Evaluation det = Calculator.evaluate(o, s);
        assertEquals(true, det.detourCostOnly);
        assertEquals(full.cost * 2.0 / 22.0, det.cost, EPS);
        assertEquals(true, det.profit > full.profit);
        // Fora do trajeto, a opção não muda nada.
        Evaluation off = Calculator.evaluate(new Offer(30.00, 2.0, 5, 20.0, 35, "Barra da Tijuca"), s);
        assertEquals(false, off.detourCostOnly);
        assertEquals(full.cost, off.cost, EPS);
    }

    // ---- Rolagem real do histórico (telas.txt 31/10 21:20–21:21): o cabeçalho do dia some por cima ----
    private static java.util.List<java.util.List<String>> realScroll() {
        String h = "Histórico de ganhos";
        return Arrays.asList(
            Arrays.asList("Voltar", h, "Compacto", "ter., 20 de out.",
                "R$ 40,94", "\u2066Uber X\u2069 · \u206642 min 10 segundos\u2069 · \u206622.95 km\u2069", "19:41",
                "R$ 2,75 Preço dinâmico", "R$ 6,60 Pedágio", "Rua Senador Dantas, Centro - Rio de Janeiro - RJ, 20031-914, Brasil",
                "R$ 18,82", "\u2066Uber X\u2069 · \u206614 min 8 segundos\u2069 · \u20663.31 km\u2069", "19:03", "R$ 11,00 Preço dinâmico",
                "R$ 18,65", "\u2066Prioridade\u2069 · \u206613 min 9 segundos\u2069 · \u20662.80 km\u2069", "18:53", "R$ 9,75 Preço dinâmico",
                "Página inicial", "Caixa de entrada, 10 novas notificações", "1-18 min"),
            Arrays.asList("Voltar", h, "Compacto",
                "R$ 18,65", "\u2066Prioridade\u2069 · \u206613 min 9 segundos\u2069 · \u20662.80 km\u2069", "18:53", "R$ 9,75 Preço dinâmico",
                "R$ 10,64", "\u2066Prioridade\u2069 · \u20669 min 9 segundos\u2069 · \u20662.02 km\u2069", "18:24", "R$ 1,75 Preço dinâmico",
                "R$ 180,00", "Promoção", "6:59",
                "R$ 56,19", "Aumentou", "\u2066Uber X\u2069 · \u20661 h 17 min\u2069 · \u206636.28 km\u2069", "5:35",
                "Rua Quintiliano da Fonseca, Colubandê - São Gonçalo - RJ, 24451-255, Brasil", "Página inicial", "1-6 min"),
            Arrays.asList("Voltar", h, "Compacto",
                "R$ 10,64", "\u2066Prioridade\u2069 · \u20669 min 9 segundos\u2069 · \u20662.02 km\u2069", "18:24",
                "R$ 180,00", "Promoção", "6:59",
                "R$ 56,19", "Aumentou", "\u2066Uber X\u2069 · \u20661 h 17 min\u2069 · \u206636.28 km\u2069", "5:35",
                "sex., 16 de out.", "R$ 0,00", "Uber X · Você cancelou", "16:45", "Página inicial"),
            Arrays.asList("Voltar", h, "Compacto",
                "R$ 0,00", "Uber X · Você cancelou", "16:45",
                "R$ 22,10", "\u2066Prioridade\u2069 · \u206619 min 47 segundos\u2069 · \u20667.71 km\u2069", "6:05", "R$ 1,50 Preço dinâmico",
                "R$ 25,06", "\u2066Uber X\u2069 · \u206617 min 14 segundos\u2069 · \u206614.96 km\u2069", "5:42",
                "Página inicial", "Caixa de entrada, 10 novas notificações", "1-5 min"));
    }

    @Test
    public void history_scroll_inheritsDateWhenHeaderScrollsAway() {
        java.util.Map<String, String> known = new java.util.HashMap<>();
        java.util.Map<String, HistoryParser.Trip> got = new java.util.LinkedHashMap<>();
        for (java.util.List<String> screen : realScroll()) {
            assertEquals(0, OfferParser.parseAll(screen).size());          // histórico nunca vira oferta
            for (HistoryParser.Trip t : HistoryParser.parse(screen, 2026, 10, known)) got.put(t.key(), t);
        }
        assertEquals(9, got.size());
        assertEquals(true, got.containsKey("2026-10-20 18:24 10.64"));
        assertEquals(true, got.containsKey("2026-10-20 6:59 180.00"));
        assertEquals(77.0, got.get("2026-10-20 5:35 56.19").minutes, EPS);
        assertEquals(true, got.get("2026-10-16 16:45 0.00").cancelled);
        assertEquals(true, got.containsKey("2026-10-16 6:05 22.10"));
        assertEquals(true, got.containsKey("2026-10-16 5:42 25.06"));
        // Sem memória entre telas, as que perderam o cabeçalho ficariam de fora.
        int alone = 0;
        java.util.Set<String> k = new java.util.HashSet<>();
        for (java.util.List<String> screen : realScroll())
            for (HistoryParser.Trip t : HistoryParser.parse(screen, 2026, 10)) if (k.add(t.key())) alone++;
        assertEquals(4, alone);

        RealStats r = RealStats.of(new java.util.ArrayList<>(got.values()), lenoCar());
        assertEquals(7, r.trips);
        assertEquals(180.0, r.promos, EPS);
        System.out.println("ROLAGEM REAL:\n" + r.text(lenoCar()));
    }

    // ---- Cabeçalho do dia que não veio no texto da tela (caso real: 45,44 de 28/08 gravada como 01/09) ----
    @Test
    public void history_missingDayHeader_isDetectedByTimeOrder() {
        java.util.List<String> broken = Arrays.asList("Histórico de ganhos", "ter., 1 de set.",
                "R$ 17,31", "Uber X · 11 min 48 segundos · 5.58 km", "19:25",
                "R$ 61,43", "Uber X · 1 h 5 min · 30.01 km", "18:16", "R$ 6,60 Pedágio",
                // aqui faltou "sex., 28 de ago."
                "R$ 45,44", "Uber X · 1 h 16 min · 25.62 km", "19:13",
                "ter., 25 de ago.",
                "R$ 23,29", "Uber X · 23 min 30 segundos · 9.98 km", "18:43");
        java.util.Map<String, String> known = new java.util.HashMap<>();
        java.util.List<HistoryParser.Trip> t = HistoryParser.parse(broken, 2026, 10, known);
        assertEquals(3, t.size());                                   // a 45,44 fica de fora (data incerta)
        for (HistoryParser.Trip x : t) assertEquals(false, x.fare == 45.44);
        assertEquals("2026-08-25", t.get(2).date);

        // Se a mesma corrida já foi vista com o cabeçalho certo, ela entra com a data certa.
        java.util.List<HistoryParser.Trip> ok = HistoryParser.parse(Arrays.asList("Histórico de ganhos",
                "sex., 28 de ago.", "R$ 45,44", "Uber X · 1 h 16 min · 25.62 km", "19:13"), 2026, 10, known);
        assertEquals("2026-08-28", ok.get(0).date);
        java.util.List<HistoryParser.Trip> again = HistoryParser.parse(broken, 2026, 10, known);
        assertEquals(4, again.size());
        assertEquals("2026-08-28", again.get(2).date);
    }

    @Test
    public void history_dedupe_keepsFirst() {
        String csv = HistoryParser.CSV_HEADER
                + "2026-08-28;19:13;Uber X;45,44;76,0;25,62;8,00;6,60;0,00;nao;nao\n"
                + "2026-08-25;18:43;Uber X;23,29;23,5;9,98;5,50;0,00;0,00;nao;nao\n"
                + "2026-09-01;19:13;Uber X;45,44;76,0;25,62;8,00;6,60;0,00;nao;nao\n";
        java.util.List<HistoryParser.Trip> t = HistoryParser.dedupe(HistoryParser.fromCsv(csv));
        assertEquals(2, t.size());
        assertEquals("2026-08-28", t.get(0).date);
    }

    // ---- Quadros reais de 31/10 22:54–22:58 (SG): busca e viagem trocavam entre quadros ----
    private static Offer frame(String... l) { return only(Arrays.asList(l)); }

    @Test
    public void ocr_tripLineTypos_keepPickupAndTripStable() {
        String[] head = {"2 UberX", "X", "R$ 19,78", "Est. de R$2,79/km", "9 +R$ 1,50 incluído", "e 6 min•1.9 km",
                "Av. Maricá, Mutondo, Rio de Janeiro", "* 4,99 (129)", "l parada"};
        String[][] trips = {{"O 18 minutos5.2 km"}, {"18 minutos•5.2 km"}, {"O 18 nminutos •5.2 km"}};
        for (String[] t : trips) {
            java.util.List<String> l = new java.util.ArrayList<>(Arrays.asList(head));
            l.addAll(Arrays.asList(t));
            l.addAll(Arrays.asList("Rua Coronel Camisão, 456, Mutuá,", "São Gonçalo", "Tenho interesse"));
            Offer o = only(l);
            assertEquals(t[0], 1.9, o.pickupKm, EPS);
            assertEquals(t[0], 6.0, o.pickupMin, EPS);
            assertEquals(t[0], 5.2, o.tripKm, EPS);
            assertEquals(t[0], 18.0, o.tripMin, EPS);
            assertEquals(false, o.pickupEstimated);
        }
        Offer a = frame("UberX", "X", "R$ 14,05", "Est. de R$3,12/km", ") +R$ 1,50 incluído", "a 5 min 1.6 km",
                "Rua Expedicionário Pinto", "* 4,88 (544)", "O8 minutos2.9 km", "Rua Batista da Costa, 210, Mutondo,", "Tenho interesse");
        assertEquals(1.6, a.pickupKm, EPS);
        assertEquals(2.9, a.tripKm, EPS);
        Offer b = frame("9 UberX", "X", "R$ 17,51", "Est. de R$2,58/km", "9 +R$ 1,50 incluído", "8 6mnin •1.8 km",
                "Avenida José Mendonça Campos,", "* 4,96 (722)", "O 1l minutos5.0 km", "Rua Arlindo Carvalho, 132, Boaçu,", "Tenho interesse");
        assertEquals(1.8, b.pickupKm, EPS);
        assertEquals(6.0, b.pickupMin, EPS);
        assertEquals(5.0, b.tripKm, EPS);
        assertEquals(11.0, b.tripMin, EPS);
        Offer c = frame("2 UberX", "X", "R$ 10,47", "Est. de R$1,47/km", ") +R$ l.50 incluído", "e 9 min 3.5 km",
                "R. São Pedro Alcântara, Alcântara,", "4,82 (1794)", "9 minutos3.6 km", "Rua Angustura, 134, São Gonçalo,", "Tenho interesse");
        assertEquals(3.5, c.pickupKm, EPS);
        assertEquals(3.6, c.tripKm, EPS);
    }

    @Test
    public void estimatedPickup_doesNotRejectAlone() {
        Settings s = lenoCar();
        s.profile.maxPickupKm = 3;
        Offer o = new Offer(19.78, 5.2, 16, 1.9, 6, "Mutuá, São Gonçalo");
        o.pickupEstimated = true;
        Evaluation e = Calculator.evaluate(o, s);
        assertEquals(false, e.reasonsLine().contains("✘ busca"));
        Offer real = new Offer(19.78, 5.2, 16, 1.9, 6, "Mutuá, São Gonçalo");
        assertEquals(Evaluation.Verdict.BAD, Calculator.evaluate(real, s).verdict);
    }
}
