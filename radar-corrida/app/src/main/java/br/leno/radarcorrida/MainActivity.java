package br.leno.radarcorrida;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.leno.radarcorrida.core.Evaluation;
import br.leno.radarcorrida.core.Offer;
import br.leno.radarcorrida.core.OfferParser;
import br.leno.radarcorrida.core.Profile;
import br.leno.radarcorrida.core.Ranker;

/** Tela única: status, carro, perfis e metas, simulador e exportação dos registros. */
public class MainActivity extends Activity {
    private LinearLayout col;
    private TextView status, costInfo, simOut;
    private EditText fuel, kmpl, extra;
    private Spinner profileSpinner;
    private ArrayAdapter<String> profileAdapter;
    private EditText pName, pMinKm, pMinH, pMinProfit, pRoute, pMaxPickKm, pMaxPickMin;
    private RadioButton ruleAll, ruleAny;
    private CheckBox pIncludePickup;
    private EditText overlayY, packages, simInput;
    private CheckBox debug, discovery;

    private List<Profile> profiles;
    private int shown;
    private RideLog log;

    private TextView stepRestricted, stepEnable;
    private Button btnRestricted, btnEnable;
    private boolean built;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.sp(this).getBoolean(Prefs.K_CONSENT, false)) {
            showDisclosure();
        } else {
            buildMain();
        }
    }

    /**
     * Aviso de uso da acessibilidade, com aceite explícito, antes de qualquer ativação.
     * Exigido pela política da Google Play para apps que usam a API de acessibilidade.
     */
    private void showDisclosure() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, p);
        scroll.addView(col);
        setContentView(scroll);

        title("Antes de começar", 22);
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setLineSpacing(0, 1.2f);
        t.setText("O Radar de Corrida usa o serviço de Acessibilidade do Android para LER o texto "
                + "das ofertas que aparecem na tela da Uber, 99 e inDrive: valor, distância, tempo "
                + "e endereço de destino.\n\n"
                + "• Isso é usado só para calcular R$/km, R$/hora e lucro e mostrar a etiqueta.\n"
                + "• Ele lê apenas os apps da lista de apps monitorados.\n"
                + "• Ele NÃO toca, aceita nem recusa corridas. A decisão é sempre sua.\n"
                + "• Nada é enviado para a internet: o app não tem permissão de internet. "
                + "Os registros ficam só neste aparelho e você pode apagá-los quando quiser.\n\n"
                + "Para funcionar, você vai ativar o Radar de Corrida em Configurações → Acessibilidade.");
        col.addView(t);
        button("Concordo e quero continuar", v -> {
            Prefs.sp(this).edit().putBoolean(Prefs.K_CONSENT, true).apply();
            buildMain();
            refreshStatus();
        });
        button("Agora não", v -> finish());
    }

    private void buildMain() {
        built = true;
        log = new RideLog(this);
        profiles = Prefs.profiles(this);
        shown = Prefs.activeIndex(this, profiles.size());

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, p, p, dp(48));
        scroll.addView(col);
        setContentView(scroll);

        title("Radar de Corrida", 24);
        status = new TextView(this);
        status.setTextSize(15);
        col.addView(status);
        stepRestricted = new TextView(this);
        stepRestricted.setText("Passo 1 (Android 13 ou mais novo): na tela que abrir, toque nos ⋮ "
                + "no canto de cima e em \"Permitir configurações restritas\". "
                + "Se não aparecer essa opção, pule para o passo 2.");
        stepRestricted.setPadding(0, dp(8), 0, 0);
        col.addView(stepRestricted);
        btnRestricted = new Button(this);
        btnRestricted.setText("Abrir informações do app");
        btnRestricted.setAllCaps(false);
        btnRestricted.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        col.addView(btnRestricted);
        stepEnable = new TextView(this);
        stepEnable.setText("Passo 2: em Acessibilidade, toque em \"Radar de Corrida\" e ative.");
        stepEnable.setPadding(0, dp(8), 0, 0);
        col.addView(stepEnable);
        btnEnable = new Button(this);
        btnEnable.setText("Abrir Acessibilidade");
        btnEnable.setAllCaps(false);
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(btnEnable);

        // ---- Carro ----
        title("Carro (vale para todos os perfis)", 18);
        SharedPreferences sp = Prefs.sp(this);
        fuel = field("Combustível (R$/litro)", null, sp.getString(Prefs.K_FUEL, Prefs.DEF_FUEL));
        kmpl = field("Consumo médio real (km/litro)",
                "Use o consumo medido na bomba, não o do manual.", sp.getString(Prefs.K_KMPL, Prefs.DEF_KMPL));
        extra = field("Custo extra por km (R$/km)",
                "Manutenção, pneus, óleo, depreciação, seguro rateado. Sem isso o lucro sai otimista.",
                sp.getString(Prefs.K_EXTRA, Prefs.DEF_EXTRA));
        costInfo = new TextView(this);
        costInfo.setTextSize(14);
        costInfo.setPadding(0, dp(4), 0, 0);
        col.addView(costInfo);

        // ---- Perfil ----
        title("Perfil de motorista", 18);
        profileSpinner = new Spinner(this);
        profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        col.addView(profileSpinner);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallButton("Novo perfil", v -> newProfile()));
        row.addView(smallButton("Excluir perfil", v -> deleteProfile()));
        col.addView(row);

        pName = field("Nome do perfil", null, "");
        hint("Regra de aceite");
        RadioGroup rg = new RadioGroup(this);
        ruleAll = new RadioButton(this);
        ruleAll.setText("Bater TODAS as metas ligadas (tempo integral)");
        ruleAny = new RadioButton(this);
        ruleAny.setText("Bater QUALQUER uma das metas (renda extra, trajeto)");
        rg.addView(ruleAll);
        rg.addView(ruleAny);
        col.addView(rg);

        hint("Metas — deixe em branco para desligar");
        pMinKm = field("Mínimo R$/km", "Valor ÷ km total. Remunera o desgaste do carro.", "");
        pMinH = field("Mínimo R$/hora", "Valor ÷ tempo total. Remunera o seu tempo.", "");
        pMinProfit = field("Lucro mínimo (%)",
                "(Valor − custo por km × km) ÷ valor. Mostra quanto sobra de cada real.", "");
        pRoute = field("Bairros/cidades do seu trajeto",
                "Um por linha ou separados por vírgula. Se o destino tiver um deles, conta como meta batida.", "");
        pRoute.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pRoute.setMinLines(2);

        hint("Filtros eliminatórios — recusa sempre que passar do limite");
        pMaxPickKm = field("Busca máxima (km até o passageiro)", null, "");
        pMaxPickMin = field("Busca máxima (minutos até o passageiro)", null, "");
        pIncludePickup = new CheckBox(this);
        pIncludePickup.setText("Somar o deslocamento até o passageiro no km e no tempo");
        col.addView(pIncludePickup);

        button("Salvar", v -> save(true));

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shown) return;
                collectProfile(profiles.get(shown));
                shown = pos;
                showProfile(profiles.get(shown));
                Prefs.saveProfiles(MainActivity.this, profiles, shown);
                refreshNames();
                updateCostInfo();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ---- Simulador ----
        title("Simulador", 18);
        hint("Cole o texto de uma oferta (ou um bloco do telas.txt) e veja o cálculo com o perfil ativo.");
        simInput = new EditText(this);
        simInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        simInput.setMinLines(4);
        simInput.setText("R$ 23,94\n5 min (1,8 km) de distância\nRua Doutor Lopes, São Gonçalo\n"
                + "Viagem de 23 min (13,3 km)\nAv. Amaral Peixoto, Niterói");
        col.addView(simInput);
        button("Calcular", v -> simulate());
        simOut = new TextView(this);
        simOut.setTextSize(15);
        simOut.setTextIsSelectable(true);
        col.addView(simOut);

        // ---- Diagnóstico ----
        title("Teste e diagnóstico", 18);
        debug = check("Modo diagnóstico: gravar textos das telas dos apps de corrida",
                sp.getBoolean(Prefs.K_DEBUG, true));
        discovery = check("Modo descoberta: anotar nomes de apps que mostram R$ e km",
                sp.getBoolean(Prefs.K_DISCOVERY, false));
        overlayY = field("Altura da etiqueta (dp a partir do topo)", null, sp.getString(Prefs.K_OVERLAY_Y, "90"));
        packages = field("Apps monitorados (um pacote por linha)", null,
                sp.getString(Prefs.K_PACKAGES, Prefs.DEFAULT_PACKAGES));
        packages.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        packages.setMinLines(3);
        button("Salvar diagnóstico", v -> save(true));

        // ---- Registros ----
        title("Registros", 18);
        button("Compartilhar corridas.csv", v -> share(RideLog.RIDES, 200_000));
        button("Compartilhar telas.txt (diagnóstico)", v -> share(RideLog.SCREENS, 90_000));
        button("Compartilhar descoberta.txt", v -> share(RideLog.DISCOVERY, 20_000));
        button("Apagar registros", v -> {
            log.clearAll();
            toast("Registros apagados");
        });
        hint("Arquivos ficam em: " + log.file("").getAbsolutePath());

        refreshNames();
        showProfile(profiles.get(shown));
        updateCostInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (!built) return;
        boolean on = serviceEnabled();
        status.setText(on
                ? "● Ativo — a etiqueta aparece quando chegar uma oferta."
                : "○ Desativado — faça os 2 passos abaixo (leva 30 segundos).");
        status.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        int vis = on ? View.GONE : View.VISIBLE;
        boolean needsRestricted = android.os.Build.VERSION.SDK_INT >= 33;
        stepRestricted.setVisibility(on || !needsRestricted ? View.GONE : View.VISIBLE);
        btnRestricted.setVisibility(on || !needsRestricted ? View.GONE : View.VISIBLE);
        stepEnable.setVisibility(vis);
        btnEnable.setVisibility(vis);
        if (!needsRestricted) stepEnable.setText("Em Acessibilidade, toque em \"Radar de Corrida\" e ative.");
    }

    // ---- Perfis ----

    private void showProfile(Profile p) {
        pName.setText(p.name);
        ruleAll.setChecked(p.matchAll);
        ruleAny.setChecked(!p.matchAll);
        pMinKm.setText(Prefs.edit(p.minRsPerKm));
        pMinH.setText(Prefs.edit(p.minRsPerHour));
        pMinProfit.setText(Prefs.edit(p.minProfitPct));
        StringBuilder kw = new StringBuilder();
        for (String k : p.routeKeywords) kw.append(kw.length() > 0 ? "\n" : "").append(k);
        pRoute.setText(kw.toString());
        pMaxPickKm.setText(Prefs.edit(p.maxPickupKm));
        pMaxPickMin.setText(Prefs.edit(p.maxPickupMin));
        pIncludePickup.setChecked(p.includePickup);
    }

    private void collectProfile(Profile p) {
        String n = pName.getText().toString().trim();
        p.name = n.isEmpty() ? "Perfil" : n;
        p.matchAll = !ruleAny.isChecked();
        p.minRsPerKm = Prefs.num(pMinKm.getText().toString(), 0);
        p.minRsPerHour = Prefs.num(pMinH.getText().toString(), 0);
        p.minProfitPct = Math.min(99, Prefs.num(pMinProfit.getText().toString(), 0));
        p.routeKeywords = Profile.parseKeywords(pRoute.getText().toString());
        p.maxPickupKm = Prefs.num(pMaxPickKm.getText().toString(), 0);
        p.maxPickupMin = Prefs.num(pMaxPickMin.getText().toString(), 0);
        p.includePickup = pIncludePickup.isChecked();
    }

    private void newProfile() {
        collectProfile(profiles.get(shown));
        Profile p = profiles.get(shown).copy();
        p.name = "Novo perfil";
        profiles.add(p);
        shown = profiles.size() - 1;
        Prefs.saveProfiles(this, profiles, shown);
        refreshNames();
        showProfile(p);
        toast("Perfil criado a partir do atual. Ajuste e salve.");
    }

    private void deleteProfile() {
        if (profiles.size() <= 1) {
            toast("Precisa existir pelo menos um perfil");
            return;
        }
        profiles.remove(shown);
        shown = 0;
        Prefs.saveProfiles(this, profiles, shown);
        refreshNames();
        showProfile(profiles.get(shown));
        updateCostInfo();
    }

    private void refreshNames() {
        List<String> names = new ArrayList<>();
        for (Profile p : profiles) names.add(p.name);
        profileAdapter.clear();
        profileAdapter.addAll(names);
        profileAdapter.notifyDataSetChanged();
        profileSpinner.setSelection(shown);
    }

    // ---- Salvar / informação de custo ----

    private void save(boolean notify) {
        SharedPreferences.Editor e = Prefs.sp(this).edit();
        e.putString(Prefs.K_FUEL, fuel.getText().toString());
        e.putString(Prefs.K_KMPL, kmpl.getText().toString());
        e.putString(Prefs.K_EXTRA, extra.getText().toString());
        e.putString(Prefs.K_OVERLAY_Y, overlayY.getText().toString());
        e.putString(Prefs.K_PACKAGES, packages.getText().toString());
        e.putBoolean(Prefs.K_DEBUG, debug.isChecked());
        e.putBoolean(Prefs.K_DISCOVERY, discovery.isChecked());
        e.apply();
        collectProfile(profiles.get(shown));
        Prefs.saveProfiles(this, profiles, shown);
        refreshNames();
        updateCostInfo();
        if (notify) toast("Salvo · perfil ativo: " + profiles.get(shown).name);
    }

    private void updateCostInfo() {
        br.leno.radarcorrida.core.Settings s = Prefs.settings(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Custo atual: R$ ").append(Prefs.fmt(s.costPerKm())).append("/km");
        double pct = s.profile.minProfitPct;
        if (pct > 0) {
            sb.append("\nLucro de ").append(Prefs.edit(pct)).append("% exige a corrida pagar ≥ R$ ")
                    .append(Prefs.fmt(s.breakEvenRsPerKm(pct))).append("/km");
        }
        if (s.profile.minRsPerKm > 0) {
            double margin = (1 - s.costPerKm() / s.profile.minRsPerKm) * 100;
            sb.append("\nNa meta de R$ ").append(Prefs.fmt(s.profile.minRsPerKm))
                    .append("/km sobram ").append(Prefs.edit(Math.round(margin))).append("% de cada real");
        }
        costInfo.setText(sb.toString());
    }

    // ---- Simulador ----

    private void simulate() {
        save(false);
        String raw = simInput.getText().toString();
        List<String> lines = Arrays.asList(raw.split("\\r?\\n"));
        List<Offer> offers = OfferParser.parseAll(lines);
        if (offers.isEmpty()) {
            simOut.setText("Não reconheci uma oferta nesse texto. Ele precisa ter um valor em R$ "
                    + "e ao menos um par de tempo e distância, ex.: \"23 min (13,3 km)\".");
            return;
        }
        List<Evaluation> ranked = Ranker.evaluateAndRank(offers, Prefs.settings(this));
        StringBuilder sb = new StringBuilder();
        if (ranked.size() > 1) {
            sb.append(ranked.size()).append(" ofertas na tela — da melhor para a pior:\n");
            for (int i = 0; i < ranked.size(); i++) {
                sb.append(i + 1).append("º ").append(ranked.get(i).compactLine()).append('\n');
            }
            sb.append("\nDetalhe da melhor:\n");
        }
        Evaluation ev = ranked.get(0);
        Offer o = ev.offer;
        sb.append(ev.verdictLabel()).append("  [").append(ev.profileName).append("]\n")
                .append(ev.headline()).append('\n')
                .append(ev.details()).append("\n\n");
        for (String r : ev.reasons) sb.append(r).append('\n');
        sb.append("\nLido: valor R$ ").append(Prefs.fmt(o.fare))
                .append(" | busca ").append(Prefs.fmt(o.pickupKm)).append(" km / ").append((int) o.pickupMin).append(" min")
                .append(" | viagem ").append(Prefs.fmt(o.tripKm)).append(" km / ").append((int) o.tripMin).append(" min")
                .append("\nDestino lido: ").append(o.destination.isEmpty() ? "(não encontrado)" : o.destination)
                .append("\nCusto R$ ").append(Prefs.fmt(ev.cost))
                .append(" · lucro R$ ").append(Prefs.fmt(ev.profit))
                .append(" · lucro/h R$ ").append(Prefs.fmt(ev.profitPerHour));
        simOut.setText(sb.toString());
    }

    // ---- Registros ----

    private void share(String name, int maxChars) {
        String body = log.tail(name, maxChars);
        if (body.isEmpty()) {
            toast(name + " ainda está vazio");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Radar de Corrida — " + name);
        i.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "Enviar " + name));
    }

    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = new ComponentName(this, RadarService.class).flattenToString();
        String meShort = new ComponentName(this, RadarService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }

    // ---- UI helpers ----

    private void title(String text, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        col.addView(t);
    }

    private void hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
    }

    /** Campo de texto livre (aceita vírgula decimal em qualquer teclado). */
    private EditText field(String label, String help, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setPadding(0, dp(6), 0, 0);
        col.addView(l);
        if (help != null) {
            TextView h = new TextView(this);
            h.setText(help);
            h.setTextSize(12);
            h.setAlpha(0.65f);
            col.addView(h);
        }
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        col.addView(e);
        return e;
    }

    private CheckBox check(String label, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(value);
        col.addView(c);
        return c;
    }

    private void button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        col.addView(b);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
