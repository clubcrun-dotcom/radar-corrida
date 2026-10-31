package br.leno.radarcorrida.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai valor, busca (km/min) e viagem (km/min) da lista de textos lidos da tela.
 *
 * Os formatos exatos de Uber, 99 e inDrive mudam com atualizações, por isso o parser é
 * heurístico e tolerante. O modo diagnóstico do app grava os textos reais da tela para
 * que estas regras sejam calibradas com dados de campo.
 *
 * Formatos de par distância/tempo aceitos:
 *   "5 min (1,8 km)"      "1 h 5 min (52,4 km)"      "3 min (800 m)"
 *   "13,3 km · 23 min"    "23 min · 13,3 km"         (separadores · • - |)
 */
public final class OfferParser {
    private OfferParser() {}

    private static final String NUM = "(\\d{1,3}(?:\\.\\d{3})*(?:,\\d+)?|\\d+(?:[.,]\\d+)?)";

    /** Número de dinheiro: "17,48", "17.48", "1.413,60", "1748" (OCR sem vírgula). */
    private static final String MNUM = "(\\d+(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?)";

    /** Separador entre tempo e distância; opcional porque a leitura por imagem às vezes perde o "•". */
    private static final String SEP = "[·•|\\-–.,*°]?";

    // R$ 23,94 | R$23,94 | +R$ 3,00 | R$ 1,52/km | "RS 23,94" (leitura por imagem)
    private static final Pattern MONEY = Pattern.compile(
            "(\\+\\s*)?(?-i:R\\s?[$S])\\s*" + MNUM + "\\s*(/\\s*(?:km|h|hr|hora)|por\\s+(?:km|hora))?",
            Pattern.CASE_INSENSITIVE);

    // "1 h 5 min (52,4 km)" ou "5 min (800 m)"
    private static final Pattern MIN_PAREN_DIST = Pattern.compile(
            "(?:(\\d+)\\s*h\\s*)?(\\d+)\\s*min\\w*\\s*\\(\\s*" + NUM + "\\s*(km|m)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    // "13,3 km · 23 min"
    private static final Pattern DIST_SEP_MIN = Pattern.compile(
            NUM + "\\s*(km|m)\\b\\s*" + SEP + "\\s*(?:(\\d+)\\s*h\\s*)?(\\d+)\\s*min",
            Pattern.CASE_INSENSITIVE);

    // "23 min · 13,3 km"
    private static final Pattern MIN_SEP_DIST = Pattern.compile(
            "(?:(\\d+)\\s*h\\s*)?(\\d+)\\s*min\\w*\\s*" + SEP + "\\s*" + NUM + "\\s*(km|m)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String[] PICKUP_HINTS = {
            "distância", "distancia", "de você", "de voce", "até o passageiro", "ate o passageiro",
            "buscar", "embarque", "away", "pickup"
    };
    private static final String[] TRIP_HINTS = {
            "viagem", "trip", "destino", "percurso", "corrida de", "total"
    };

    /** Um par distância/tempo encontrado, com a posição (linha) e a dica de papel. */
    static final class Leg {
        final int line;
        final double km;
        final double min;
        final int role; // -1 desconhecido, 0 busca, 1 viagem

        Leg(int line, double km, double min, int role) {
            this.line = line;
            this.km = km;
            this.min = min;
            this.role = role;
        }
    }

    /** Devolve a oferta ou null se o conjunto de textos não parece uma oferta. */
    public static Offer parse(List<String> texts) {
        if (texts == null || texts.isEmpty()) return null;
        texts = normalizeAll(texts);

        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isEmpty()) continue;
            int role = roleOf(t);
            collectLegs(t, i, role, legs);
        }
        if (legs.isEmpty()) return null;

        Double fare = pickFare(texts, legs.get(0).line);
        if (fare == null || fare <= 0) return null;

        Leg pickup = null;
        Leg trip = null;
        for (Leg l : legs) {
            if (l.role == 0 && pickup == null) pickup = l;
            else if (l.role == 1 && trip == null) trip = l;
        }
        List<Leg> unknown = new ArrayList<>();
        for (Leg l : legs) if (l != pickup && l != trip) unknown.add(l);

