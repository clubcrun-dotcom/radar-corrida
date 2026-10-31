package br.leno.radarcorrida.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Perfil de motorista = estratégia de aceite. Os custos do carro ficam em {@link Settings}
 * (valem para todos os perfis); aqui ficam as metas e a regra de combinação.
 *
 * Qualquer meta com valor 0 (ou lista vazia) fica desligada.
 */
public final class Profile {
    public String name = "Perfil";
    /** true: precisa bater TODAS as metas ligadas. false: basta UMA. */
    public boolean matchAll = true;

    public double minRsPerKm = 0;
    public double minRsPerHour = 0;
    /** Lucro mínimo em % do valor da corrida, após custo por km. */
    public double minProfitPct = 0;
    /** Bairros/cidades do seu trajeto. Destino contendo um deles conta como meta batida. */
    public List<String> routeKeywords = new ArrayList<>();

    /** Filtros eliminatórios (valem sempre, independente da regra). 0 = sem limite. */
    public double maxPickupKm = 0;
    public double maxPickupMin = 0;

    /** Se true, o deslocamento até o passageiro entra no km e no tempo. */
    public boolean includePickup = true;

    public Profile copy() {
        Profile p = new Profile();
        p.name = name;
        p.matchAll = matchAll;
        p.minRsPerKm = minRsPerKm;
        p.minRsPerHour = minRsPerHour;
        p.minProfitPct = minProfitPct;
        p.routeKeywords = new ArrayList<>(routeKeywords);
        p.maxPickupKm = maxPickupKm;
        p.maxPickupMin = maxPickupMin;
        p.includePickup = includePickup;
        return p;
    }

    /** Devolve a palavra-chave do trajeto encontrada no destino, ou null. */
    public String routeMatch(String destination) {
        if (destination == null || destination.isEmpty() || routeKeywords.isEmpty()) return null;
        String d = norm(destination);
        for (String k : routeKeywords) {
            String nk = norm(k);
            if (!nk.isEmpty() && d.contains(nk)) return k.trim();
        }
        return null;
    }

    /** Minúsculas e sem acento: "Colubandê" casa com "colubande". */
    public static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    public static List<String> parseKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split("[\\n,;]+")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ---- Modelos de partida. São pontos de partida: calibre com o seu corridas.csv. ----

    /** Renda extra: aceita se estiver no trajeto OU se o lucro for muito alto. Busca curta. */
    public static Profile extraIncomeRoute() {
        Profile p = new Profile();
        p.name = "Renda extra · trajeto";
        p.matchAll = false;
        p.minProfitPct = 90;
        p.maxPickupKm = 3;
        p.maxPickupMin = 8;
        return p;
    }

    /** Tempo integral: precisa remunerar o km e a hora ao mesmo tempo. */
    public static Profile fullTime() {
        Profile p = new Profile();
        p.name = "Tempo integral";
        p.matchAll = true;
        p.minRsPerKm = 1.50;
        p.minRsPerHour = 40;
        p.maxPickupKm = 5;
        return p;
    }

    public static List<Profile> presets() {
        List<Profile> l = new ArrayList<>();
        l.add(extraIncomeRoute());
        l.add(fullTime());
        return l;
    }
}
