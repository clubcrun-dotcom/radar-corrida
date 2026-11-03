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
    /** Lucro mínimo por hora (R$/h), já descontado o custo do carro. O que paga o seu tempo. */
    public double minProfitPerHour = 0;
    /** Lucro mínimo em % do valor da corrida, após custo por km. */
    public double minProfitPct = 0;
    /** Bairros/cidades do seu trajeto. Destino contendo um deles conta como meta batida. */
    public List<String> routeKeywords = new ArrayList<>();

    /** Filtros eliminatórios (valem sempre, independente da regra). 0 = sem limite. */
    public double maxPickupKm = 0;
    public double maxPickupMin = 0;

    /** Se true, o deslocamento até o passageiro entra no km e no tempo. */
    public boolean includePickup = true;
    /** Corrida no trajeto: considerar só o custo do desvio (km até o passageiro). */
    public boolean routeDetourCostOnly = false;

    /**
     * Meta do dia deste perfil. Cada perfil tem a sua: "renda extra" pode ser R$100 bruto em 3 h,
     * "vivendo do Uber" R$250 de lucro em 10 h. NaN = ainda não definida (usa a meta antiga, global).
     */
    public double dailyGoal = Double.NaN;
    public double dailyHours = Double.NaN;
    public double busyPct = Double.NaN;
    public boolean netGoal = false;

    public boolean hasGoal() {
        return !Double.isNaN(dailyGoal);
    }

    /** Copia a meta do perfil para os ajustes de cálculo (se o perfil tiver meta própria). */
    public void applyGoal(Settings s) {
        if (!hasGoal()) return;
        s.dailyGoal = dailyGoal;
        if (!Double.isNaN(dailyHours)) s.dailyHours = dailyHours;
        if (!Double.isNaN(busyPct)) s.busyPct = busyPct;
        s.netGoal = netGoal;
    }

    public Profile copy() {
        Profile p = new Profile();
        p.name = name;
        p.matchAll = matchAll;
        p.minRsPerKm = minRsPerKm;
        p.minRsPerHour = minRsPerHour;
        p.minProfitPerHour = minProfitPerHour;
        p.minProfitPct = minProfitPct;
        p.routeKeywords = new ArrayList<>(routeKeywords);
        p.maxPickupKm = maxPickupKm;
        p.maxPickupMin = maxPickupMin;
        p.includePickup = includePickup;
        p.routeDetourCostOnly = routeDetourCostOnly;
        p.dailyGoal = dailyGoal;
        p.dailyHours = dailyHours;
        p.busyPct = busyPct;
        p.netGoal = netGoal;
        return p;
    }

    /** Devolve a palavra-chave do trajeto encontrada no destino, ou null. */
    public String routeMatch(String destination) {
        if (destination == null || destination.isEmpty() || routeKeywords.isEmpty()) return null;
        List<String> dw = words(destination);
        for (String k : routeKeywords) {
            List<String> kw = words(k);
            if (kw.isEmpty()) continue;
            boolean all = true;
            for (String w : kw) {
                boolean found = false;
                for (String d : dw) if (d.startsWith(w)) { found = true; break; }
                if (!found) { all = false; break; }
            }
            if (all) return k.trim();
        }
        return null;
    }

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(
            java.util.Arrays.asList("de", "do", "da", "dos", "das", "e"));

    /**
     * Palavras sem acento e sem "de/do/da". "Centro do Rio de Janeiro" exige centro + rio + janeiro
     * no destino: casa com "Centro, Rio de Janeiro", mas não com "Centro, São Gonçalo".
     */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split("[^a-z0-9]+")) {
            if (!w.isEmpty() && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Minúsculas e sem acento: "Colubandê" casa com "colubande". */
    public static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    public static List<String> parseKeywords(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        // Um por linha (ou ";"). Vírgula também separa, exceto quando a linha toda forma
        // um lugar só: aí use espaço ("Centro Rio de Janeiro").
        for (String s : raw.split("[\\n,;]+")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ---- Modelos de partida. São pontos de partida: calibre com o seu corridas.csv. ----

    /** Renda extra: aceita se pagar bem a hora OU se estiver no trajeto. Busca curta. */
    public static Profile extraIncomeRoute() {
        Profile p = new Profile();
        p.name = "Renda extra · trajeto";
        p.matchAll = false;
        p.minProfitPerHour = 45;
        p.maxPickupKm = 3;
        p.maxPickupMin = 8;
        p.dailyGoal = 100;
        p.dailyHours = 3;
        p.busyPct = 60;
        p.netGoal = false;
        return p;
    }

    public static final String LIVING_NAME = "Vivendo do Uber";

    /**
     * Vivendo do Uber: o carro é a ferramenta de trabalho e o custo dele sai do seu bolso.
     * Meta em LUCRO (R$250/dia em 10 h, ~R$6.500/mês em 26 dias). Precisa pagar a hora
     * E o km ao mesmo tempo: corrida longa que paga bem a hora mas mal o km gasta o carro.
     * Lucro/h mínimo = 250 / 10 h / 65% do tempo com passageiro ≈ R$39/h.
     */
    public static Profile livingFromUber() {
        Profile p = new Profile();
        p.name = LIVING_NAME;
        p.matchAll = true;
        p.minRsPerKm = 1.50;
        p.minProfitPerHour = 39;
        p.maxPickupKm = 4;
        p.maxPickupMin = 10;
        p.includePickup = true;
        p.dailyGoal = 250;
        p.dailyHours = 10;
        p.busyPct = 65;
        p.netGoal = true;
        return p;
    }

    /** Modelo antigo (sem meta própria). Mantido para os cálculos padrão e testes. */
    public static Profile fullTime() {
        Profile p = new Profile();
        p.name = "Tempo integral";
        p.matchAll = true;
        p.minRsPerKm = 1.50;
        p.minRsPerHour = 40;
        p.maxPickupKm = 5;
        return p;
    }

    /** O perfil é o modelo antigo "Tempo integral", sem nenhum ajuste do usuário. */
    public boolean isUntouchedOldFullTime() {
        return "Tempo integral".equals(name) && matchAll && minRsPerKm == 1.50 && minRsPerHour == 40
                && minProfitPerHour == 0 && minProfitPct == 0 && routeKeywords.isEmpty()
                && maxPickupKm == 5 && maxPickupMin == 0;
    }

    public static List<Profile> presets() {
        List<Profile> l = new ArrayList<>();
        l.add(extraIncomeRoute());
        l.add(livingFromUber());
        return l;
    }
}