        if (trip == null && pickup == null) {
            if (unknown.size() >= 2) {
                // Ordem de tela usual: primeiro a busca, depois a viagem.
                pickup = unknown.get(0);
                trip = unknown.get(1);
            } else {
                trip = unknown.get(0);
            }
        } else if (trip == null) {
            if (!unknown.isEmpty()) trip = unknown.get(0);
        } else if (pickup == null) {
            for (Leg l : unknown) {
                if (l.line < trip.line) { pickup = l; break; }
            }
        }
        if (trip == null) {
            // Só achamos a busca: sem viagem não há decisão possível.
            return null;
        }

        double pKm = pickup != null ? pickup.km : 0;
        double pMin = pickup != null ? pickup.min : 0;
        double tKm = trip.km;

        // Conferência com o "Est. de R$ X/km" da própria Uber (que já soma a busca).
        // Corrige leituras por imagem que perderam a vírgula ("70 km" em vez de "7.0 km",
        // "11 km" em vez de "1.1 km") ou que não acharam a linha da busca.
        Double anchor = perKmAnchor(texts);
        if (anchor != null) {
            double implied = fare / anchor;
            double err = Math.abs(tKm + pKm - implied) / implied;
            if (err > 0.08) {
                double[] tc = {tKm, tKm / 10, tKm / 100};
                double[] pc = pickup != null ? new double[]{pKm, pKm / 10, pKm / 100} : new double[]{0};
                double bt = tKm, bp = pKm, best = Double.MAX_VALUE;
                for (double t : tc) {
                    for (double pk : pc) {
                        double e = Math.abs(t + pk - implied);
                        if (e < best) { best = e; bt = t; bp = pk; }
                    }
                }
                if (best / implied <= 0.10) {
                    tKm = bt;
                    pKm = bp;
                } else {
                    // Busca não lida: viagem = candidato mais próximo abaixo do total; busca = o resto.
                    double t = tKm;
                    for (double c : tc) if (c < implied && Math.abs(implied - c) < Math.abs(implied - t)) t = c;
                    if (t < implied) {
                        tKm = t;
                        pKm = implied - t;
                        double speed = trip.min > 0 ? tKm / trip.min : 0;  // km por minuto
                        if (speed > 0) pMin = Math.round(pKm / speed);
                    }
                }
            }
        }

        // Se mesmo após a correção a leitura não bate com a estimativa da Uber, é melhor
        // não mostrar nada do que mostrar um número errado.
        if (anchor != null && Math.abs(tKm + pKm - fare / anchor) / (fare / anchor) > 0.25) return null;

        Offer o = new Offer(fare, round1(pKm), pMin, round1(tKm), trip.min,
                destinationAfter(texts, trip.line, legs));

        // Sanidade: descarta leituras absurdas (ex.: ganhos do dia confundidos com tarifa).
        double km = o.tripKm + o.pickupKm;
        if (km <= 0 || o.tripMin <= 0) return null;
        double rsKm = o.fare / km;
        if (rsKm < 0.2 || rsKm > 30) return null;
        return o;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** "Est. de R$2,16/km" da Uber: R$/km já com a busca somada. Null se não houver. */
    static Double perKmAnchor(List<String> texts) {
        for (String t : texts) {
            if (t == null || t.indexOf('R') < 0) continue;
            Matcher m = MONEY.matcher(t);
            while (m.find()) {
                if (m.group(1) != null) continue;
                boolean perKm = m.group(3) != null && m.group(3).toLowerCase(Locale.ROOT).contains("km");
                boolean est = isEstimateLine(t);
                if (!perKm && !est) continue;
                double v = money(m.group(2));
                if (v >= 0.3 && v <= 15) return v;
            }
        }
        return null;
    }

    private static boolean isEstimateLine(String t) {
        String low = t.toLowerCase(Locale.ROOT);
        return low.contains("est") && low.contains("de r");
    }

    // ---- Correção de leitura por imagem (OCR) ----

    private static final Pattern OCR_S5 = Pattern.compile("\\bS(?=\\s*min)");
    private static final Pattern OCR_MIN_GLUE = Pattern.compile("(?<=min)(?=[lIL|!\\d])");
    private static final Pattern OCR_BEFORE_KM = Pattern.compile(
            "(?<![A-Za-zÀ-ÿ])([0-9lIL|!O][0-9lIL|!O.,]{0,5})(\\s*km\\b)");
    private static final Pattern OCR_BEFORE_MIN = Pattern.compile(
            "(?<![A-Za-zÀ-ÿ])([0-9lIL|!O]{1,3})(\\s*min)");

    /**
     * Corrige confusões típicas da leitura por imagem perto de "km" e "min":
     * "ll km" → "11 km", "1.l km" → "1.1 km", "Smin" → "5min", "7.O km" → "7.0 km".
     * Não mexe no resto da linha (endereços ficam como estão).
     */
    static String normalizeOcr(String t) {
        if (t == null) return null;
        if (t.indexOf("km") < 0 && t.indexOf("min") < 0) return t;
        String s = OCR_S5.matcher(t).replaceAll("5");
        s = OCR_MIN_GLUE.matcher(s).replaceAll(" ");
        s = fixDigits(OCR_BEFORE_KM, s);
        s = fixDigits(OCR_BEFORE_MIN, s);
        return s;
    }

    private static String fixDigits(Pattern p, String s) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String num = m.group(1).replaceAll("[lIL|!]", "1").replace('O', '0');
            m.appendReplacement(sb, Matcher.quoteReplacement(num + m.group(2)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static List<String> normalizeAll(List<String> texts) {
        List<String> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(normalizeOcr(t));
        return out;
    }

    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}{3,}");
    private static final String[] NOT_ADDRESS = {
            "aceitar", "recusar", "accept", "decline", "confirmar", "ver mais", "toque para",
            "tenho interesse", "interesse", "deslize", "match"
    };

    /**
     * Destino = até 2 linhas com texto logo depois da linha da viagem (padrão Uber: o endereço
     * de destino vem embaixo de "Viagem de X min (Y km)"). Para na próxima linha com valor ou
     * distância. A regra deve ser conferida com o telas.txt de cada app.
     */
    static String destinationAfter(List<String> texts, int tripLine, List<Leg> legs) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (int i = tripLine + 1; i < texts.size() && taken < 2; i++) {
            String t = texts.get(i);
            if (t == null) continue;
            boolean isLeg = false;
            for (Leg l : legs) if (l.line == i) { isLeg = true; break; }
            if (isLeg || t.contains("R$")) break;
            String low = t.toLowerCase(Locale.ROOT);
            boolean skip = !HAS_LETTER.matcher(t).find();
            for (String w : NOT_ADDRESS) if (low.contains(w)) { skip = true; break; }
            if (skip) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t.trim());
            taken++;
        }
        return sb.toString();
    }

    private static void collectLegs(String t, int line, int role, List<Leg> out) {
        boolean[] used = new boolean[t.length() + 1];

        Matcher m = MIN_PAREN_DIST.matcher(t);
        while (m.find()) {
            double min = toMin(m.group(1), m.group(2));
            double km = toKm(m.group(3), m.group(4));
            out.add(new Leg(line, km, min, role));
            mark(used, m.start(), m.end());
        }
        m = DIST_SEP_MIN.matcher(t);
        while (m.find()) {
            if (overlaps(used, m.start(), m.end())) continue;
            double km = toKm(m.group(1), m.group(2));
            double min = toMin(m.group(3), m.group(4));
            out.add(new Leg(line, km, min, role));
            mark(used, m.start(), m.end());
        }
        m = MIN_SEP_DIST.matcher(t);
        while (m.find()) {
            if (overlaps(used, m.start(), m.end())) continue;
            double min = toMin(m.group(1), m.group(2));
            double km = toKm(m.group(3), m.group(4));
            out.add(new Leg(line, km, min, role));
            mark(used, m.start(), m.end());
        }
    }

    /**
     * Escolhe a tarifa: o último valor "R$" antes do primeiro par distância/tempo
     * (o cartão da oferta mostra o preço logo acima da busca). Ignora "+R$" (dinâmico
     * já incluído) e valores por km/hora. Se não houver nenhum antes, usa o primeiro depois.
     */
    static Double pickFare(List<String> texts, int firstLegLine) {
        Double before = null;
        Double after = null;
        for (int i = 0; i < texts.size(); i++) {
            Double v = lastFareIn(texts.get(i));
            if (v == null) continue;
            if (i <= firstLegLine) before = v;
            else if (after == null) after = v;
        }
        return before != null ? before : after;
    }

    /** Último valor de tarifa válido numa linha (ignora "+R$ x" e "R$ x/km"), ou null. */
    static Double lastFareIn(String t) {
        if (t == null || t.indexOf('R') < 0) return null;
        Double found = null;
        Matcher m = MONEY.matcher(t);
        while (m.find()) {
            if (m.group(1) != null) continue;  // +R$ x
            if (m.group(3) != null) continue;  // R$ x/km
            if (isEstimateLine(t)) continue;   // "Est. de R$2,16/km" (mesmo se o "/km" não for lido)
            double v = money(m.group(2));
            if (v > 0) found = v;
        }
        return found;
    }

    /**
     * Várias ofertas na mesma tela (lista do inDrive, ofertas empilhadas da 99/Uber).
     * Divide a tela em blocos, um por tarifa, e lê cada bloco como uma oferta.
     *  - Layout "tarifa primeiro" (Uber): o bloco vai de uma tarifa até a próxima.
     *  - Layout "tarifa no fim": o bloco termina na tarifa.
     * Blocos sem par distância/tempo (ex.: "ganhos do dia") são descartados.
     * Com uma tarifa só, equivale a {@link #parse(List)}.
     */
    public static List<Offer> parseAll(List<String> texts) {
        List<Offer> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return out;
        texts = normalizeAll(texts);

        List<Integer> fareLines = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (lastFareIn(texts.get(i)) != null) fareLines.add(i);
        }

        if (fareLines.size() >= 2) {
            int firstLeg = firstLegLine(texts);
            boolean fareFirst = firstLeg < 0 || fareLines.get(0) <= firstLeg;
            java.util.Set<String> seen = new java.util.HashSet<>();
            int n = fareLines.size();
            for (int k = 0; k < n; k++) {
                int from;
                int to;
                if (fareFirst) {
                    from = fareLines.get(k);
                    to = k + 1 < n ? fareLines.get(k + 1) : texts.size();
                } else {
                    from = k == 0 ? 0 : fareLines.get(k - 1) + 1;
                    to = fareLines.get(k) + 1;
                }
                Offer o = parse(texts.subList(from, to));
                if (o != null && seen.add(o.signature())) out.add(o);
            }
            if (!out.isEmpty()) return out;
        }

        Offer single = parse(texts);
        if (single != null) out.add(single);
        return out;
    }

    private static int firstLegLine(List<String> texts) {
        List<Leg> tmp = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t == null || t.isEmpty()) continue;
            collectLegs(t, i, -1, tmp);
            if (!tmp.isEmpty()) return i;
        }
        return -1;
    }

    private static int roleOf(String t) {
        String s = t.toLowerCase(Locale.forLanguageTag("pt-BR"));
        for (String h : PICKUP_HINTS) if (s.contains(h)) return 0;
        for (String h : TRIP_HINTS) if (s.contains(h)) return 1;
        return -1;
    }

    private static double toMin(String hours, String mins) {
        double h = hours != null ? Double.parseDouble(hours) : 0;
        return h * 60 + Double.parseDouble(mins);
    }

    private static double toKm(String num, String unit) {
        double v = parseNumber(num);
        return "m".equalsIgnoreCase(unit) ? v / 1000.0 : v;
    }

    /**
     * Valor em R$. A leitura por imagem às vezes perde a vírgula ("R$ 1748" em vez de
     * "R$ 17,48"); como os apps sempre mostram centavos, 3+ dígitos sem separador = centavos.
     */
    static double money(String s) {
        String t = s.trim();
        if (t.matches("\\d{3,}")) return Double.parseDouble(t) / 100.0;
        return parseNumber(t);
    }

    /** Converte "1.234,56", "13,3", "13.3", "1.234" (milhar) em double. */
    static double parseNumber(String s) {
        s = s.trim();
        boolean hasDot = s.indexOf('.') >= 0;
        boolean hasComma = s.indexOf(',') >= 0;
        if (hasDot && hasComma) {
            s = s.replace(".", "").replace(',', '.');
        } else if (hasComma) {
            s = s.replace(',', '.');
        } else if (hasDot) {
            int idx = s.lastIndexOf('.');
            if (s.length() - idx - 1 == 3) s = s.replace(".", "");
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void mark(boolean[] used, int a, int b) {
        for (int i = a; i < b && i < used.length; i++) used[i] = true;
    }

    private static boolean overlaps(boolean[] used, int a, int b) {
        for (int i = a; i < b && i < used.length; i++) if (used[i]) return true;
        return false;
    }
}
